package org.wodichka.packcontrol.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.wodichka.packcontrol.client.update.PackUpdateCoordinator;
import org.wodichka.packcontrol.client.update.PackUpdateSummary;
import org.wodichka.packcontrol.client.update.PackUpdateViewModel;
import org.wodichka.packcontrol.client.update.UpdateDetailsPanel;
import org.wodichka.packcontrol.client.update.UpdatePlanPanel;
import org.wodichka.packcontrol.client.update.UpdateVersionPanel;
import org.wodichka.packcontrol.config.PackControlConfig;
import org.wodichka.packcontrol.updateformat.GitHubReleaseDiscoveryService;
import org.wodichka.packcontrol.updateformat.PackHttpClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ForkJoinPool;

@Environment(EnvType.CLIENT)
public final class PackControlScreen extends Screen {
    private static final int BACKGROUND = 0xF00C101B;
    private static final int TEXT = 0xFFE7E9F3;
    private static final int MUTED = 0xFF9EA7BC;
    private static final int WARNING = 0xFFFFD863;
    private static final int ERROR = 0xFFFF7D7D;

    private static GitHubReleaseDiscoveryService sharedReleaseService;
    private static String sharedApiBase;

    private final Screen parent;
    private final PackUpdateCoordinator coordinator;
    private final UpdateVersionPanel versionPanel = new UpdateVersionPanel();
    private final UpdatePlanPanel planPanel = new UpdatePlanPanel();
    private final UpdateDetailsPanel detailsPanel = new UpdateDetailsPanel();

    private PackUpdateViewModel model;
    private Button checkButton;
    private Button primaryButton;
    private Button rollbackButton;
    private Button detailsButton;
    private boolean showAdvancedDetails;

    public PackControlScreen(Screen parent) {
        super(Component.translatable("packcontrol.screen.title"));
        this.parent = parent;
        PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
        this.coordinator = new PackUpdateCoordinator(
                PackControlConfig.gameDirectory(),
                releaseService(PackControlConfig.user().githubApiBaseUrl),
                ForkJoinPool.commonPool(),
                command -> Minecraft.getInstance().execute(command),
                pack.installedVersion,
                pack.latestKnownVersion
        );
        this.model = coordinator.model();
    }

    @Override
    protected void init() {
        int left = contentLeft();
        int bottom = contentTop() + contentHeight();
        int buttonY = bottom - 28;
        int gap = 8;
        int rightActionsWidth = Math.min(238, Math.max(160, contentWidth() / 3));
        int leftActionsWidth = contentWidth() - rightActionsWidth - gap;
        int checkWidth = Math.min(150, Math.max(64, (leftActionsWidth - gap) / 2));
        int primaryX = left + checkWidth + gap;
        int primaryWidth = Math.max(64, leftActionsWidth - checkWidth - gap);
        int backWidth = Math.min(96, rightActionsWidth / 2);
        int detailsWidth = rightActionsWidth - backWidth - gap;
        int detailsX = left + contentWidth() - rightActionsWidth;

        checkButton = addRenderableWidget(Button.builder(
                Component.translatable("packcontrol.update.check"),
                ignored -> checkForUpdates()
        ).bounds(left, buttonY, checkWidth, 20).build());

        primaryButton = addRenderableWidget(Button.builder(
                Component.translatable("packcontrol.update.download"),
                ignored -> runPrimaryAction()
        ).bounds(primaryX, buttonY, primaryWidth, 20).build());

        rollbackButton = addRenderableWidget(Button.builder(
                Component.translatable("packcontrol.update.rollback"),
                ignored -> coordinator.rollback()
        ).bounds(primaryX, buttonY, primaryWidth, 20).build());

        detailsButton = addRenderableWidget(Button.builder(
                Component.translatable("packcontrol.update.details"),
                ignored -> {
                    showAdvancedDetails = !showAdvancedDetails;
                    updateButtons();
                }
        ).bounds(detailsX, buttonY, detailsWidth, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("packcontrol.action.back"),
                ignored -> onClose()
        ).bounds(left + contentWidth() - backWidth, buttonY, backWidth, 20).build());

        coordinator.setListener(next -> {
            model = next;
            updateButtons();
        });
        updateButtons();
    }

    private void checkForUpdates() {
        PackControlConfig.PackControlUserConfig user = PackControlConfig.user();
        PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
        if (!user.useGitHubReleases) {
            model = new PackUpdateViewModel(
                    PackUpdateViewModel.Stage.ERROR,
                    model.installedVersion(),
                    model.availableVersion(),
                    model.changelog(),
                    Component.translatable("packcontrol.github.disabled").getString(),
                    model.summary(),
                    model.details(),
                    false
            );
            updateButtons();
            return;
        }
        coordinator.check(
                new PackUpdateCoordinator.CheckInput(
                        pack.targetGithubRepository,
                        pack.updateChannel,
                        pack.installedVersion,
                        Duration.ofMinutes(user.updateCheckIntervalMinutes)
                ),
                result -> {
                    pack.lastUpdateCheck = result.checkedAt().toString();
                    pack.lastReleaseCheckStatus = result.message();
                    if (result.release() != null) {
                        pack.latestKnownVersion = result.release().version();
                    }
                    PackControlConfig.savePack();
                }
        );
    }

