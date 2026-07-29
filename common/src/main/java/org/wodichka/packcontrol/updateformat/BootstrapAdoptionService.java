package org.wodichka.packcontrol.updateformat;

import org.wodichka.packcontrol.updateformat.FileHashing.DigestedContent;
import org.wodichka.packcontrol.updateformat.InstalledPackState.ManagedFile;
import org.wodichka.packcontrol.updateformat.PackBootstrapValidator.Issue;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Adopts an imported mrpack without changing any pack content.
 */
public final class BootstrapAdoptionService {
    public static final String BOOTSTRAP_RELATIVE_PATH = ".packcontrol/bootstrap.json";

    private final PackBootstrapValidator validator;

    public BootstrapAdoptionService() {
        this(new PackBootstrapValidator());
    }

    public BootstrapAdoptionService(PackBootstrapValidator validator) {
        this.validator = validator;
    }

    public AdoptionResult adopt(Path instanceRoot) {
        Path root = instanceRoot.toAbsolutePath().normalize();
        Path bootstrapPath = root.resolve(BOOTSTRAP_RELATIVE_PATH);
        if (!Files.isRegularFile(bootstrapPath, LinkOption.NOFOLLOW_LINKS)) {
            return new AdoptionResult(Status.NOT_PRESENT, List.of(), null);
        }

        PackBootstrap bootstrap;
        try (Reader reader = Files.newBufferedReader(bootstrapPath)) {
            bootstrap = PackBootstrapJson.fromJson(reader);
        } catch (IOException | RuntimeException exception) {
            return new AdoptionResult(
                    Status.INVALID_BOOTSTRAP,
                    List.of(new Issue("", "Cannot read bootstrap: " + exception.getMessage())),
                    null
            );
        }

        List<Issue> issues = validator.validate(bootstrap);
        if (!issues.isEmpty()) {
            return new AdoptionResult(Status.INVALID_BOOTSTRAP, issues, null);
        }

        InstalledPackState candidate = bootstrap.toInstalledState();
        InstalledStateStore store = new InstalledStateStore(root);
        Optional<InstalledPackState> existing;
        try {
            existing = store.load();
        } catch (IOException exception) {
            return new AdoptionResult(
                    Status.EXISTING_STATE,
                    List.of(new Issue("", "Existing installed state cannot be read")),
                    null
            );
        }
        if (existing.isPresent()) {
            if (existing.get().equals(candidate)) {
                return new AdoptionResult(Status.ALREADY_ADOPTED, List.of(), existing.get());
            }
            return new AdoptionResult(
                    Status.EXISTING_STATE,
                    List.of(new Issue("", "Existing installed-state.json differs from bootstrap")),
                    existing.get()
            );
        }

        List<Issue> mismatches = verifyFiles(root, bootstrap.managedFiles());
        if (!mismatches.isEmpty()) {
            return new AdoptionResult(Status.FILE_MISMATCH, mismatches, null);
        }

        try {
            if (!store.saveNew(candidate)) {
                Optional<InstalledPackState> raced = store.load();
                if (raced.isPresent() && raced.get().equals(candidate)) {
                    return new AdoptionResult(Status.ALREADY_ADOPTED, List.of(), raced.get());
                }
                return new AdoptionResult(
                        Status.EXISTING_STATE,
                        List.of(new Issue("", "installed-state.json appeared during bootstrap")),
                        raced.orElse(null)
                );
            }
            return new AdoptionResult(Status.ADOPTED, List.of(), candidate);
        } catch (IOException exception) {
            return new AdoptionResult(
                    Status.WRITE_FAILED,
                    List.of(new Issue("", "Cannot create installed-state.json: " + exception.getMessage())),
                    null
            );
        }
    }

    private static List<Issue> verifyFiles(Path root, List<ManagedFile> files) {
        List<Issue> issues = new ArrayList<>();
        for (int index = 0; index < files.size(); index++) {
            ManagedFile expected = files.get(index);
            String pointer = "/managedFiles/" + index;
            Path target = root.resolve(expected.path().replace('/', java.io.File.separatorChar)).normalize();
            if (!target.startsWith(root)
                    || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || hasSymbolicLink(root, target)) {
                issues.add(new Issue(pointer, "Managed file is missing or unsafe: " + expected.path()));
                continue;
            }
            try {
                if (Files.size(target) != expected.size()) {
                    issues.add(new Issue(pointer, "Managed file does not match bootstrap: " + expected.path()));
                    continue;
                }
                DigestedContent actual = FileHashing.inspect(target);
                if (!hashesEqual(actual.hashes(), expected.hashes())) {
                    issues.add(new Issue(pointer, "Managed file does not match bootstrap: " + expected.path()));
                }
            } catch (IOException exception) {
                issues.add(new Issue(pointer, "Cannot inspect managed file: " + expected.path()));
            }
        }
        return List.copyOf(issues);
    }

    private static boolean hasSymbolicLink(Path root, Path target) {
        Path current = root;
        for (Path segment : root.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hashesEqual(
            PackControlManifest.Hashes left,
            PackControlManifest.Hashes right
    ) {
        return left.sha1().equalsIgnoreCase(right.sha1())
                && left.sha256().equalsIgnoreCase(right.sha256())
                && left.sha512().equalsIgnoreCase(right.sha512());
    }

    public enum Status {
        NOT_PRESENT,
        ADOPTED,
        ALREADY_ADOPTED,
        EXISTING_STATE,
        INVALID_BOOTSTRAP,
        FILE_MISMATCH,
        WRITE_FAILED
    }

    public record AdoptionResult(
            Status status,
            List<Issue> issues,
            InstalledPackState installedState
    ) {
        public AdoptionResult {
            issues = List.copyOf(issues);
        }

        public boolean successful() {
            return status == Status.ADOPTED || status == Status.ALREADY_ADOPTED;
        }
    }
}
