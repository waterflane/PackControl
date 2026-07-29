package org.wodichka.packcontrol.publisher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.wodichka.packcontrol.updateformat.InstalledPackState.ManagedFile;
import org.wodichka.packcontrol.updateformat.PackBootstrap;
import org.wodichka.packcontrol.updateformat.PackBootstrapJson;
import org.wodichka.packcontrol.updateformat.PackControlManifest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class BootstrapArtifacts {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private BootstrapArtifacts() {
    }

    static byte[] packConfig(PublisherConfig config) {
        PackConfig value = new PackConfig(
                1,
                config.targetGithubRepository(),
                config.updateChannel().toLowerCase(java.util.Locale.ROOT)
        );
        return withNewline(GSON.toJson(value));
    }

    static PackBootstrap bootstrap(PackControlManifest manifest, String manifestSha256) {
        List<ManagedFile> managed = new ArrayList<>();
        manifest.files().forEach(file -> managed.add(
                new ManagedFile(file.path(), file.hashes(), file.size())
        ));
        manifest.overrides().entries().forEach(file -> managed.add(
                new ManagedFile(file.path(), file.hashes(), file.size())
        ));
        managed.sort(Comparator.comparing(ManagedFile::path));
        return new PackBootstrap(
                PackBootstrap.CURRENT_SCHEMA_VERSION,
                manifest.metadata().packId(),
                manifest.metadata().version(),
                manifest.metadata().releaseId(),
                manifestSha256,
                managed
        );
    }

    static byte[] bootstrapJson(PackBootstrap bootstrap) {
        return withNewline(PackBootstrapJson.toJson(bootstrap));
    }

    private static byte[] withNewline(String json) {
        return (json + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private record PackConfig(
            int schemaVersion,
            String targetGithubRepository,
            String updateChannel
    ) {
    }
}
