package org.wodichka.packcontrol.updateformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wodichka.packcontrol.updateformat.ArtifactDownloader.DownloadResponse;
import org.wodichka.packcontrol.updateformat.PackControlManifest.BuildMetadata;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Environment;
import org.wodichka.packcontrol.updateformat.PackControlManifest.EnvironmentRequirement;
import org.wodichka.packcontrol.updateformat.PackControlManifest.FileEntry;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverrideEntry;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverridesArchive;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.OperationType;
import org.wodichka.packcontrol.updateformat.TransactionalPackInstaller.FileOperations;
import org.wodichka.packcontrol.updateformat.TransactionalPackInstaller.InstallResult;
import org.wodichka.packcontrol.updateformat.TransactionalPackInstaller.NioFileOperations;
import org.wodichka.packcontrol.updateformat.TransactionalPackInstaller.PreparationResult;
import org.wodichka.packcontrol.updateformat.TransactionalPackInstaller.RollbackResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionalPackInstallerTest {
    @TempDir
    Path instanceRoot;

    @Test
    void installsAllContentFromStagingAndWritesState() throws Exception {
        byte[] mod = bytes("verified mod");
        Map<String, byte[]> overrides = new LinkedHashMap<>();
        overrides.put("config/example.toml", bytes("enabled=true"));
        overrides.put("kubejs/server_scripts/example.js", bytes("ServerEvents.loaded(() => {})"));
        PackControlManifest manifest = manifest(
                "1.0.0",
                "release-1",
                Map.of("mods/example.jar", mod),
                overrides,
                List.of()
        );
        FakeDownloader downloads = downloaderFor(manifest, Map.of("mods/example.jar", mod), overrides);

        InstallResult result = installer(downloads).install(manifest);

        assertTrue(result.success(), result::message);
        assertEquals("verified mod", Files.readString(instanceRoot.resolve("mods/example.jar")));
        assertEquals("enabled=true", Files.readString(instanceRoot.resolve("config/example.toml")));
        assertTrue(Files.exists(instanceRoot.resolve("kubejs/server_scripts/example.js")));
        InstalledPackState state = new InstalledStateStore(instanceRoot).load().orElseThrow();
        assertEquals("1.0.0", state.packVersion());
        assertEquals(3, state.managedFiles().size());
        assertEquals(3, result.plan().count(OperationType.ADD));
        assertEquals(0, result.plan().count(OperationType.REPLACE));
    }

    @Test
    void preparesWithoutMutationThenAppliesPreparedContent() throws Exception {
        byte[] mod = bytes("prepared mod");
        PackControlManifest manifest = manifest(
                "1.0.0",
                "release-prepared",
                Map.of("mods/prepared.jar", mod),
                Map.of("config/prepared.toml", bytes("prepared=true")),
                List.of()
        );
        TransactionalPackInstaller installer = installer(downloaderFor(
                manifest,
                Map.of("mods/prepared.jar", mod),
                Map.of("config/prepared.toml", bytes("prepared=true"))
        ));

        PreparationResult prepared = installer.prepare(manifest);

        assertTrue(prepared.success(), prepared::message);
        assertTrue(Files.isDirectory(prepared.stagingDirectory()));
        assertFalse(Files.exists(instanceRoot.resolve("mods/prepared.jar")));
        assertFalse(Files.exists(instanceRoot.resolve("config/prepared.toml")));
        assertTrue(new InstalledStateStore(instanceRoot).load().isEmpty());

        InstallResult applied = installer.applyPreparedUpdate();

        assertTrue(applied.success(), applied::message);
        assertEquals("prepared mod", Files.readString(instanceRoot.resolve("mods/prepared.jar")));
        assertEquals("prepared=true", Files.readString(instanceRoot.resolve("config/prepared.toml")));
        assertEquals("1.0.0", new InstalledStateStore(instanceRoot).load().orElseThrow().packVersion());
        assertFalse(Files.exists(instanceRoot.resolve(".packcontrol/prepared-update.json")));
    }

    @Test
    void revalidatesPreparedContentBeforeApplying() throws Exception {
        byte[] mod = bytes("verified staged mod");
        PackControlManifest manifest = manifest(
                "1.0.0",
                "release-stage-tamper",
                Map.of("mods/staged.jar", mod),
                Map.of(),
                List.of()
        );
        TransactionalPackInstaller installer = installer(downloaderFor(
                manifest,
                Map.of("mods/staged.jar", mod),
                Map.of()
        ));
        PreparationResult prepared = installer.prepare(manifest);
        assertTrue(prepared.success(), prepared::message);
        Files.writeString(prepared.stagingDirectory().resolve("content/mods/staged.jar"), "tampered");

        InstallResult result = installer.applyPreparedUpdate();

        assertFalse(result.success());
        assertFalse(result.rollbackAttempted());
        assertFalse(Files.exists(instanceRoot.resolve("mods/staged.jar")));
        assertTrue(new InstalledStateStore(instanceRoot).load().isEmpty());
    }

    @Test
    void wrongHashFailsBeforeInstanceMutation() throws Exception {
        byte[] expected = bytes("expected");
        byte[] received = bytes("tampered");
        PackControlManifest manifest = manifest(
                "1.0.0",
                "release-hash",
                Map.of("mods/example.jar", expected),
                Map.of(),
                List.of()
        );
        FakeDownloader downloader = downloaderFor(
                manifest,
                Map.of("mods/example.jar", received),
                Map.of()
        );

        InstallResult result = installer(downloader).install(manifest);

        assertFalse(result.success());
        assertFalse(result.rollbackAttempted());
        assertFalse(Files.exists(instanceRoot.resolve("mods/example.jar")));
        assertTrue(new InstalledStateStore(instanceRoot).load().isEmpty());
    }

    @Test
    void interruptedDownloadFailsBeforeInstanceMutation() throws Exception {
        byte[] expected = bytes("complete download");
        PackControlManifest manifest = manifest(
                "1.0.0",
                "release-broken",
                Map.of("mods/example.jar", expected),
                Map.of(),
                List.of()
        );
        String url = fileUrl(manifest, "mods/example.jar");
        FakeDownloader downloader = new FakeDownloader();
        downloader.add(url, 200, expected.length, () -> new BrokenInputStream(expected, 4));

        InstallResult result = installer(downloader).install(manifest);

        assertFalse(result.success());
        assertFalse(result.rollbackAttempted());
        assertFalse(Files.exists(instanceRoot.resolve("mods/example.jar")));
        assertTrue(new InstalledStateStore(instanceRoot).load().isEmpty());
    }

    @Test
    void badHttpStatusAndSizeFailBeforeInstanceMutation() throws Exception {
        byte[] expected = bytes("download");
        PackControlManifest statusManifest = manifest(
                "1.0.0",
                "release-status",
                Map.of("mods/status.jar", expected),
                Map.of(),
                List.of()
        );
        FakeDownloader statusDownloader = new FakeDownloader();
        statusDownloader.add(
                fileUrl(statusManifest, "mods/status.jar"),
                404,
                expected.length,
                () -> new ByteArrayInputStream(expected)
        );

        InstallResult statusResult = installer(statusDownloader).install(statusManifest);

        assertFalse(statusResult.success());
        assertFalse(Files.exists(instanceRoot.resolve("mods/status.jar")));

        PackControlManifest sizeManifest = manifest(
                "1.0.0",
                "release-size",
                Map.of("mods/size.jar", expected),
                Map.of(),
                List.of()
        );
        FakeDownloader sizeDownloader = new FakeDownloader();
        sizeDownloader.add(
                fileUrl(sizeManifest, "mods/size.jar"),
                200,
                expected.length + 1L,
                () -> new ByteArrayInputStream(expected)
        );

        InstallResult sizeResult = installer(sizeDownloader).install(sizeManifest);

        assertFalse(sizeResult.success());
        assertFalse(Files.exists(instanceRoot.resolve("mods/size.jar")));
    }

    @Test
    void writeFailureRollsBackReplacedFileAndState() throws Exception {
        byte[] oldContent = bytes("old version");
        byte[] newContent = bytes("new version");
        PackControlManifest oldManifest = manifest(
                "1.0.0",
                "release-old",
                Map.of("mods/example.jar", oldContent),
                Map.of(),
                List.of()
        );
        FakeDownloader allDownloads = new FakeDownloader();
        addManifestDownloads(allDownloads, oldManifest, Map.of("mods/example.jar", oldContent), Map.of());
        assertTrue(installer(allDownloads).install(oldManifest).success());

        PackControlManifest newManifest = manifest(
                "2.0.0",
                "release-new",
                Map.of("mods/example.jar", newContent),
                Map.of(),
                List.of()
        );
        addManifestDownloads(allDownloads, newManifest, Map.of("mods/example.jar", newContent), Map.of());
        FailFirstReplace operations = new FailFirstReplace();
        TransactionalPackInstaller failingInstaller = new TransactionalPackInstaller(
                instanceRoot,
                new PackUpdatePlanner(),
                allDownloads,
                operations
        );

        InstallResult result = failingInstaller.install(newManifest);

        assertFalse(result.success());
        assertTrue(result.rollbackAttempted());
        assertTrue(result.rollbackSucceeded(), result::message);
        assertEquals("old version", Files.readString(instanceRoot.resolve("mods/example.jar")));
        InstalledPackState state = new InstalledStateStore(instanceRoot).load().orElseThrow();
        assertEquals("1.0.0", state.packVersion());
    }

    @Test
    void manualRollbackRetriesAFailedAutomaticRollback() throws Exception {
        byte[] oldContent = bytes("old version");
        byte[] newContent = bytes("new version");
        PackControlManifest oldManifest = manifest(
                "1.0.0",
                "release-old-recovery",
                Map.of("mods/example.jar", oldContent),
                Map.of(),
                List.of()
        );
        PackControlManifest newManifest = manifest(
                "2.0.0",
                "release-new-recovery",
                Map.of("mods/example.jar", newContent),
                Map.of(),
                List.of()
        );
        FakeDownloader downloads = new FakeDownloader();
        addManifestDownloads(downloads, oldManifest, Map.of("mods/example.jar", oldContent), Map.of());
        addManifestDownloads(downloads, newManifest, Map.of("mods/example.jar", newContent), Map.of());
        assertTrue(installer(downloads).install(oldManifest).success());
        TransactionalPackInstaller recoveringInstaller = new TransactionalPackInstaller(
                instanceRoot,
                new PackUpdatePlanner(),
                downloads,
                new FailTwiceReplace()
        );
        assertTrue(recoveringInstaller.prepare(newManifest).success());

        InstallResult failed = recoveringInstaller.applyPreparedUpdate();

        assertFalse(failed.success());
        assertTrue(failed.rollbackAttempted());
        assertFalse(failed.rollbackSucceeded());

        RollbackResult recovered = recoveringInstaller.rollbackLastUpdate();

        assertTrue(recovered.success(), recovered::message);
        assertEquals("old version", Files.readString(instanceRoot.resolve("mods/example.jar")));
        assertEquals("1.0.0", new InstalledStateStore(instanceRoot).load().orElseThrow().packVersion());
    }

    @Test
    void removesOnlyPreviouslyManagedFiles() throws Exception {
        byte[] oldMod = bytes("old managed mod");
        PackControlManifest oldManifest = manifest(
                "1.0.0",
                "release-old-mod",
                Map.of("mods/old.jar", oldMod),
                Map.of(),
                List.of()
        );
        FakeDownloader downloader = downloaderFor(
                oldManifest,
                Map.of("mods/old.jar", oldMod),
                Map.of()
        );
        assertTrue(installer(downloader).install(oldManifest).success());
        Files.writeString(instanceRoot.resolve("mods/user.jar"), "user owned");

        PackControlManifest newManifest = manifest(
                "2.0.0",
                "release-remove-old",
                Map.of(),
                Map.of(),
                List.of("mods/old.jar", "mods/user.jar")
        );
        addManifestDownloads(downloader, newManifest, Map.of(), Map.of());

        InstallResult result = installer(downloader).install(newManifest);

        assertTrue(result.success(), result::message);
        assertEquals(1, result.plan().count(OperationType.REMOVE));
        assertFalse(Files.exists(instanceRoot.resolve("mods/old.jar")));
        assertEquals("user owned", Files.readString(instanceRoot.resolve("mods/user.jar")));
        assertTrue(result.plan().warnings().stream().anyMatch(issue ->
                issue.code() == PackUpdatePlan.IssueCode.UNMANAGED_REMOVE_IGNORED
        ));
    }

    @Test
    void manuallyRollsBackLastSuccessfulUpdate() throws Exception {
        byte[] first = bytes("first version");
        byte[] second = bytes("second version");
        PackControlManifest firstManifest = manifest(
                "1.0.0",
                "release-first",
                Map.of("mods/example.jar", first),
                Map.of(),
                List.of()
        );
        PackControlManifest secondManifest = manifest(
                "2.0.0",
                "release-second",
                Map.of("mods/example.jar", second),
                Map.of(),
                List.of()
        );
        FakeDownloader downloader = new FakeDownloader();
        addManifestDownloads(downloader, firstManifest, Map.of("mods/example.jar", first), Map.of());
        addManifestDownloads(downloader, secondManifest, Map.of("mods/example.jar", second), Map.of());
        TransactionalPackInstaller installer = installer(downloader);
        assertTrue(installer.install(firstManifest).success());
        assertTrue(installer.install(secondManifest).success());
        assertEquals("second version", Files.readString(instanceRoot.resolve("mods/example.jar")));

        RollbackResult result = installer.rollbackLastUpdate();

        assertTrue(result.success(), result::message);
        assertEquals("first version", Files.readString(instanceRoot.resolve("mods/example.jar")));
        InstalledPackState state = new InstalledStateStore(instanceRoot).load().orElseThrow();
        assertEquals("1.0.0", state.packVersion());
        assertEquals("release-first", state.releaseId());
    }

    private TransactionalPackInstaller installer(ArtifactDownloader downloader) {
        return new TransactionalPackInstaller(
                instanceRoot,
                new PackUpdatePlanner(),
                downloader,
                new NioFileOperations()
        );
    }

    private static PackControlManifest manifest(
            String version,
            String releaseId,
            Map<String, byte[]> files,
            Map<String, byte[]> overrides,
            List<String> removedFiles
    ) throws IOException {
        List<FileEntry> fileEntries = files.entrySet().stream()
                .map(entry -> new FileEntry(
                        entry.getKey(),
                        List.of("https://example.test/" + releaseId + "/" + entry.getKey().replace('/', '-')),
                        hashes(entry.getValue()),
                        (long) entry.getValue().length,
                        true,
                        new Environment(EnvironmentRequirement.REQUIRED, EnvironmentRequirement.REQUIRED)
                ))
                .toList();
        byte[] archive = zip(overrides);
        List<OverrideEntry> overrideEntries = overrides.entrySet().stream()
                .map(entry -> new OverrideEntry(
                        entry.getKey(),
                        hashes(entry.getValue()),
                        (long) entry.getValue().length
                ))
                .toList();
        OverridesArchive overridesArchive = new OverridesArchive(
                "overrides.zip",
                List.of("https://example.test/" + releaseId + "/overrides.zip"),
                hashes(archive),
                (long) archive.length,
                overrideEntries
        );
        return new PackControlManifest(
                1,
                new BuildMetadata(
                        "test-pack",
                        "Test Pack",
                        version,
                        releaseId,
                        "1.21.1",
                        "neoforge",
                        "21.1.233"
                ),
                "0.1.0",
                fileEntries,
                overridesArchive,
                removedFiles
        );
    }

    private static FakeDownloader downloaderFor(
            PackControlManifest manifest,
            Map<String, byte[]> files,
            Map<String, byte[]> overrides
    ) throws IOException {
        FakeDownloader downloader = new FakeDownloader();
        addManifestDownloads(downloader, manifest, files, overrides);
        return downloader;
    }

    private static void addManifestDownloads(
            FakeDownloader downloader,
            PackControlManifest manifest,
            Map<String, byte[]> files,
            Map<String, byte[]> overrides
    ) throws IOException {
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            String url = fileUrl(manifest, entry.getKey());
            byte[] content = entry.getValue();
            downloader.add(
                    url,
                    200,
                    content.length,
                    () -> new ByteArrayInputStream(content)
            );
        }
        byte[] archive = zip(overrides);
        downloader.add(
                manifest.overrides().downloads().getFirst(),
                200,
                archive.length,
                () -> new ByteArrayInputStream(archive)
        );
    }

    private static String fileUrl(PackControlManifest manifest, String path) {
        return manifest.files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElseThrow()
                .downloads()
                .getFirst();
    }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0);
                output.putNextEntry(zipEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Hashes hashes(byte[] content) {
        return new Hashes(
                digest("SHA-1", content),
                digest("SHA-256", content),
                digest("SHA-512", content)
        );
    }

    private static String digest(String algorithm, byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class FakeDownloader implements ArtifactDownloader {
        private final Map<String, ResponseSpec> responses = new HashMap<>();

        void add(String url, int status, long contentLength, Supplier<InputStream> body) {
            responses.put(url, new ResponseSpec(status, contentLength, body));
        }

        @Override
        public DownloadResponse open(URI uri) throws IOException {
            ResponseSpec response = Optional.ofNullable(responses.get(uri.toString()))
                    .orElseThrow(() -> new IOException("No fake response for " + uri));
            return new DownloadResponse(
                    response.status,
                    response.contentLength,
                    response.body.get()
            );
        }
    }

    private record ResponseSpec(
            int status,
            long contentLength,
            Supplier<InputStream> body
    ) {
    }

    private static final class BrokenInputStream extends InputStream {
        private final byte[] content;
        private final int failAfter;
        private int position;

        private BrokenInputStream(byte[] content, int failAfter) {
            this.content = content;
            this.failAfter = failAfter;
        }

        @Override
        public int read() throws IOException {
            if (position >= failAfter) {
                throw new IOException("connection reset");
            }
            return position >= content.length ? -1 : content[position++] & 0xff;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (position >= failAfter) {
                throw new IOException("connection reset");
            }
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(length, Math.min(content.length - position, failAfter - position));
            System.arraycopy(content, position, buffer, offset, count);
            position += count;
            return count;
        }
    }

    private static final class FailFirstReplace implements FileOperations {
        private final NioFileOperations delegate = new NioFileOperations();
        private boolean failed;

        @Override
        public void replace(Path source, Path target) throws IOException {
            if (!failed) {
                failed = true;
                throw new IOException("target is not writable");
            }
            delegate.replace(source, target);
        }

        @Override
        public void delete(Path target) throws IOException {
            delegate.delete(target);
        }
    }

    private static final class FailTwiceReplace implements FileOperations {
        private final NioFileOperations delegate = new NioFileOperations();
        private int failuresRemaining = 2;

        @Override
        public void replace(Path source, Path target) throws IOException {
            if (failuresRemaining-- > 0) {
                throw new IOException("target is temporarily not writable");
            }
            delegate.replace(source, target);
        }

        @Override
        public void delete(Path target) throws IOException {
            delegate.delete(target);
        }
    }
}
