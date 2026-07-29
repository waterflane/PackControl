package org.wodichka.packcontrol.updateformat;

import com.google.gson.JsonParseException;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestJsonTest {
    @Test
    void serializesAndDeserializesManifestWithGson() {
        PackControlManifest expected = ManifestTestFixtures.validManifest();

        String json = ManifestJson.toJson(expected);
        PackControlManifest actual = ManifestJson.fromJson(json);

        assertEquals(expected, actual);
        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("\"required\""));
        assertTrue(json.contains("\"unsupported\""));
    }

    @Test
    void readsManifestFromReader() {
        String json = ManifestJson.toJson(ManifestTestFixtures.validManifest());

        PackControlManifest actual = ManifestJson.fromJson(new StringReader(json));

        assertEquals(ManifestTestFixtures.validManifest(), actual);
    }

    @Test
    void rejectsNonObjectRoot() {
        assertThrows(JsonParseException.class, () -> ManifestJson.fromJson("[]"));
        assertThrows(JsonParseException.class, () -> ManifestJson.fromJson("null"));
    }

    @Test
    void malformedElementsReachStructuredValidator() {
        String json = ManifestJson.toJson(ManifestTestFixtures.validManifest())
                .replace("\"files\": [", "\"files\": [null,");

        PackControlManifest manifest = ManifestJson.fromJson(json);
        ManifestValidationResult result = new ManifestValidator().validate(manifest);

        assertTrue(result.errors().stream().anyMatch(error ->
                error.code() == ManifestErrorCode.REQUIRED_VALUE
                        && error.pointer().equals("/files/0")
        ));
    }
}
