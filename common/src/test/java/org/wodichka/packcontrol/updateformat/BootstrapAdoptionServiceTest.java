package org.wodichka.packcontrol.updateformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wodichka.packcontrol.updateformat.BootstrapAdoptionService.AdoptionResult;
import org.wodichka.packcontrol.updateformat.BootstrapAdoptionService.Status;
import org.wodichka.packcontrol.updateformat.InstalledPackState.ManagedFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapAdoptionServiceTest {
    @TempDir
    Path instance;

    @Test
    void adoptsMatchingFilesAndIsIdempotent() throws Exception {
        writeManaged("mods/example.jar", "fixture");
        PackBootstrap bootstrap = bootstrap(List.of(managed("mods/example.jar")));
        writeBootstrap(bootstrap);

        BootstrapAdoptionService service = new BootstrapAdoptionService();
        AdoptionResult first = service.adopt(instance);
        byte[] stateBytes = Files.readAllBytes(new InstalledStateStore(instance).statePath());
        AdoptionResult second = service.adopt(instance);

        assertEquals(Status.ADOPTED, first.status());
        assertEquals(Status.ALREADY_ADOPTED, second.status());
        assertEquals(bootstrap.toInstalledState(), second.installedState());
        assertTrue(java.util.Arrays.equals(
                stateBytes,
                Files.readAllBytes(new InstalledStateStore(instance).statePath())
        ));
    }

    @Test
    void mismatchedFileIsNotAdopted() throws Exception {
        writeManaged("mods/example.jar", "fixture");
        PackBootstrap bootstrap = bootstrap(List.of(managed("mods/example.jar")));
        writeBootstrap(bootstrap);
        Files.writeString(instance.resolve("mods/example.jar"), "changed");

        AdoptionResult result = new BootstrapAdoptionService().adopt(instance);

        assertEquals(Status.FILE_MISMATCH, result.status());
        assertFalse(Files.exists(new InstalledStateStore(instance).statePath()));
        assertTrue(result.issues().stream().anyMatch(issue ->
                issue.message().contains("does not match bootstrap")
        ));
    }

    @Test
    void existingInstalledStateIsNeverOverwritten() throws Exception {
        writeManaged("mods/example.jar", "fixture");
        PackBootstrap bootstrap = bootstrap(List.of(managed("mods/example.jar")));
        writeBootstrap(bootstrap);
        InstalledPackState existing = new InstalledPackState(
                1,
                "other-pack",
                "9.0.0",
                "other-release",
                "f".repeat(64),
                List.of()
        );
        InstalledStateStore store = new InstalledStateStore(instance);
        store.save(existing);
        byte[] before = Files.readAllBytes(store.statePath());

        AdoptionResult result = new BootstrapAdoptionService().adopt(instance);

        assertEquals(Status.EXISTING_STATE, result.status());
        assertEquals(existing, store.load().orElseThrow());
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(store.statePath())));
    }

    @Test
    void unsafeBootstrapPathIsRejected() throws Exception {
        PackBootstrap bootstrap = bootstrap(List.of(new ManagedFile(
                "../escape.jar",
                ManifestTestFixtures.validHashes(),
                1
        )));
        writeBootstrap(bootstrap);

        AdoptionResult result = new BootstrapAdoptionService().adopt(instance);

        assertEquals(Status.INVALID_BOOTSTRAP, result.status());
        assertFalse(Files.exists(new InstalledStateStore(instance).statePath()));
    }

    private ManagedFile managed(String path) throws Exception {
        Path file = instance.resolve(path.replace('/', java.io.File.separatorChar));
        FileHashing.DigestedContent digest = FileHashing.inspect(file);
        return new ManagedFile(path, digest.hashes(), digest.size());
    }

    private void writeManaged(String path, String content) throws Exception {
        Path target = instance.resolve(path.replace('/', java.io.File.separatorChar));
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private void writeBootstrap(PackBootstrap bootstrap) throws Exception {
        Path path = instance.resolve(BootstrapAdoptionService.BOOTSTRAP_RELATIVE_PATH);
        Files.createDirectories(path.getParent());
        Files.writeString(path, PackBootstrapJson.toJson(bootstrap));
    }

    private static PackBootstrap bootstrap(List<ManagedFile> files) {
        return new PackBootstrap(
                1,
                "fixture-pack",
                "1.0.0",
                "fixture-1.0.0",
                "a".repeat(64),
                files
        );
    }
}
