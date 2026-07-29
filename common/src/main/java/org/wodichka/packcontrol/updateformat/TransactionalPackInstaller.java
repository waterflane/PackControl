package org.wodichka.packcontrol.updateformat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.wodichka.packcontrol.updateformat.ArtifactDownloader.DownloadResponse;
import org.wodichka.packcontrol.updateformat.FileHashing.DigestedContent;
import org.wodichka.packcontrol.updateformat.InstalledPackState.ManagedFile;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverrideEntry;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.ContentKind;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.Operation;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.OperationType;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Transactional installer for validated PackControl manifests.
 *
 * <p>All remote content is downloaded and verified in staging before any
 * managed instance file is changed. An active journal is durable before apply,
 * so a later invocation can recover an interrupted transaction.</p>
 */
public final class TransactionalPackInstaller {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path instanceRoot;
    private final Path controlDirectory;
    private final Path stagingDirectory;
    private final Path backupsDirectory;
    private final Path activeJournalPath;
    private final Path lastJournalPath;
    private final Path preparedUpdatePath;
    private final Path lockPath;
    private final InstalledStateStore stateStore;
    private final PackUpdatePlanner planner;
    private final ArtifactDownloader downloader;
    private final FileOperations fileOperations;

    public TransactionalPackInstaller(Path instanceRoot) {
        this(
                instanceRoot,
                new PackUpdatePlanner(),
                new HttpArtifactDownloader(),
                new NioFileOperations()
        );
    }

    public TransactionalPackInstaller(
            Path instanceRoot,
            PackUpdatePlanner planner,
            ArtifactDownloader downloader,
            FileOperations fileOperations
    ) {
        this.instanceRoot = instanceRoot.toAbsolutePath().normalize();
        this.controlDirectory = this.instanceRoot.resolve(".packcontrol");
        this.stagingDirectory = controlDirectory.resolve("staging");
        this.backupsDirectory = controlDirectory.resolve("backups");
        this.activeJournalPath = controlDirectory.resolve("active-transaction.json");
        this.lastJournalPath = controlDirectory.resolve("last-successful-transaction.json");
        this.preparedUpdatePath = controlDirectory.resolve("prepared-update.json");
        this.lockPath = controlDirectory.resolve("update.lock");
        this.stateStore = new InstalledStateStore(this.instanceRoot);
        this.planner = planner;
        this.downloader = downloader;
        this.fileOperations = fileOperations;
    }

