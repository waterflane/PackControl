package org.wodichka.packcontrol.client.update;

import org.wodichka.packcontrol.updateformat.CancellationToken;
import org.wodichka.packcontrol.updateformat.GitHubReleaseDiscoveryService;
import org.wodichka.packcontrol.updateformat.GitHubReleaseDiscoveryService.CheckResult;
import org.wodichka.packcontrol.updateformat.InstalledPackState;
import org.wodichka.packcontrol.updateformat.InstalledStateStore;
import org.wodichka.packcontrol.updateformat.PackControlManifest;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan;
import org.wodichka.packcontrol.updateformat.PackUpdatePlanner;
import org.wodichka.packcontrol.updateformat.TransactionalPackInstaller;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class PackUpdateCoordinator {
    private final Path instanceRoot;
    private final GitHubReleaseDiscoveryService releases;
    private final InstalledStateStore stateStore;
    private final PackUpdatePlanner planner;
    private final TransactionalPackInstaller installer;
    private final Executor backgroundExecutor;
    private final Executor uiExecutor;
    private final CancellationToken cancellation = new CancellationToken();

    private Consumer<PackUpdateViewModel> listener = ignored -> {
    };
    private volatile PackUpdateViewModel model;
    private volatile PackControlManifest selectedManifest;

    public PackUpdateCoordinator(
            Path instanceRoot,
            GitHubReleaseDiscoveryService releases,
            Executor backgroundExecutor,
            Executor uiExecutor,
            String installedVersion,
            String availableVersion
    ) {
        this.instanceRoot = instanceRoot.toAbsolutePath().normalize();
        this.releases = releases;
        this.stateStore = new InstalledStateStore(this.instanceRoot);
        this.planner = new PackUpdatePlanner();
        this.installer = new TransactionalPackInstaller(this.instanceRoot);
        this.backgroundExecutor = backgroundExecutor;
        this.uiExecutor = uiExecutor;
        this.model = PackUpdateViewModel.idle(installedVersion, availableVersion);
    }

    public PackUpdateViewModel model() {
        return model;
    }

    public void setListener(Consumer<PackUpdateViewModel> listener) {
        this.listener = listener == null ? ignored -> {
        } : listener;
        this.listener.accept(model);
    }

    public void check(CheckInput input, Consumer<CheckResult> persistResult) {
        if (model.busy()) {
            return;
        }
        publish(copy(PackUpdateViewModel.Stage.CHECKING, "Checking for updates...", false));
        CompletableFuture.supplyAsync(() -> discover(input, persistResult), backgroundExecutor)
                .whenCompleteAsync(this::complete, uiExecutor);
    }

    public void prepare() {
        PackControlManifest manifest = selectedManifest;
        if (model.busy() || manifest == null) {
            return;
        }
        publish(copy(PackUpdateViewModel.Stage.PREPARING, "Downloading and verifying update...", false));
        CompletableFuture.supplyAsync(() -> {
            TransactionalPackInstaller.PreparationResult result = installer.prepare(manifest);
            if (!result.success()) {
                return error(
                        result.message(),
                        result.plan() == null ? List.of() : issueDetails(result.plan()),
                        false
                );
            }
            return new PackUpdateViewModel(
                    PackUpdateViewModel.Stage.READY_TO_RESTART,
                    model.installedVersion(),
                    model.availableVersion(),
                    model.changelog(),
                    "Download verified. Apply the update and restart Minecraft when ready.",
                    model.summary(),
                    model.details(),
                    false
            );
        }, backgroundExecutor).whenCompleteAsync(this::complete, uiExecutor);
    }

    public void applyPrepared(Runnable persistInstalledVersion, Runnable restartMinecraft) {
        if (model.busy() || model.stage() != PackUpdateViewModel.Stage.READY_TO_RESTART) {
            return;
        }
        publish(copy(PackUpdateViewModel.Stage.APPLYING, "Applying update with backup...", false));
        CompletableFuture.supplyAsync(() -> {
            TransactionalPackInstaller.InstallResult result = installer.applyPreparedUpdate();
            if (!result.success()) {
                boolean rollbackNeeded = result.rollbackAttempted() && !result.rollbackSucceeded();
                String message = result.message();
                if (result.rollbackSucceeded()) {
                    message += " Previous files were restored automatically.";
                }
                return error(
                        message,
                        result.plan() == null ? List.of() : issueDetails(result.plan()),
                        rollbackNeeded
                );
            }
            if (persistInstalledVersion != null) {
                persistInstalledVersion.run();
            }
            return new PackUpdateViewModel(
                    PackUpdateViewModel.Stage.UP_TO_DATE,
                    model.availableVersion(),
                    model.availableVersion(),
                    model.changelog(),
                    "Update applied. Restarting Minecraft...",
                    model.summary(),
                    model.details(),
                    false
            );
        }, backgroundExecutor).whenCompleteAsync((next, failure) -> {
            complete(next, failure);
            if (failure == null
                    && next != null
                    && next.stage() == PackUpdateViewModel.Stage.UP_TO_DATE
                    && restartMinecraft != null) {
                restartMinecraft.run();
            }
        }, uiExecutor);
    }

    public void rollback() {
        if (model.busy()) {
            return;
        }
        publish(copy(PackUpdateViewModel.Stage.ROLLING_BACK, "Restoring backup...", false));
        CompletableFuture.supplyAsync(() -> {
            TransactionalPackInstaller.RollbackResult result = installer.rollbackLastUpdate();
            return new PackUpdateViewModel(
                    result.success()
                            ? PackUpdateViewModel.Stage.ROLLED_BACK
                            : PackUpdateViewModel.Stage.ERROR,
                    model.installedVersion(),
                    model.availableVersion(),
                    model.changelog(),
                    result.message(),
                    model.summary(),
                    model.details(),
                    !result.success()
            );
        }, backgroundExecutor).whenCompleteAsync(this::complete, uiExecutor);
    }

    public void close() {
        cancellation.cancel();
        listener = ignored -> {
        };
    }

    private PackUpdateViewModel discover(CheckInput input, Consumer<CheckResult> persistResult) {
        String installedVersion = input.installedVersion();
        try {
            Optional<InstalledPackState> installedState = stateStore.load();
            if (installedState.isPresent()) {
                installedVersion = installedState.get().packVersion();
            }
            CheckResult result = releases.check(
                    new GitHubReleaseDiscoveryService.CheckRequest(
                            input.repository(),
                            input.channel(),
                            installedVersion,
                            input.minimumInterval()
                    ),
                    cancellation
            );
            if (persistResult != null) {
                persistResult.accept(result);
            }
            if (result.release() == null) {
                PackUpdateViewModel.Stage stage =
                        result.status() == GitHubReleaseDiscoveryService.CheckStatus.UP_TO_DATE
                                ? PackUpdateViewModel.Stage.UP_TO_DATE
                                : PackUpdateViewModel.Stage.ERROR;
                return new PackUpdateViewModel(
                        stage,
                        installedVersion,
                        model.availableVersion(),
                        model.changelog(),
                        result.message(),
                        null,
                        List.of(),
                        false
                );
            }

            PackControlManifest manifest = result.release().manifest();
            PackUpdatePlan plan = planner.plan(manifest, installedState, instanceRoot);
            PackUpdateSummary summary =
                    PackUpdateSummary.create(manifest, plan, installedState, instanceRoot);
            List<String> details = issueDetails(plan);
            if (plan.isBlocked()) {
                return new PackUpdateViewModel(
                        PackUpdateViewModel.Stage.ERROR,
                        installedVersion,
                        result.release().version(),
                        result.release().changelog(),
                        "The update cannot be prepared safely.",
                        summary,
                        details,
                        false
                );
            }
            selectedManifest = manifest;
            return new PackUpdateViewModel(
                    result.updateAvailable()
                            ? PackUpdateViewModel.Stage.UPDATE_AVAILABLE
                            : PackUpdateViewModel.Stage.UP_TO_DATE,
                    installedVersion,
                    result.release().version(),
                    result.release().changelog(),
                    result.message(),
                    summary,
                    details,
                    false
            );
        } catch (Exception exception) {
            return error("Could not build update plan: " + exception.getMessage(), List.of(), false);
        }
    }

    private static List<String> issueDetails(PackUpdatePlan plan) {
        List<String> details = new ArrayList<>();
        plan.errors().forEach(issue -> details.add("Error: " + issue.message()));
        plan.warnings().forEach(issue -> details.add("Warning: " + issue.message()));
        return List.copyOf(details);
    }

    private PackUpdateViewModel error(String message, List<String> details, boolean rollbackSuggested) {
        return new PackUpdateViewModel(
                PackUpdateViewModel.Stage.ERROR,
                model.installedVersion(),
                model.availableVersion(),
                model.changelog(),
                message,
                model.summary(),
                details,
                rollbackSuggested
        );
    }

    private PackUpdateViewModel copy(
            PackUpdateViewModel.Stage stage,
            String message,
            boolean rollbackSuggested
    ) {
        return new PackUpdateViewModel(
                stage,
                model.installedVersion(),
                model.availableVersion(),
                model.changelog(),
                message,
                model.summary(),
                model.details(),
                rollbackSuggested
        );
    }

    private void complete(PackUpdateViewModel next, Throwable failure) {
        if (failure != null) {
            publish(error("Update operation failed: " + rootMessage(failure), List.of(), false));
        } else {
            publish(next);
        }
    }

    private void publish(PackUpdateViewModel next) {
        model = next;
        listener.accept(next);
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record CheckInput(
            String repository,
            String channel,
            String installedVersion,
            Duration minimumInterval
    ) {
    }
}
