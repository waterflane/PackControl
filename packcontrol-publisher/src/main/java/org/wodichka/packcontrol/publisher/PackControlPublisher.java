package org.wodichka.packcontrol.publisher;

import org.wodichka.packcontrol.publisher.PublisherConfig.GitHubModMapping;
import org.wodichka.packcontrol.publisher.PublisherScanner.ScanResult;
import org.wodichka.packcontrol.publisher.PublisherScanner.ScannedFile;
import org.wodichka.packcontrol.updateformat.CancellationToken;
import org.wodichka.packcontrol.updateformat.FileHashing;
import org.wodichka.packcontrol.updateformat.GitHubReleaseSource;
import org.wodichka.packcontrol.updateformat.ManifestJson;
import org.wodichka.packcontrol.updateformat.ManifestValidationException;
import org.wodichka.packcontrol.updateformat.ManifestValidator;
import org.wodichka.packcontrol.updateformat.ModrinthSource;
import org.wodichka.packcontrol.updateformat.PackControlManifest;
import org.wodichka.packcontrol.updateformat.PackControlManifest.BuildMetadata;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Environment;
import org.wodichka.packcontrol.updateformat.PackControlManifest.EnvironmentRequirement;
import org.wodichka.packcontrol.updateformat.PackControlManifest.FileEntry;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverrideEntry;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverridesArchive;
import org.wodichka.packcontrol.updateformat.PackFileCandidate;
import org.wodichka.packcontrol.updateformat.PackFileRequest;
import org.wodichka.packcontrol.updateformat.PackFileResolution;
import org.wodichka.packcontrol.updateformat.PackFileSourceRegistry;
import org.wodichka.packcontrol.updateformat.PackSourceReference;
import org.wodichka.packcontrol.updateformat.PackSourceReference.GitHubReleaseReference;
import org.wodichka.packcontrol.updateformat.PackSourceReference.ModrinthReference;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class PackControlPublisher {
    public static final String CONFIG_FILE = "packcontrol-publisher.json";
    public static final String MANIFEST_FILE = "packcontrol-manifest.json";
    public static final String OVERRIDES_FILE = "overrides.zip";
    public static final String CHECKSUMS_FILE = "checksums.txt";

    private final PackFileSourceRegistry sources;
    private final PublisherScanner scanner;
    private final ManifestValidator manifestValidator;

    public PackControlPublisher() {
        this(new PackFileSourceRegistry(List.of(new ModrinthSource(), new GitHubReleaseSource())));
    }

    public PackControlPublisher(PackFileSourceRegistry sources) {
        this.sources = sources;
        this.scanner = new PublisherScanner();
        this.manifestValidator = new ManifestValidator();
    }

    public Inspection inspect(
            Path instance,
            PublisherConfig config,
            CancellationToken cancellation
    ) throws IOException, InterruptedException, PublisherException {
        validateConfig(config);
        ScanResult scan = scanner.scan(instance);
        Set<String> optional = config.optionalMods().stream()
                .map(PackControlPublisher::normalizePath)
                .collect(Collectors.toSet());
        Map<String, GitHubModMapping> github = config.githubMods().entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> normalizePath(entry.getKey()),
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        List<PackFileRequest> requests = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (ScannedFile mod : scan.mods()) {
            GitHubModMapping mapping = github.get(normalizePath(mod.path()));
            PackSourceReference reference;
            if (mapping != null) {
                if (!mapping.allowThirdPartyJar()) {
                    errors.add(mod.path() + ": explicit allowThirdPartyJar=true is required");
                    continue;
                }
                reference = new GitHubReleaseReference(
                        mapping.owner(),
                        mapping.repository(),
                        mapping.tag(),
                        List.of(mapping.assetName())
                );
            } else {
                reference = new ModrinthReference(fileName(mod.path()));
            }
            requests.add(new PackFileRequest(mod.path(), mod.path(), mod.hashes(), mod.size(), reference));
        }

        Map<String, PackFileResolution> resolved = sources.resolve(requests, cancellation);
        List<ResolvedMod> mods = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (ScannedFile mod : scan.mods()) {
            boolean required = !optional.contains(normalizePath(mod.path()));
            if (errors.stream().anyMatch(error -> error.startsWith(mod.path() + ":"))) {
                continue;
            }
            PackFileResolution resolution = resolved.get(mod.path());
            if (resolution == null || !resolution.resolved()) {
                String details = resolution == null
                        ? "source returned no result"
                        : resolution.issues().stream()
                        .map(issue -> issue.code() + ": " + issue.message())
                        .collect(Collectors.joining("; "));
                String message = mod.path() + ": " + details;
                if (required) {
                    errors.add(message);
                } else {
                    warnings.add("Optional mod omitted: " + message);
                }
                continue;
            }
            List<String> downloads = resolution.candidates().stream()
                    .map(PackFileCandidate::downloadUri)
                    .map(URI::toString)
                    .distinct()
                    .toList();
            mods.add(new ResolvedMod(mod, required, downloads));
        }
        return new Inspection(scan, mods, warnings, errors);
    }

    public BuildResult build(
            Path instance,
            Path output,
            PublisherConfig config,
            CancellationToken cancellation
    ) throws IOException, InterruptedException, PublisherException {
        Inspection inspection = inspect(instance, config, cancellation);
        if (!inspection.errors().isEmpty()) {
            throw new PublisherException("Publication blocked:\n - " + String.join("\n - ", inspection.errors()));
        }

        Path outputRoot = output.toAbsolutePath().normalize();
        Path parent = outputRoot.getParent();
        if (parent == null) {
            throw new PublisherException("Output directory must have a parent: " + outputRoot);
        }
        Files.createDirectories(parent);
        Path staging = Files.createTempDirectory(parent, ".packcontrol-publisher-");
        String mrpackName = safeFilePart(config.packId()) + "-" + safeFilePart(config.version()) + ".mrpack";
        try {
            Path overridesPath = staging.resolve(OVERRIDES_FILE);
            List<ReproducibleZip.Entry> overrideEntries = inspection.scan().overrides().stream()
                    .map(file -> ReproducibleZip.Entry.file(file.path(), file.source()))
                    .toList();
            ReproducibleZip.write(overridesPath, overrideEntries);
            FileHashing.DigestedContent overridesDigest = FileHashing.inspect(overridesPath);

            PackControlManifest manifest = createManifest(config, inspection, overridesDigest);
            try {
                manifestValidator.validateOrThrow(manifest);
            } catch (ManifestValidationException exception) {
                throw new PublisherException("Generated manifest is invalid: " + exception.getMessage(), exception);
            }
            Files.writeString(
                    staging.resolve(MANIFEST_FILE),
                    ManifestJson.toJson(manifest) + "\n",
                    StandardCharsets.UTF_8
            );

            new MrpackExporter().write(
                    staging.resolve(mrpackName),
                    manifest,
                    config.summary(),
                    inspection.scan().overrides()
            );
            writeChecksums(staging, mrpackName);

            PublisherOutputValidator validator = new PublisherOutputValidator();
            List<String> validationErrors = validator.validate(staging);
            if (!validationErrors.isEmpty()) {
                throw new PublisherException("Generated output failed self-validation:\n - "
                        + String.join("\n - ", validationErrors));
            }

            Files.createDirectories(outputRoot);
            for (String name : List.of(MANIFEST_FILE, OVERRIDES_FILE, mrpackName, CHECKSUMS_FILE)) {
                moveReplacing(staging.resolve(name), outputRoot.resolve(name));
            }
            return new BuildResult(
                    outputRoot.resolve(MANIFEST_FILE),
                    outputRoot.resolve(OVERRIDES_FILE),
                    outputRoot.resolve(mrpackName),
                    outputRoot.resolve(CHECKSUMS_FILE),
                    inspection.warnings()
            );
        } finally {
            deleteTree(staging);
        }
    }

    private PackControlManifest createManifest(
            PublisherConfig config,
            Inspection inspection,
            FileHashing.DigestedContent overridesDigest
    ) throws PublisherException {
        List<FileEntry> files = inspection.mods().stream()
                .map(mod -> new FileEntry(
                        mod.file().path(),
                        mod.downloads(),
                        mod.file().hashes(),
                        mod.file().size(),
                        mod.required(),
                        new Environment(
                                mod.required() ? EnvironmentRequirement.REQUIRED : EnvironmentRequirement.OPTIONAL,
                                mod.required() ? EnvironmentRequirement.REQUIRED : EnvironmentRequirement.OPTIONAL
                        )
                ))
                .toList();
        List<OverrideEntry> entries = inspection.scan().overrides().stream()
                .map(file -> new OverrideEntry(file.path(), file.hashes(), file.size()))
                .toList();
        URI base = releaseBase(config.releaseBaseUrl());
        return new PackControlManifest(
                ManifestValidator.SUPPORTED_SCHEMA_VERSION,
                new BuildMetadata(
                        config.packId(),
                        config.name(),
                        config.version(),
                        config.releaseId(),
                        config.minecraftVersion(),
                        config.loader(),
                        config.loaderVersion()
                ),
                config.minimumPackControlVersion(),
                files,
                new OverridesArchive(
                        OVERRIDES_FILE,
                        List.of(base.resolve(OVERRIDES_FILE).toString()),
                        overridesDigest.hashes(),
                        overridesDigest.size(),
                        entries
                ),
                List.of()
        );
    }

    private static void writeChecksums(Path staging, String mrpackName) throws IOException {
        StringBuilder output = new StringBuilder();
        for (String name : List.of(MANIFEST_FILE, OVERRIDES_FILE, mrpackName)) {
            output.append(FileHashing.inspect(staging.resolve(name)).hashes().sha256())
                    .append("  ")
                    .append(name)
                    .append('\n');
        }
        Files.writeString(staging.resolve(CHECKSUMS_FILE), output, StandardCharsets.UTF_8);
    }

    private static void validateConfig(PublisherConfig config) throws PublisherException {
        if (config == null) {
            throw new PublisherException("Publisher configuration is required");
        }
        if (!"neoforge".equalsIgnoreCase(config.loader())) {
            throw new PublisherException("MVP publisher supports only loader=neoforge");
        }
        if (!"1.21.1".equals(config.minecraftVersion())) {
            throw new PublisherException("MVP publisher supports only minecraftVersion=1.21.1");
        }
        releaseBase(config.releaseBaseUrl());
        for (String optional : config.optionalMods()) {
            if (!safeConfiguredModPath(optional)) {
                throw new PublisherException("optionalMods entry must be a safe mods/*.jar path: " + optional);
            }
        }
        Set<String> mappings = new HashSet<>();
        for (Map.Entry<String, GitHubModMapping> entry : config.githubMods().entrySet()) {
            String path = normalizePath(entry.getKey());
            if (!safeConfiguredModPath(entry.getKey())) {
                throw new PublisherException("GitHub mapping must target a mods/*.jar path: " + entry.getKey());
            }
            if (!mappings.add(path)) {
                throw new PublisherException("Duplicate GitHub mapping path: " + entry.getKey());
            }
            GitHubModMapping mapping = entry.getValue();
            if (mapping == null || blank(mapping.owner()) || blank(mapping.repository())
                    || blank(mapping.tag()) || blank(mapping.assetName())) {
                throw new PublisherException("Incomplete GitHub mapping for " + entry.getKey());
            }
        }
    }

    private static URI releaseBase(String value) throws PublisherException {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new PublisherException("releaseBaseUrl must be an absolute HTTPS URL");
            }
            String text = uri.toString();
            return URI.create(text.endsWith("/") ? text : text + "/");
        } catch (URISyntaxException | NullPointerException exception) {
            throw new PublisherException("releaseBaseUrl must be an absolute HTTPS URL", exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizePath(String path) {
        return path.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static boolean safeConfiguredModPath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String path = value.replace('\\', '/');
        if (!path.startsWith("mods/") || !path.toLowerCase(Locale.ROOT).endsWith(".jar")
                || path.startsWith("/") || path.contains("//") || path.indexOf(':') >= 0) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String safeFilePart(String value) throws PublisherException {
        if (value == null || !value.matches("[A-Za-z0-9._+-]+")) {
            throw new PublisherException("packId and version must be safe file-name components");
        }
        return value;
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    public record Inspection(
            ScanResult scan,
            List<ResolvedMod> mods,
            List<String> warnings,
            List<String> errors
    ) {
        public Inspection {
            mods = List.copyOf(mods);
            warnings = List.copyOf(warnings);
            errors = List.copyOf(errors);
        }
    }

    public record ResolvedMod(ScannedFile file, boolean required, List<String> downloads) {
        public ResolvedMod {
            downloads = List.copyOf(downloads);
        }
    }

    public record BuildResult(
            Path manifest,
            Path overrides,
            Path mrpack,
            Path checksums,
            List<String> warnings
    ) {
        public BuildResult {
            warnings = List.copyOf(warnings);
        }
    }
}