    public InstallResult install(PackControlManifest manifest) {
        try {
            Files.createDirectories(controlDirectory);
            try (UpdateLock ignored = acquireLock()) {
                recoverIncompleteTransaction();
                Optional<InstalledPackState> previousState = stateStore.load();
                PackUpdatePlan plan = planner.plan(manifest, previousState, instanceRoot);
                if (plan.isBlocked()) {
                    return InstallResult.blocked(plan);
                }
                return executeInstall(manifest, previousState, plan);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return InstallResult.failed("Installation interrupted", null, false, false, null);
        } catch (IOException | RuntimeException exception) {
            return InstallResult.failed("Installation failed: " + exception.getMessage(), null, false, false, null);
        }
    }

    /**
     * Downloads and verifies the complete update without modifying managed
     * instance files. A later {@link #applyPreparedUpdate()} call revalidates
     * both the plan and staged content before applying it transactionally.
     */
    public PreparationResult prepare(PackControlManifest manifest) {
        try {
            Files.createDirectories(controlDirectory);
            try (UpdateLock ignored = acquireLock()) {
                recoverIncompleteTransaction();
                discardPreparedUpdate();
                Optional<InstalledPackState> previousState = stateStore.load();
                PackUpdatePlan plan = planner.plan(manifest, previousState, instanceRoot);
                if (plan.isBlocked()) {
                    return PreparationResult.blocked(plan);
                }
                String transactionId = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
                Path staging = stagingDirectory.resolve(transactionId);
                Path content = staging.resolve("content");
                Files.createDirectories(content);
                try {
                    stageDownloads(manifest, plan, staging, content);
                    verifyPreparedContent(plan, content);
                    writePreparedUpdate(new PreparedUpdate(
                            transactionId,
                            Instant.now().toString(),
                            manifest
                    ));
                    return new PreparationResult(
                            true,
                            false,
                            "Update " + manifest.metadata().version() + " is ready to apply",
                            plan,
                            staging
                    );
                } catch (IOException | InterruptedException | RuntimeException exception) {
                    deleteTree(staging);
                    if (exception instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    return PreparationResult.failed("Update preparation failed: " + exception.getMessage(), plan);
                }
            }
        } catch (IOException | RuntimeException exception) {
            return PreparationResult.failed("Update preparation failed: " + exception.getMessage(), null);
        }
    }

    public InstallResult applyPreparedUpdate() {
        try {
            Files.createDirectories(controlDirectory);
            try (UpdateLock ignored = acquireLock()) {
                recoverIncompleteTransaction();
                if (Files.notExists(preparedUpdatePath)) {
                    return InstallResult.failed("No prepared update is available", null, false, false, null);
                }
                PreparedUpdate prepared = readPreparedUpdate();
                Optional<InstalledPackState> previousState = stateStore.load();
                PackUpdatePlan plan = planner.plan(prepared.manifest, previousState, instanceRoot);
                if (plan.isBlocked()) {
                    return InstallResult.blocked(plan);
                }
                Path staging = stagingDirectory.resolve(prepared.transactionId);
                Path content = staging.resolve("content");
                verifyPreparedContent(plan, content);
                return applyPrepared(prepared, previousState, plan, staging, content);
            }
        } catch (IOException | RuntimeException exception) {
            return InstallResult.failed(
                    "Prepared update failed: " + exception.getMessage(),
                    null,
                    false,
                    false,
                    null
            );
        }
    }

    public RollbackResult rollbackLastUpdate() {
        try {
            Files.createDirectories(controlDirectory);
            try (UpdateLock ignored = acquireLock()) {
                TransactionJournal incomplete = Files.exists(activeJournalPath)
                        ? readJournal(activeJournalPath)
                        : null;
                recoverIncompleteTransaction();
                if (incomplete != null) {
                    return new RollbackResult(
                            true,
                            "Restored files from the failed update",
                            backupDirectory(incomplete.transactionId)
                    );
                }
                if (Files.notExists(lastJournalPath)) {
                    return new RollbackResult(false, "No successful update is available for rollback", null);
                }
                TransactionJournal journal = readJournal(lastJournalPath);
                Optional<InstalledPackState> current = stateStore.load();
                if (current.isEmpty() || !journal.committedReleaseId.equals(current.get().releaseId())) {
                    return new RollbackResult(
                            false,
                            "Installed state no longer matches the last successful update",
                            backupDirectory(journal.transactionId)
                    );
                }
                rollback(journal);
                Files.deleteIfExists(lastJournalPath);
                return new RollbackResult(
                        true,
                        "Rolled back update " + journal.committedReleaseId,
                        backupDirectory(journal.transactionId)
                );
            }
        } catch (IOException | RuntimeException exception) {
            return new RollbackResult(false, "Rollback failed: " + exception.getMessage(), null);
        }
    }

    private InstallResult executeInstall(
            PackControlManifest manifest,
            Optional<InstalledPackState> previousState,
            PackUpdatePlan plan
    ) throws IOException, InterruptedException {
        String transactionId = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
        Path staging = stagingDirectory.resolve(transactionId);
        Path content = staging.resolve("content");
        Path backup = backupDirectory(transactionId);
        Files.createDirectories(content);

        TransactionJournal journal = null;
        boolean applyStarted = false;
        try {
            stageDownloads(manifest, plan, staging, content);
            journal = createBackup(transactionId, previousState, plan, backup);
            writeJournal(activeJournalPath, journal);
            applyStarted = true;

            apply(plan, content);
            verifyApplied(plan);

            InstalledPackState newState = createInstalledState(manifest, plan);
            stateStore.save(newState);

            TransactionJournal committed = journal.withCommittedReleaseId(manifest.metadata().releaseId());
            writeJournal(lastJournalPath, committed);
            Files.deleteIfExists(activeJournalPath);
            deleteTree(staging);
            return new InstallResult(
                    true,
                    false,
                    "Installed " + manifest.metadata().packId() + " " + manifest.metadata().version(),
                    plan,
                    backup,
                    false,
                    false
            );
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            boolean rollbackAttempted = applyStarted && journal != null;
            boolean rollbackSucceeded = false;
            String rollbackMessage = "";
            if (rollbackAttempted) {
                try {
                    rollback(journal);
                    Files.deleteIfExists(activeJournalPath);
                    deleteLastJournalIfTransactionMatches(journal.transactionId);
                    rollbackSucceeded = true;
                } catch (IOException | RuntimeException rollbackException) {
                    rollbackMessage = "; rollback failed: " + rollbackException.getMessage();
                }
            }
            try {
                deleteTree(staging);
            } catch (IOException ignored) {
                // The original failure is more useful; stale staging is safe.
            }
            return InstallResult.failed(
                    "Installation failed: " + exception.getMessage() + rollbackMessage,
                    plan,
                    rollbackAttempted,
                    rollbackSucceeded,
                    backup
            );
        }
    }

    private InstallResult applyPrepared(
            PreparedUpdate prepared,
            Optional<InstalledPackState> previousState,
            PackUpdatePlan plan,
            Path staging,
            Path content
    ) {
        Path backup = backupDirectory(prepared.transactionId);
        TransactionJournal journal = null;
        boolean applyStarted = false;
        try {
            journal = createBackup(prepared.transactionId, previousState, plan, backup);
            writeJournal(activeJournalPath, journal);
            applyStarted = true;
            apply(plan, content);
            verifyApplied(plan);

            InstalledPackState newState = createInstalledState(prepared.manifest, plan);
            stateStore.save(newState);
            TransactionJournal committed =
                    journal.withCommittedReleaseId(prepared.manifest.metadata().releaseId());
            writeJournal(lastJournalPath, committed);
            Files.deleteIfExists(activeJournalPath);
            Files.deleteIfExists(preparedUpdatePath);
            deleteTree(staging);
            return new InstallResult(
                    true,
                    false,
                    "Installed " + prepared.manifest.metadata().packId()
                            + " " + prepared.manifest.metadata().version(),
                    plan,
                    backup,
                    false,
                    false
            );
        } catch (IOException | RuntimeException exception) {
            boolean rollbackAttempted = applyStarted && journal != null;
            boolean rollbackSucceeded = false;
            String rollbackMessage = "";
            if (rollbackAttempted) {
                try {
                    rollback(journal);
                    Files.deleteIfExists(activeJournalPath);
                    deleteLastJournalIfTransactionMatches(journal.transactionId);
                    rollbackSucceeded = true;
                } catch (IOException | RuntimeException rollbackException) {
                    rollbackMessage = "; rollback failed: " + rollbackException.getMessage();
                }
            }
            if (rollbackSucceeded || !rollbackAttempted) {
                try {
                    Files.deleteIfExists(preparedUpdatePath);
                    deleteTree(staging);
                } catch (IOException ignored) {
                    // Preserve the apply failure as the primary result.
                }
            }
            return InstallResult.failed(
                    "Prepared update failed: " + exception.getMessage() + rollbackMessage,
                    plan,
                    rollbackAttempted,
                    rollbackSucceeded,
                    backup
            );
        }
    }

    private static void verifyPreparedContent(PackUpdatePlan plan, Path content) throws IOException {
        for (Operation operation : plan.operations()) {
            if (operation.type() != OperationType.ADD && operation.type() != OperationType.REPLACE) {
                continue;
            }
            Path staged = resolveUnder(content, operation.path());
            if (!Files.isRegularFile(staged) || Files.isSymbolicLink(staged)) {
                throw new IOException("Prepared file is missing: " + operation.path());
            }
            DigestedContent digest = FileHashing.inspect(staged);
            if (digest.size() != operation.size() || !hashesEqual(operation.hashes(), digest.hashes())) {
                throw new IOException("Prepared file verification failed: " + operation.path());
            }
        }
    }

    private void discardPreparedUpdate() throws IOException {
        if (Files.notExists(preparedUpdatePath)) {
            return;
        }
        PreparedUpdate previous = readPreparedUpdate();
        deleteTree(stagingDirectory.resolve(previous.transactionId));
        Files.deleteIfExists(preparedUpdatePath);
    }

    private void writePreparedUpdate(PreparedUpdate prepared) throws IOException {
        Files.createDirectories(preparedUpdatePath.getParent());
        Path temporary = preparedUpdatePath.resolveSibling(preparedUpdatePath.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary)) {
            GSON.toJson(prepared, writer);
        }
        InstalledStateStore.moveReplacing(temporary, preparedUpdatePath);
    }

    private PreparedUpdate readPreparedUpdate() throws IOException {
        try (Reader reader = Files.newBufferedReader(preparedUpdatePath)) {
            PreparedUpdate prepared = GSON.fromJson(reader, PreparedUpdate.class);
            if (prepared == null || prepared.transactionId == null || prepared.manifest == null) {
                throw new IOException("Invalid prepared-update.json");
            }
            return prepared;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid prepared-update.json", exception);
        }
    }

    private void stageDownloads(
            PackControlManifest manifest,
            PackUpdatePlan plan,
            Path staging,
            Path content
    ) throws IOException, InterruptedException {
        for (Operation operation : plan.operations()) {
            if ((operation.type() == OperationType.ADD || operation.type() == OperationType.REPLACE)
                    && operation.contentKind() == ContentKind.DOWNLOAD) {
                Path target = resolveUnder(content, operation.path());
                downloadVerified(
                        operation.downloads(),
                        operation.hashes(),
                        operation.size(),
                        target
                );
            }
        }

        boolean needsOverrides = plan.operations().stream().anyMatch(operation ->
                (operation.type() == OperationType.ADD || operation.type() == OperationType.REPLACE)
                        && operation.contentKind() == ContentKind.OVERRIDE
        );
        if (!needsOverrides) {
            return;
        }

        Path archive = staging.resolve("overrides.zip");
        downloadVerified(
                manifest.overrides().downloads(),
                manifest.overrides().hashes(),
                manifest.overrides().size(),
                archive
        );
        extractAndVerifyOverrides(archive, content, manifest.overrides().entries());
    }

    private void downloadVerified(
            List<String> downloads,
            Hashes expectedHashes,
            long expectedSize,
            Path target
    ) throws IOException, InterruptedException {
        List<String> failures = new ArrayList<>();
        for (String value : downloads) {
            Files.deleteIfExists(target);
            URI uri = URI.create(value);
            try (DownloadResponse response = downloader.open(uri)) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    failures.add(uri + " returned HTTP " + response.statusCode());
                    continue;
                }
                if (response.contentLength() >= 0 && response.contentLength() != expectedSize) {
                    failures.add(
                            uri + " declared Content-Length " + response.contentLength()
                                    + ", expected " + expectedSize
                    );
                    continue;
                }
                if (response.body() == null) {
                    failures.add(uri + " returned an empty response body");
                    continue;
                }

                DigestedContent downloaded = FileHashing.copyAndHash(
                        response.body(),
                        target,
                        expectedSize
                );
                if (downloaded.size() != expectedSize) {
                    failures.add(uri + " downloaded " + downloaded.size() + " bytes, expected " + expectedSize);
                    continue;
                }
                if (!hashesEqual(expectedHashes, downloaded.hashes())) {
                    failures.add(uri + " failed hash verification");
                    continue;
                }
                return;
            } catch (IOException exception) {
                failures.add(uri + " failed: " + exception.getMessage());
            }
        }
        Files.deleteIfExists(target);
        throw new IOException("All download sources failed: " + String.join("; ", failures));
    }

    private void extractAndVerifyOverrides(
            Path archive,
            Path content,
            List<OverrideEntry> entries
    ) throws IOException {
        Map<String, OverrideEntry> expected = new LinkedHashMap<>();
        for (OverrideEntry entry : entries) {
            expected.put(entry.path(), entry);
        }
        Set<String> seen = new HashSet<>();

        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry zipEntry;
            while ((zipEntry = input.getNextEntry()) != null) {
                String name = zipEntry.getName();
                if (zipEntry.isDirectory()) {
                    String directory = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                    boolean knownDirectory = expected.keySet().stream()
                            .anyMatch(path -> path.startsWith(directory + "/"));
                    if (!knownDirectory) {
                        throw new IOException("Unexpected override directory: " + name);
                    }
                    input.closeEntry();
                    continue;
                }

                OverrideEntry manifestEntry = expected.get(name);
                if (manifestEntry == null) {
                    throw new IOException("Unexpected override entry: " + name);
                }
                if (!seen.add(name)) {
                    throw new IOException("Duplicate override entry: " + name);
                }

                Path target = resolveUnder(content, name);
                DigestedContent extracted = FileHashing.copyAndHash(
                        input,
                        target,
                        manifestEntry.size()
                );
                if (extracted.size() != manifestEntry.size()) {
                    throw new IOException("Override size mismatch: " + name);
                }
                if (!hashesEqual(manifestEntry.hashes(), extracted.hashes())) {
                    throw new IOException("Override hash mismatch: " + name);
                }
                input.closeEntry();
            }
        }

        if (seen.size() != expected.size()) {
            Set<String> missing = new HashSet<>(expected.keySet());
            missing.removeAll(seen);
            throw new IOException("Missing override entries: " + missing);
        }
    }

    private TransactionJournal createBackup(
            String transactionId,
            Optional<InstalledPackState> previousState,
            PackUpdatePlan plan,
            Path backup
    ) throws IOException {
        Path backupFiles = backup.resolve("files");
        Files.createDirectories(backupFiles);
        List<BackupEntry> entries = new ArrayList<>();

        for (Operation operation : plan.operations()) {
            if (operation.type() == OperationType.KEEP) {
                continue;
            }
            Path target = resolveInstance(operation.path());
            ensureSafeTarget(target);
            boolean existed = Files.exists(target);
            if (existed) {
                if (!Files.isRegularFile(target) || Files.isSymbolicLink(target)) {
                    throw new IOException("Cannot back up non-regular path: " + operation.path());
                }
                Path backupTarget = resolveUnder(backupFiles, operation.path());
                Files.createDirectories(backupTarget.getParent());
                Files.copy(
                        target,
                        backupTarget,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
                if (!FileHashing.sha256(target).equalsIgnoreCase(FileHashing.sha256(backupTarget))) {
                    throw new IOException("Backup verification failed: " + operation.path());
                }
            }
            entries.add(new BackupEntry(operation.path(), existed));
        }

        boolean previousStateExisted = previousState.isPresent();
        if (previousStateExisted) {
            Files.copy(
                    stateStore.statePath(),
                    backup.resolve("installed-state.json"),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
            );
        }
        return new TransactionJournal(transactionId, entries, previousStateExisted, "");
    }

    private void apply(PackUpdatePlan plan, Path content) throws IOException {
        for (Operation operation : plan.operations()) {
            Path target = resolveInstance(operation.path());
            ensureSafeTarget(target);
            switch (operation.type()) {
                case ADD, REPLACE -> fileOperations.replace(
                        resolveUnder(content, operation.path()),
                        target
                );
                case REMOVE -> fileOperations.delete(target);
                case KEEP -> {
                    // No mutation.
                }
            }
        }
    }

    private void verifyApplied(PackUpdatePlan plan) throws IOException {
        for (Operation operation : plan.operations()) {
            Path target = resolveInstance(operation.path());
            if (operation.type() == OperationType.REMOVE) {
                if (Files.exists(target)) {
                    throw new IOException("Removed file still exists: " + operation.path());
                }
            } else {
                if (!Files.isRegularFile(target)
                        || !operation.hashes().sha256().equalsIgnoreCase(FileHashing.sha256(target))) {
                    throw new IOException("Post-install verification failed: " + operation.path());
                }
            }
        }
    }

    private InstalledPackState createInstalledState(
            PackControlManifest manifest,
            PackUpdatePlan plan
    ) throws IOException {
        List<ManagedFile> managed = new ArrayList<>();
        for (Operation operation : plan.operations()) {
            if (operation.type() != OperationType.REMOVE) {
                managed.add(new ManagedFile(
                        operation.path(),
                        operation.hashes(),
                        operation.size()
                ));
            }
        }
        String manifestHash = sha256(ManifestJson.toJson(manifest));
        return new InstalledPackState(
                InstalledPackState.CURRENT_SCHEMA_VERSION,
                manifest.metadata().packId(),
                manifest.metadata().version(),
                manifest.metadata().releaseId(),
                manifestHash,
                managed
        );
    }

    private void rollback(TransactionJournal journal) throws IOException {
        Path backup = backupDirectory(journal.transactionId);
        List<BackupEntry> reversed = new ArrayList<>(journal.entries);
        for (int index = reversed.size() - 1; index >= 0; index--) {
            BackupEntry entry = reversed.get(index);
            Path target = resolveInstance(entry.path);
            ensureSafeTarget(target);
            if (entry.existedBefore) {
                Path backupFile = resolveUnder(backup.resolve("files"), entry.path);
                if (!Files.isRegularFile(backupFile)) {
                    throw new IOException("Backup file is missing: " + entry.path);
                }
                fileOperations.replace(backupFile, target);
            } else {
                fileOperations.delete(target);
            }
        }

        if (journal.previousStateExisted) {
            Path previousState = backup.resolve("installed-state.json");
            if (!Files.isRegularFile(previousState)) {
                throw new IOException("Previous installed-state backup is missing");
            }
            Path temporary = stateStore.statePath().resolveSibling("installed-state.rollback.tmp");
            Files.copy(previousState, temporary, StandardCopyOption.REPLACE_EXISTING);
            InstalledStateStore.moveReplacing(temporary, stateStore.statePath());
        } else {
            stateStore.delete();
        }
    }

    private void recoverIncompleteTransaction() throws IOException {
        if (Files.notExists(activeJournalPath)) {
            return;
        }
        TransactionJournal journal = readJournal(activeJournalPath);
        rollback(journal);
        if (Files.exists(lastJournalPath)) {
            deleteLastJournalIfTransactionMatches(journal.transactionId);
        }
        Files.deleteIfExists(activeJournalPath);
    }

    private void deleteLastJournalIfTransactionMatches(String transactionId) throws IOException {
        if (Files.notExists(lastJournalPath)) {
            return;
        }
        TransactionJournal last = readJournal(lastJournalPath);
        if (last.transactionId.equals(transactionId)) {
            Files.deleteIfExists(lastJournalPath);
        }
    }

    private Path resolveInstance(String relative) throws IOException {
        Path target = resolveUnder(instanceRoot, relative);
        if (target.startsWith(controlDirectory)) {
            throw new IOException("Managed path may not target .packcontrol: " + relative);
        }
        return target;
    }

    private static Path resolveUnder(Path root, String relative) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(relative.replace('/', java.io.File.separatorChar))
                .toAbsolutePath()
                .normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IOException("Path escapes root: " + relative);
        }
        return target;
    }

    private void ensureSafeTarget(Path target) throws IOException {
        Path current = instanceRoot;
        Path relative = instanceRoot.relativize(target);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current) && Files.isSymbolicLink(current)) {
                throw new IOException("Symbolic links are forbidden in managed paths: " + relative);
            }
        }
    }

    private Path backupDirectory(String transactionId) {
        return backupsDirectory.resolve(transactionId);
    }

    private UpdateLock acquireLock() throws IOException {
        FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        );
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new IOException("Another PackControl update is running");
            }
            return new UpdateLock(channel, lock);
        } catch (OverlappingFileLockException exception) {
            channel.close();
            throw new IOException("Another PackControl update is running", exception);
        }
    }

    private static boolean hashesEqual(Hashes expected, Hashes actual) {
        return expected.sha1().equalsIgnoreCase(actual.sha1())
                && expected.sha256().equalsIgnoreCase(actual.sha256())
                && expected.sha512().equalsIgnoreCase(actual.sha512());
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void writeJournal(Path path, TransactionJournal journal) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary)) {
            GSON.toJson(journal, writer);
        }
        InstalledStateStore.moveReplacing(temporary, path);
    }

    private static TransactionJournal readJournal(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            TransactionJournal journal = GSON.fromJson(reader, TransactionJournal.class);
            if (journal == null || journal.transactionId == null || journal.entries == null) {
                throw new IOException("Invalid transaction journal: " + path);
            }
            return journal;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid transaction journal: " + path, exception);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            List<Path> ordered = paths.sorted((left, right) -> right.compareTo(left)).toList();
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
        }
    }

    public interface FileOperations {
        void replace(Path source, Path target) throws IOException;

        void delete(Path target) throws IOException;
    }

    public static final class NioFileOperations implements FileOperations {
        @Override
        public void replace(Path source, Path target) throws IOException {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".packcontrol.tmp");
            try {
                Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(
                            temporary,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        @Override
        public void delete(Path target) throws IOException {
            Files.deleteIfExists(target);
        }
    }

    public record InstallResult(
            boolean success,
            boolean blocked,
            String message,
            PackUpdatePlan plan,
            Path backupDirectory,
            boolean rollbackAttempted,
            boolean rollbackSucceeded
    ) {
        private static InstallResult blocked(PackUpdatePlan plan) {
            return new InstallResult(
                    false,
                    true,
                    "Update plan is blocked",
                    plan,
                    null,
                    false,
                    false
            );
        }

        private static InstallResult failed(
                String message,
                PackUpdatePlan plan,
                boolean rollbackAttempted,
                boolean rollbackSucceeded,
                Path backupDirectory
        ) {
            return new InstallResult(
                    false,
                    false,
                    message,
                    plan,
                    backupDirectory,
                    rollbackAttempted,
                    rollbackSucceeded
            );
        }
    }

    public record RollbackResult(
            boolean success,
            String message,
            Path backupDirectory
    ) {
    }

    public record PreparationResult(
            boolean success,
            boolean blocked,
            String message,
            PackUpdatePlan plan,
            Path stagingDirectory
    ) {
        private static PreparationResult blocked(PackUpdatePlan plan) {
            return new PreparationResult(false, true, "Update plan is blocked", plan, null);
        }

        private static PreparationResult failed(String message, PackUpdatePlan plan) {
            return new PreparationResult(false, false, message, plan, null);
        }
    }

    private record BackupEntry(
            String path,
            boolean existedBefore
    ) {
    }

    private record TransactionJournal(
            String transactionId,
            List<BackupEntry> entries,
            boolean previousStateExisted,
            String committedReleaseId
    ) {
        private TransactionJournal withCommittedReleaseId(String releaseId) {
            return new TransactionJournal(transactionId, entries, previousStateExisted, releaseId);
        }
    }

    private record PreparedUpdate(
            String transactionId,
            String preparedAt,
            PackControlManifest manifest
    ) {
    }

    private record UpdateLock(
            FileChannel channel,
            FileLock lock
    ) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
