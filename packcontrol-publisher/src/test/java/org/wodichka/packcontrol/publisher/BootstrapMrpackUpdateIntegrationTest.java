package org.wodichka.packcontrol.publisher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wodichka.packcontrol.client.update.PackUpdateSummary;
import org.wodichka.packcontrol.updateformat.ArtifactDownloader;
import org.wodichka.packcontrol.updateformat.BootstrapAdoptionService;
import org.wodichka.packcontrol.updateformat.BootstrapAdoptionService.Status;
import org.wodichka.packcontrol.updateformat.CancellationToken;
import org.wodichka.packcontrol.updateformat.InstalledPackState;
import org.wodichka.packcontrol.updateformat.InstalledStateStore;
import org.wodichka.packcontrol.updateformat.ManifestJson;
import org.wodichka.packcontrol.updateformat.PackControlManifest;
import org.wodichka.packcontrol.updateformat.PackFileCandidate;
import org.wodichka.packcontrol.updateformat.PackFileRequest;
import org.wodichka.packcontrol.updateformat.PackFileResolution;
import org.wodichka.packcontrol.updateformat.PackFileSource;
import org.wodichka.packcontrol.updateformat.PackFileSourceRegistry;
import org.wodichka.packcontrol.updateformat.PackSourceReference;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan;
import org.wodichka.packcontrol.updateformat.PackUpdatePlan.OperationType;
import org.wodichka.packcontrol.updateformat.PackUpdatePlanner;
import org.wodichka.packcontrol.updateformat.TransactionalPackInstaller;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapMrpackUpdateIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void importedMrpackIsAdoptedAndCompletesFirstUpdate() throws Exception {
        Path release100 = copyFixture("1.0.0");
        Path release101 = copyFixture("1.0.1");
        PackControlPublisher publisher = new PackControlPublisher(
                new PackFileSourceRegistry(List.of(new FixtureSource()))
        );
        Path output100 = temporary.resolve("release-1.0.0");
        Path output101 = temporary.resolve("release-1.0.1");
        PackControlPublisher.BuildResult build100 = publisher.build(
                release100,
                output100,
                PublisherConfig.read(release100.resolve(PackControlPublisher.CONFIG_FILE)),
                CancellationToken.none()
        );
        PackControlPublisher.BuildResult build101 = publisher.build(
                release101,
                output101,
                PublisherConfig.read(release101.resolve(PackControlPublisher.CONFIG_FILE)),
                CancellationToken.none()
        );

        Path imported = temporary.resolve("imported-instance");
        extractMrpackOverrides(build100.mrpack(), imported);
        copyMods(release100, imported);

        JsonObject packConfig = JsonParser.parseString(
                Files.readString(imported.resolve(PackControlPublisher.PACK_CONFIG_FILE))
        ).getAsJsonObject();
        assertEquals(1, packConfig.get("schemaVersion").getAsInt());
        assertEquals(
                "example/bootstrap-fixture",
                packConfig.get("targetGithubRepository").getAsString()
        );
        assertEquals("stable", packConfig.get("updateChannel").getAsString());
        assertTrue(Files.isRegularFile(imported.resolve(BootstrapAdoptionService.BOOTSTRAP_RELATIVE_PATH)));

        PackControlManifest manifest100 = readManifest(build100.manifest());
        assertTrue(manifest100.overrides().entries().stream().noneMatch(entry ->
                entry.path().equals(PackControlPublisher.PACK_CONFIG_FILE)
                        || entry.path().startsWith(".packcontrol/")
        ));
        try (ZipFile overrides = new ZipFile(build100.overrides().toFile())) {
            assertTrue(overrides.getEntry(PackControlPublisher.PACK_CONFIG_FILE) == null);
            assertTrue(overrides.getEntry(PackControlPublisher.BOOTSTRAP_FILE) == null);
        }

        BootstrapAdoptionService bootstrap = new BootstrapAdoptionService();
        assertEquals(Status.ADOPTED, bootstrap.adopt(imported).status());
        InstalledStateStore stateStore = new InstalledStateStore(imported);
        InstalledPackState installed100 = stateStore.load().orElseThrow();
        assertEquals("bootstrap-fixture", installed100.packId());
        assertEquals("1.0.0", installed100.packVersion());
        assertEquals("bootstrap-fixture-1.0.0", installed100.releaseId());
        assertEquals(5, installed100.managedFiles().size());
        byte[] firstState = Files.readAllBytes(stateStore.statePath());
        assertEquals(Status.ALREADY_ADOPTED, bootstrap.adopt(imported).status());
        assertTrue(java.util.Arrays.equals(firstState, Files.readAllBytes(stateStore.statePath())));

        Files.writeString(imported.resolve("config/changed.toml"), "user-local-change");
        PackControlManifest manifest101 = readManifest(build101.manifest());
        PackUpdatePlan plan = new PackUpdatePlanner().plan(
                manifest101,
                stateStore.load(),
                imported
        );
        PackUpdateSummary summary = PackUpdateSummary.create(
                manifest101,
                plan,
                stateStore.load(),
                imported
        );

        assertFalse(plan.isBlocked());
        assertEquals(1, plan.count(OperationType.ADD));
        assertEquals(2, plan.count(OperationType.REPLACE));
        assertEquals(2, plan.count(OperationType.KEEP));
        assertEquals(1, plan.count(OperationType.REMOVE));
        assertEquals(List.of("config/changed.toml"), summary.locallyModified());

        Map<URI, byte[]> payloads = updatePayloads(manifest101, release101, build101.overrides());
        ArtifactDownloader downloader = uri -> {
            byte[] payload = payloads.get(uri);
            if (payload == null) {
                return new ArtifactDownloader.DownloadResponse(
                        404,
                        0,
                        new ByteArrayInputStream(new byte[0])
                );
            }
            return new ArtifactDownloader.DownloadResponse(
                    200,
                    payload.length,
                    new ByteArrayInputStream(payload)
            );
        };
        TransactionalPackInstaller.InstallResult installed = new TransactionalPackInstaller(
                imported,
                new PackUpdatePlanner(),
                downloader,
                new TransactionalPackInstaller.NioFileOperations()
        ).install(manifest101);

        assertTrue(installed.success(), installed.message());
        assertEquals("1.0.1", stateStore.load().orElseThrow().packVersion());
        assertTrue(Files.isRegularFile(imported.resolve("mods/add.jar")));
        assertFalse(Files.exists(imported.resolve("mods/remove.jar")));
        assertEquals(
                Files.readString(release101.resolve("mods/replace.jar")),
                Files.readString(imported.resolve("mods/replace.jar"))
        );
        assertEquals(
                Files.readString(release101.resolve("config/changed.toml")),
                Files.readString(imported.resolve("config/changed.toml"))
        );
    }

    private Map<URI, byte[]> updatePayloads(
            PackControlManifest manifest,
            Path release,
            Path overrides
    ) throws IOException {
        Map<URI, byte[]> payloads = new HashMap<>();
        for (PackControlManifest.FileEntry file : manifest.files()) {
            byte[] content = Files.readAllBytes(release.resolve(file.path()));
            file.downloads().forEach(download -> payloads.put(URI.create(download), content));
        }
        byte[] overrideBytes = Files.readAllBytes(overrides);
        manifest.overrides().downloads().forEach(download ->
                payloads.put(URI.create(download), overrideBytes)
        );
        return payloads;
    }

    private static PackControlManifest readManifest(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) {
            return ManifestJson.fromJson(reader);
        }
    }

    private static void extractMrpackOverrides(Path mrpack, Path instance) throws IOException {
        Files.createDirectories(instance);
        try (ZipFile zip = new ZipFile(mrpack.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("overrides/")) {
                    continue;
                }
                String relative = entry.getName().substring("overrides/".length());
                Path target = instance.resolve(relative).normalize();
                if (!target.startsWith(instance)) {
                    throw new IOException("Unsafe test mrpack entry: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                try (var input = zip.getInputStream(entry)) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void copyMods(Path release, Path instance) throws IOException {
        Path source = release.resolve("mods");
        Path target = instance.resolve("mods");
        Files.createDirectories(target);
        try (var files = Files.list(source)) {
            for (Path file : files.toList()) {
                Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private Path copyFixture(String version) throws Exception {
        Path source = Path.of(getClass().getResource("/bootstrap-update/" + version).toURI());
        Path target = temporary.resolve("source-" + version);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return target;
    }

    private static final class FixtureSource implements PackFileSource {
        @Override
        public String sourceId() {
            return "fixture";
        }

        @Override
        public boolean supports(PackSourceReference reference) {
            return true;
        }

        @Override
        public Map<String, PackFileResolution> resolve(
                List<PackFileRequest> requests,
                CancellationToken cancellation
        ) throws IOException {
            Map<String, PackFileResolution> resolutions = new HashMap<>();
            for (PackFileRequest request : requests) {
                cancellation.throwIfCancelled();
                URI uri = URI.create(
                        "https://downloads.example.invalid/mods/"
                                + request.path().substring(request.path().lastIndexOf('/') + 1)
                );
                resolutions.put(request.requestId(), new PackFileResolution(
                        request.requestId(),
                        List.of(new PackFileCandidate(
                                uri,
                                sourceId(),
                                request.path(),
                                request.size(),
                                request.hashes(),
                                Set.of("downloads.example.invalid")
                        )),
                        List.of()
                ));
            }
            return resolutions;
        }
    }
}