    private void runPrimaryAction() {
        if (model.stage() == PackUpdateViewModel.Stage.UPDATE_AVAILABLE) {
            coordinator.prepare();
        } else if (model.stage() == PackUpdateViewModel.Stage.READY_TO_RESTART) {
            coordinator.applyPrepared(
                    () -> {
                        PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
                        pack.installedVersion = model.availableVersion();
                        pack.latestKnownVersion = model.availableVersion();
                        pack.lastReleaseCheckStatus = "Installed " + model.availableVersion();
                        PackControlConfig.savePack();
                    },
                    () -> minecraft.stop()
            );
        }
    }

    private void updateButtons() {
        if (checkButton == null) {
            return;
        }
        checkButton.active = !model.busy()
                && model.stage() != PackUpdateViewModel.Stage.READY_TO_RESTART;
        primaryButton.visible = model.stage() == PackUpdateViewModel.Stage.UPDATE_AVAILABLE
                || model.stage() == PackUpdateViewModel.Stage.READY_TO_RESTART
                || model.stage() == PackUpdateViewModel.Stage.PREPARING
                || model.stage() == PackUpdateViewModel.Stage.APPLYING;
        primaryButton.active = !model.busy();
        primaryButton.setMessage(Component.translatable(
                model.stage() == PackUpdateViewModel.Stage.READY_TO_RESTART
                        || model.stage() == PackUpdateViewModel.Stage.APPLYING
                        ? "packcontrol.update.apply_restart"
                        : "packcontrol.update.download"
        ));
        rollbackButton.visible = model.rollbackSuggested()
                || model.stage() == PackUpdateViewModel.Stage.ROLLING_BACK;
        rollbackButton.active = !model.busy();
        detailsButton.visible = model.summary() != null || !model.details().isEmpty();
        detailsButton.active = !model.busy();
        detailsButton.setMessage(Component.translatable(
                showAdvancedDetails
                        ? "packcontrol.update.hide_details"
                        : "packcontrol.update.details"
        ));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = contentLeft();
        int top = contentTop();
        int width = contentWidth();

        graphics.drawCenteredString(font, title, left + width / 2, top + 7, TEXT);
        graphics.drawCenteredString(
                font,
                Component.translatable("packcontrol.update.subtitle"),
                left + width / 2,
                top + 22,
                MUTED
        );

        int versionY = top + 42;
        versionPanel.render(graphics, font, model, left, versionY, width, 86);
        int planY = versionY + 96;
        int planHeight = contentHeight() - 96 - 42 - 44;
        if (showAdvancedDetails) {
            detailsPanel.render(graphics, font, model, left, planY, width, planHeight);
        } else {
            planPanel.render(graphics, font, model, left, planY, width, planHeight);
        }
        drawLocalChangeWarning(graphics, left, top + contentHeight() - 49, width);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawLocalChangeWarning(GuiGraphics graphics, int x, int y, int width) {
        PackUpdateSummary summary = model.summary();
        if (summary == null || summary.locallyModified().isEmpty()) {
            return;
        }
        graphics.fill(x, y, x + width, y + 15, 0xCC3A2D12);
        graphics.drawString(
                font,
                "Warning: " + summary.locallyModified().size()
                        + " managed file(s) were changed locally. Review details before downloading.",
                x + 8,
                y + 3,
                model.stage() == PackUpdateViewModel.Stage.ERROR ? ERROR : WARNING,
                false
        );
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        graphics.fill(0, 6, width, 9, 0xFF6F64C9);
    }

    @Override
    public void renderTransparentBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, width, height, BACKGROUND);
    }

    @Override
    protected void renderBlurredBackground(float partialTick) {
    }

    @Override
    public void onClose() {
        coordinator.close();
        minecraft.setScreen(parent);
    }

    private static synchronized GitHubReleaseDiscoveryService releaseService(String apiBase) {
        if (sharedReleaseService == null || !apiBase.equals(sharedApiBase)) {
            try {
                sharedReleaseService = new GitHubReleaseDiscoveryService(
                        URI.create(apiBase),
                        new PackHttpClient(),
                        Clock.systemUTC()
                );
                sharedApiBase = apiBase;
            } catch (IllegalArgumentException exception) {
                sharedReleaseService = new GitHubReleaseDiscoveryService();
                sharedApiBase = "https://api.github.com";
            }
        }
        return sharedReleaseService;
    }

    private int contentWidth() {
        return Math.max(320, Math.min(width - 24, 1000));
    }

    private int contentHeight() {
        return Math.max(300, Math.min(height - 20, 600));
    }

    private int contentLeft() {
        return (width - contentWidth()) / 2;
    }

    private int contentTop() {
        return Math.max(10, (height - contentHeight()) / 2);
    }
}
