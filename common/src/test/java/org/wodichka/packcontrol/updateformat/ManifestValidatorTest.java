package org.wodichka.packcontrol.updateformat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Environment;
import org.wodichka.packcontrol.updateformat.PackControlManifest.EnvironmentRequirement;
import org.wodichka.packcontrol.updateformat.PackControlManifest.FileEntry;
import org.wodichka.packcontrol.updateformat.PackControlManifest.Hashes;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverrideEntry;
import org.wodichka.packcontrol.updateformat.PackControlManifest.OverridesArchive;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestValidatorTest {
    private final ManifestValidator validator = new ManifestValidator();

    @Test
    void acceptsValidManifest() {
        ManifestValidationResult result = validator.validate(ManifestTestFixtures.validManifest());

        assertTrue(result.isValid(), () -> "Unexpected errors: " + result.errors());
    }

    @Test
    void returnsStructuredErrorsAndThrowsOnDemand() {
        PackControlManifest manifest = new PackControlManifest(
                99,
                null,
                "not-semver",
                null,
                null,
                null
        );

        ManifestValidationResult result = validator.validate(manifest);
        ManifestValidationException exception = assertThrows(
                ManifestValidationException.class,
                result::throwIfInvalid
        );

        assertFalse(result.isValid());
        assertHasError(result, ManifestErrorCode.UNSUPPORTED_SCHEMA_VERSION, "/schemaVersion");
        assertHasError(result, ManifestErrorCode.REQUIRED_VALUE, "/metadata");
        assertHasError(result, ManifestErrorCode.INVALID_VERSION, "/minimumPackControlVersion");
        assertTrue(exception.result() == result);
    }

    @ParameterizedTest
    @MethodSource("dangerousPaths")
    void rejectsDangerousPaths(String path, ManifestErrorCode expectedCode) {
        PackControlManifest base = ManifestTestFixtures.validManifest();
        PackControlManifest manifest = ManifestTestFixtures.withFiles(
                base,
                List.of(ManifestTestFixtures.validFile(path, 1_024))
        );

        ManifestValidationResult result = validator.validate(manifest);

        assertHasError(result, expectedCode, "/files/0/path");
    }

    static Stream<Arguments> dangerousPaths() {
        return Stream.of(
                Arguments.of("/mods/evil.jar", ManifestErrorCode.ABSOLUTE_PATH),
                Arguments.of("\\\\server\\share\\evil.jar", ManifestErrorCode.ABSOLUTE_PATH),
                Arguments.of("\\mods\\evil.jar", ManifestErrorCode.ABSOLUTE_PATH),
                Arguments.of("C:/mods/evil.jar", ManifestErrorCode.DRIVE_PREFIXED_PATH),
                Arguments.of("d:\\mods\\evil.jar", ManifestErrorCode.DRIVE_PREFIXED_PATH),
                Arguments.of("../evil.jar", ManifestErrorCode.PATH_TRAVERSAL),
                Arguments.of("mods/../evil.jar", ManifestErrorCode.PATH_TRAVERSAL),
                Arguments.of("mods\\..\\evil.jar", ManifestErrorCode.PATH_TRAVERSAL),
                Arguments.of("mods\\evil.jar", ManifestErrorCode.INVALID_PATH),
                Arguments.of("mods//evil.jar", ManifestErrorCode.INVALID_PATH),
                Arguments.of("mods/./evil.jar", ManifestErrorCode.INVALID_PATH),
                Arguments.of("mods/evil.jar.", ManifestErrorCode.INVALID_PATH),
                Arguments.of("mods/file:stream.jar", ManifestErrorCode.INVALID_PATH),
                Arguments.of(" mods/evil.jar", ManifestErrorCode.INVALID_PATH)
        );
    }

    @Test
    void rejectsHttpAndMalformedDownloads() {
        PackControlManifest base = ManifestTestFixtures.validManifest();
        FileEntry http = fileWithDownloads(List.of("http://example.org/mod.jar"));
        FileEntry malformed = fileWithDownloads(List.of("https://"));
        PackControlManifest manifest = ManifestTestFixtures.withFiles(base, List.of(http, malformed));

        ManifestValidationResult result = validator.validate(manifest);

        assertHasError(result, ManifestErrorCode.INSECURE_DOWNLOAD_URL, "/files/0/downloads/0");
        assertHasError(result, ManifestErrorCode.INVALID_DOWNLOAD_URL, "/files/1/downloads/0");
    }

    @Test
    void rejectsCredentialsFragmentsAndDuplicateDownloads() {
        PackControlManifest base = ManifestTestFixtures.validManifest();
        FileEntry file = fileWithDownloads(List.of(
                "https://user:secret@example.org/mod.jar",
                "https://example.org/mod.jar#fragment",
                "https://example.org/mod.jar",
                "https://example.org/mod.jar"
        ));

        ManifestValidationResult result = validator.validate(
                ManifestTestFixtures.withFiles(base, List.of(file))
        );

        assertHasError(result, ManifestErrorCode.INVALID_DOWNLOAD_URL, "/files/0/downloads/0");
        assertHasError(result, ManifestErrorCode.INVALID_DOWNLOAD_URL, "/files/0/downloads/1");
        assertHasError(result, ManifestErrorCode.DUPLICATE_DOWNLOAD, "/files/0/downloads/3");
    }

    @ParameterizedTest
    @MethodSource("invalidHashes")
    void validatesEveryRequiredHash(Hashes hashes, String expectedPointer, ManifestErrorCode code) {
        PackControlManifest base = ManifestTestFixtures.validManifest();
        FileEntry valid = ManifestTestFixtures.validFile("mods/example.jar", 1_024);
        FileEntry file = new FileEntry(
                valid.path(),
                valid.downloads(),
                hashes,
                valid.size(),
                valid.required(),
                valid.environment()
        );

        ManifestValidationResult result = validator.validate(
                ManifestTestFixtures.withFiles(base, List.of(file))
        );

        assertHasError(result, code, expectedPointer);
    }

    static Stream<Arguments> invalidHashes() {
        return Stream.of(
                Arguments.of(
                        new Hashes(null, "b".repeat(64), "c".repeat(128)),
                        "/files/0/hashes/sha1",
                        ManifestErrorCode.MISSING_HASH
                ),
                Arguments.of(
                        new Hashes("z".repeat(40), "b".repeat(64), "c".repeat(128)),
                        "/files/0/hashes/sha1",
                        ManifestErrorCode.INVALID_HASH
                ),
                Arguments.of(
                        new Hashes("a".repeat(40), "b".repeat(63), "c".repeat(128)),
                        "/files/0/hashes/sha256",
                        ManifestErrorCode.INVALID_HASH
                ),
                Arguments.of(
                        new Hashes("a".repeat(40), "b".repeat(64), "c".repeat(127)),
                        "/files/0/hashes/sha512",
                        ManifestErrorCode.INVALID_HASH
                )
        );
    }

    @Test
    void validatesHashesForArchiveAndOverrideEntries() {
        PackControlManifest base = ManifestTestFixtures.validManifest();
        OverridesArchive overrides = new OverridesArchive(
                "overrides.zip",
                List.of("https://example.org/overrides.zip"),
                new Hashes("bad", "b".repeat(64), "c".repeat(128)),
                100L,
                List.of(new OverrideEntry(
                        "kubejs/server_scripts/test.js",
                        new Hashes("a".repeat(40), "bad", "c".repeat(128)),
                        10L
                ))
        );

        ManifestValidationResult result = validator.validate(
                ManifestTestFixtures.withOverrides(base, overrides)
        );

        assertHasError(result, ManifestErrorCode.INVALID_HASH, "/overrides/hashes/sha1");
        assertHasError(result, ManifestErrorCode.INVALID_HASH, "/overrides/entries/0/hashes/sha256");
    }

    @Test
    void detectsDuplicateAndCaseConflictingPaths() {
        PackControlManifest base = ManifestTestFixtures.validManifest();
        PackControlManifest exactDuplicates = ManifestTestFixtures.withFiles(
                base,
                List.of(
                        ManifestTestFixtures.validFile("mods/example.jar", 10),
                        ManifestTestFixtures.validFile("mods/example.jar", 10)
                )
        );
        PackControlManifest caseDuplicates = ManifestTestFixtures.withFiles(
                base,
                List.of(
                        ManifestTestFixtures.validFile("mods/Example.jar", 10),
                        ManifestTestFixtures.validFile("MODS/example.jar", 10)
                )
        );

        assertHasError(
                validator.validate(exactDuplicates),
                ManifestErrorCode.DUPLICATE_PATH,
                "/files/1/path"
        );
        assertHasError(
                validator.validate(caseDuplicates),
                ManifestErrorCode.DUPLICATE_PATH,
                "/files/1/path"
        );
    }

    @Test
    void detectsFileDirectoryAndCrossSectionConflicts() {
        PackControlManifest base = ManifestTestFixtures.validManifest();
        PackControlManifest prefixConflict = ManifestTestFixtures.withFiles(
                base,
                List.of(
                        ManifestTestFixtures.validFile("mods/container", 10),
                        ManifestTestFixtures.validFile("mods/container/child.jar", 10)
                )
        );
        PackControlManifest addedAndRemoved = ManifestTestFixtures.withRemovedFiles(
                base,
                List.of("mods/example.jar")
        );
        OverridesArchive overrides = new OverridesArchive(
                "overrides.zip",
                List.of("https://example.org/overrides.zip"),
                ManifestTestFixtures.validHashes(),
                100L,
                List.of(
                        new OverrideEntry("config/value", ManifestTestFixtures.validHashes(), 10L),
                        new OverrideEntry("config/value/child", ManifestTestFixtures.validHashes(), 10L)
                )
        );

        assertHasError(
                validator.validate(prefixConflict),
                ManifestErrorCode.CONFLICTING_PATH,
                "/files/1/path"
        );
        assertHasError(
                validator.validate(addedAndRemoved),
                ManifestErrorCode.CONFLICTING_PATH,
                "/removedFiles/0"
        );
        assertHasError(
                validator.validate(ManifestTestFixtures.withOverrides(base, overrides)),
                ManifestErrorCode.CONFLICTING_PATH,
                "/overrides/entries/1/path"
        );
    }

    @Test
    void restrictsOverrideRootsAndArchiveName() {
        PackControlManifest base = ManifestTestFixtures.validManifest();
        OverridesArchive overrides = new OverridesArchive(
                "nested/overrides.jar",
                List.of("https://example.org/overrides.jar"),
                ManifestTestFixtures.validHashes(),
                100L,
                List.of(new OverrideEntry(
                        "mods/hidden.jar",
                        ManifestTestFixtures.validHashes(),
                        10L
                ))
        );

        ManifestValidationResult result = validator.validate(
                ManifestTestFixtures.withOverrides(base, overrides)
        );

        assertHasError(result, ManifestErrorCode.INVALID_OVERRIDE_ARCHIVE, "/overrides/fileName");
        assertHasError(result, ManifestErrorCode.INVALID_OVERRIDE_ROOT, "/overrides/entries/0/path");
    }

    @Test
    void rejectsInvalidEnvironmentAndMissingRequiredFlag() {
        PackControlManifest base = ManifestTestFixtures.validManifest();
        FileEntry valid = ManifestTestFixtures.validFile("mods/example.jar", 10);
        FileEntry invalid = new FileEntry(
                valid.path(),
                valid.downloads(),
                valid.hashes(),
                valid.size(),
                null,
                new Environment(EnvironmentRequirement.UNSUPPORTED, EnvironmentRequirement.UNSUPPORTED)
        );

        ManifestValidationResult result = validator.validate(
                ManifestTestFixtures.withFiles(base, List.of(invalid))
        );

        assertHasError(result, ManifestErrorCode.REQUIRED_VALUE, "/files/0/required");
        assertHasError(result, ManifestErrorCode.INVALID_ENVIRONMENT, "/files/0/environment");
    }

    @Test
    void enforcesAllCountAndSizeLimits() {
        ManifestLimits tinyLimits = new ManifestLimits(
                1,
                1,
                1,
                1,
                10,
                10,
                10,
                15
        );
        ManifestValidator tinyValidator = new ManifestValidator(tinyLimits);
        PackControlManifest base = ManifestTestFixtures.validManifest();
        FileEntry oversizedOne = new FileEntry(
                "mods/one.jar",
                List.of("https://example.org/one.jar", "https://mirror.example.org/one.jar"),
                ManifestTestFixtures.validHashes(),
                11L,
                true,
                new Environment(EnvironmentRequirement.REQUIRED, EnvironmentRequirement.REQUIRED)
        );
        FileEntry oversizedTwo = ManifestTestFixtures.validFile("mods/two.jar", 11);
        OverridesArchive overrides = new OverridesArchive(
                "overrides.zip",
                List.of("https://example.org/overrides.zip"),
                ManifestTestFixtures.validHashes(),
                11L,
                List.of(
                        new OverrideEntry("config/one.toml", ManifestTestFixtures.validHashes(), 11L),
                        new OverrideEntry("config/two.toml", ManifestTestFixtures.validHashes(), 1L)
                )
        );
        PackControlManifest manifest = new PackControlManifest(
                base.schemaVersion(),
                base.metadata(),
                base.minimumPackControlVersion(),
                List.of(oversizedOne, oversizedTwo),
                overrides,
                List.of("mods/old-one.jar", "mods/old-two.jar")
        );

        ManifestValidationResult result = tinyValidator.validate(manifest);

        assertHasError(result, ManifestErrorCode.TOO_MANY_FILES, "/files");
        assertHasError(result, ManifestErrorCode.TOO_MANY_DOWNLOADS, "/files/0/downloads");
        assertHasError(result, ManifestErrorCode.SIZE_LIMIT_EXCEEDED, "/files/0/size");
        assertHasError(result, ManifestErrorCode.SIZE_LIMIT_EXCEEDED, "/overrides/size");
        assertHasError(result, ManifestErrorCode.TOO_MANY_OVERRIDE_ENTRIES, "/overrides/entries");
        assertHasError(result, ManifestErrorCode.SIZE_LIMIT_EXCEEDED, "/overrides/entries/0/size");
        assertHasError(result, ManifestErrorCode.TOO_MANY_REMOVED_FILES, "/removedFiles");
        assertHasError(result, ManifestErrorCode.TOTAL_SIZE_LIMIT_EXCEEDED, "");
    }

    @Test
    void rejectsInvalidSizesAndVersions() {
        PackControlManifest base = ManifestTestFixtures.validManifest();
        FileEntry invalidSize = ManifestTestFixtures.validFile("mods/zero.jar", 0);
        PackControlManifest manifest = new PackControlManifest(
                base.schemaVersion(),
                new PackControlManifest.BuildMetadata(
                        "pack",
                        "Pack",
                        "01.2.3",
                        "release",
                        "1.21.1",
                        "neoforge",
                        "21.1.233"
                ),
                "next",
                List.of(invalidSize),
                base.overrides(),
                base.removedFiles()
        );

        ManifestValidationResult result = validator.validate(manifest);

        assertHasError(result, ManifestErrorCode.INVALID_VERSION, "/metadata/version");
        assertHasError(result, ManifestErrorCode.INVALID_VERSION, "/minimumPackControlVersion");
        assertHasError(result, ManifestErrorCode.INVALID_SIZE, "/files/0/size");
    }

    @Test
    void validatesNullManifest() {
        ManifestValidationResult result = validator.validate(null);

        assertHasError(result, ManifestErrorCode.MANIFEST_REQUIRED, "");
    }

    private static FileEntry fileWithDownloads(List<String> downloads) {
        FileEntry valid = ManifestTestFixtures.validFile("mods/example.jar", 1_024);
        return new FileEntry(
                valid.path(),
                downloads,
                valid.hashes(),
                valid.size(),
                valid.required(),
                valid.environment()
        );
    }

    private static void assertHasError(
            ManifestValidationResult result,
            ManifestErrorCode code,
            String pointer
    ) {
        assertTrue(
                result.errors().stream().anyMatch(error -> error.code() == code && error.pointer().equals(pointer)),
                () -> "Expected " + code + " at " + pointer + ", got " + result.errors()
        );
    }
}
