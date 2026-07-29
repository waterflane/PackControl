package org.wodichka.packcontrol.publisher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.wodichka.packcontrol.publisher.PublisherScanner.ScannedFile;
import org.wodichka.packcontrol.updateformat.PackControlManifest;
import org.wodichka.packcontrol.updateformat.PackControlManifest.EnvironmentRequirement;
import org.wodichka.packcontrol.updateformat.PackControlManifest.FileEntry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MrpackExporter {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    void write(
            Path output,
            PackControlManifest manifest,
            String summary,
            List<ScannedFile> overrides,
            byte[] packConfig,
            byte[] bootstrap
    ) throws IOException {
        List<MrpackFile> files = manifest.files().stream().map(this::toMrpackFile).toList();
        Map<String, String> dependencies = new LinkedHashMap<>();
        dependencies.put("minecraft", manifest.metadata().minecraftVersion());
        dependencies.put("neoforge", manifest.metadata().loaderVersion());
        MrpackIndex index = new MrpackIndex(
                1,
                "minecraft",
                manifest.metadata().version(),
                manifest.metadata().name(),
                summary,
                files,
                dependencies
        );

        List<ReproducibleZip.Entry> entries = new ArrayList<>();
        entries.add(ReproducibleZip.Entry.bytes(
                "modrinth.index.json",
                (GSON.toJson(index) + "\n").getBytes(StandardCharsets.UTF_8)
        ));
        for (ScannedFile override : overrides) {
            entries.add(ReproducibleZip.Entry.file("overrides/" + override.path(), override.source()));
        }
        entries.add(ReproducibleZip.Entry.bytes(
                "overrides/" + PackControlPublisher.PACK_CONFIG_FILE,
                packConfig
        ));
        entries.add(ReproducibleZip.Entry.bytes(
                "overrides/" + PackControlPublisher.BOOTSTRAP_FILE,
                bootstrap
        ));
        ReproducibleZip.write(output, entries);
    }

    private MrpackFile toMrpackFile(FileEntry file) {
        Map<String, String> hashes = new LinkedHashMap<>();
        hashes.put("sha1", file.hashes().sha1());
        hashes.put("sha512", file.hashes().sha512());
        Map<String, String> env = new LinkedHashMap<>();
        env.put("client", requirement(file.environment().client()));
        env.put("server", requirement(file.environment().server()));
        return new MrpackFile(file.path(), hashes, env, file.downloads(), file.size());
    }

    private static String requirement(EnvironmentRequirement value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }

    private record MrpackIndex(
            int formatVersion,
            String game,
            String versionId,
            String name,
            String summary,
            List<MrpackFile> files,
            Map<String, String> dependencies
    ) {
    }

    private record MrpackFile(
            String path,
            Map<String, String> hashes,
            Map<String, String> env,
            List<String> downloads,
            long fileSize
    ) {
    }
}
