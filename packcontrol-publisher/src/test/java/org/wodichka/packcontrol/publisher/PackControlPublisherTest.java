package org.wodichka.packcontrol.publisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wodichka.packcontrol.updateformat.CancellationToken;
import org.wodichka.packcontrol.updateformat.PackFileCandidate;
import org.wodichka.packcontrol.updateformat.PackFileRequest;
import org.wodichka.packcontrol.updateformat.PackFileResolution;
import org.wodichka.packcontrol.updateformat.PackFileResolution.Issue;
import org.wodichka.packcontrol.updateformat.PackFileResolution.IssueCode;
import org.wodichka.packcontrol.updateformat.PackFileSource;
import org.wodichka.packcontrol.updateformat.PackFileSourceRegistry;
import org.wodichka.packcontrol.updateformat.PackSourceReference;
import org.wodichka.packcontrol.updateformat.PackSourceReference.GitHubReleaseReference;
import org.wodichka.packcontrol.updateformat.PackSourceReference.ModrinthReference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackControlPublisherTest {
    @TempDir
    Path temporary;

    @Test
    void buildIsReproducibleSelfValidatingAndUsesOneModrinthBatch() throws Exception {
        Path instance = copyFixture("pack-a");
        PublisherConfig config = PublisherConfig.read(instance.resolve(PackControlPublisher.CONFIG_FILE));
        CountingSource modrinth = new CountingSource("modrinth", ModrinthReference.class);
        CountingSource github = new CountingSource("github-release", GitHubReleaseReference.class);
        PackControlPublisher publisher = publisher(modrinth, github);

        Path first = temporary.resolve("out-one");
        Path second = temporary.resolve("out-two");
        PackControlPublisher.BuildResult firstResult =
                publisher.build(instance, first, config, CancellationToken.none());
        PackControlPublisher.BuildResult secondResult =
                publisher.build(instance, second, config, CancellationToken.none());

        assertEquals(2, modrinth.calls.get(), "one Modrinth batch per complete build");
        assertEquals(List.of(2, 2), modrinth.batchSizes);
        assertEquals(2, github.calls.get());
        assertArrayEquals(Files.readAllBytes(firstResult.manifest()), Files.readAllBytes(secondResult.manifest()));
        assertArrayEquals(Files.readAllBytes(firstResult.overrides()), Files.readAllBytes(secondResult.overrides()));
        assertArrayEquals(Files.readAllBytes(firstResult.mrpack()), Files.readAllBytes(secondResult.mrpack()));
        assertArrayEquals(Files.readAllBytes(firstResult.checksums()), Files.readAllBytes(secondResult.checksums()));
        assertTrue(new PublisherOutputValidator().validate(first).isEmpty());

        try (ZipFile overrides = new ZipFile(firstResult.overrides().toFile())) {
            assertEquals(
                    Set.of(
                            "config/fixture.toml",
                            "defaultconfigs/server.toml",
                            "kubejs/server_scripts/fixture.js"
                    ),
                    overrides.stream().map(entry -> entry.getName()).collect(java.util.stream.Collectors.toSet())
            );
            assertFalse(overrides.stream().anyMatch(entry -> entry.getName().endsWith(".jar")));
            assertFalse(overrides.stream().anyMatch(entry -> entry.getName().equals("options.txt")));
        }
        try (ZipFile mrpack = new ZipFile(firstResult.mrpack().toFile())) {
            assertTrue(mrpack.getEntry("modrinth.index.json") != null);
            assertFalse(mrpack.stream().anyMatch(entry -> entry.getName().endsWith(".jar")));
        }
    }

    @Test
    void unknownRequiredModBlocksPublication() throws Exception {
        Path instance = copyFixture("unknown");
        PublisherConfig config = PublisherConfig.read(instance.resolve(PackControlPublisher.CONFIG_FILE));
        PackFileSource unresolved = new PackFileSource() {
            @Override
            public String sourceId() {
                return "unresolved";
            }

            @Override
            public boolean supports(PackSourceReference reference) {
                return true;
            }

            @Override
            public Map<String, PackFileResolution> resolve(
                    List<PackFileRequest> requests,
                    CancellationToken cancellation
            ) {
                Map<String, PackFileResolution> result = new HashMap<>();
                for (PackFileRequest request : requests) {
                    result.put(request.requestId(), new PackFileResolution(
                            request.requestId(),
                            List.of(),
                            List.of(new Issue(IssueCode.NOT_FOUND, "fixture has no source"))
                    ));
                }
                return result;
            }
        };
        PackControlPublisher publisher =
                new PackControlPublisher(new PackFileSourceRegistry(List.of(unresolved)));

        PublisherException exception = assertThrows(
                PublisherException.class,
                () -> publisher.build(instance, temporary.resolve("blocked"), config, CancellationToken.none())
        );
        assertTrue(exception.getMessage().contains("mods/alpha.jar"));
        assertFalse(Files.exists(temporary.resolve("blocked/packcontrol-manifest.json")));
    }

    @Test
    void thirdPartyJarRequiresExplicitPermission() throws Exception {
        Path instance = copyFixture("permission");
        Path configPath = instance.resolve(PackControlPublisher.CONFIG_FILE);
        String configText = Files.readString(configPath).replace(
                "\"allowThirdPartyJar\": true",
                "\"allowThirdPartyJar\": false"
        );
        Files.writeString(configPath, configText);
        PublisherConfig config = PublisherConfig.read(configPath);

        PublisherException exception = assertThrows(
                PublisherException.class,
                () -> publisher(new CountingSource("modrinth", ModrinthReference.class),
                                new CountingSource("github-release", GitHubReleaseReference.class))
                        .build(instance, temporary.resolve("denied"), config, CancellationToken.none())
        );
        assertTrue(exception.getMessage().contains("allowThirdPartyJar=true"));
    }

    @Test
    void configurationRejectsSecretFields() throws Exception {
        Path config = temporary.resolve("secret.json");
        Files.writeString(config, """
                {
                  "packId": "fixture",
                  "token": "do-not-store-this"
                }
                """);

        PublisherException exception =
                assertThrows(PublisherException.class, () -> PublisherConfig.read(config));
        assertTrue(exception.getMessage().contains("Secrets are not allowed"));
    }

    @Test
    void validateCliAcceptsBuiltPublication() throws Exception {
        Path instance = copyFixture("cli");
        PublisherConfig config = PublisherConfig.read(instance.resolve(PackControlPublisher.CONFIG_FILE));
        Path output = temporary.resolve("cli-output");
        publisher(new CountingSource("modrinth", ModrinthReference.class),
                new CountingSource("github-release", GitHubReleaseReference.class))
                .build(instance, output, config, CancellationToken.none());

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int exit = PublisherCli.run(
                new String[]{"validate", "--input", output.toString()},
                new PrintStream(stdout),
                new PrintStream(new ByteArrayOutputStream())
        );
        assertEquals(0, exit);
        assertTrue(stdout.toString(StandardCharsets.UTF_8).contains("Valid PackControl publication"));
    }

    @Test
    void validatorRejectsTamperedArtifact() throws Exception {
        Path instance = copyFixture("tampered");
        PublisherConfig config = PublisherConfig.read(instance.resolve(PackControlPublisher.CONFIG_FILE));
        Path output = temporary.resolve("tampered-output");
        PackControlPublisher.BuildResult result =
                publisher(new CountingSource("modrinth", ModrinthReference.class),
                        new CountingSource("github-release", GitHubReleaseReference.class))
                        .build(instance, output, config, CancellationToken.none());

        Files.write(result.overrides(), new byte[]{0x00}, java.nio.file.StandardOpenOption.APPEND);

        List<String> errors = new PublisherOutputValidator().validate(output);
        assertTrue(errors.stream().anyMatch(error -> error.contains("overrides.zip")));
    }

    private PackControlPublisher publisher(PackFileSource... sources) {
        return new PackControlPublisher(new PackFileSourceRegistry(List.of(sources)));
    }

    private Path copyFixture(String name) throws Exception {
        Path source = Path.of(getClass().getResource("/fixture-pack").toURI());
        Path target = temporary.resolve(name);
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

    private static final class CountingSource implements PackFileSource {
        private final String id;
        private final Class<? extends PackSourceReference> supported;
        private final AtomicInteger calls = new AtomicInteger();
        private final List<Integer> batchSizes = new ArrayList<>();

        private CountingSource(String id, Class<? extends PackSourceReference> supported) {
            this.id = id;
            this.supported = supported;
        }

        @Override
        public String sourceId() {
            return id;
        }

        @Override
        public boolean supports(PackSourceReference reference) {
            return supported.isInstance(reference);
        }

        @Override
        public Map<String, PackFileResolution> resolve(
                List<PackFileRequest> requests,
                CancellationToken cancellation
        ) throws IOException {
            calls.incrementAndGet();
            batchSizes.add(requests.size());
            Map<String, PackFileResolution> result = new HashMap<>();
            for (PackFileRequest request : requests) {
                cancellation.throwIfCancelled();
                URI uri = URI.create(
                        id.equals("modrinth")
                                ? "https://cdn.modrinth.com/data/fixture/versions/1/" + fileName(request.path())
                                : "https://github.com/example/custom-mod/releases/download/v1.0.0/custom.jar"
                );
                PackFileCandidate candidate = new PackFileCandidate(
                        uri,
                        id,
                        fileName(request.path()),
                        request.size(),
                        request.hashes(),
                        Set.of(uri.getHost())
                );
                result.put(request.requestId(), new PackFileResolution(
                        request.requestId(),
                        List.of(candidate),
                        List.of()
                ));
            }
            return result;
        }

        private static String fileName(String path) {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }
}
