package org.wodichka.packcontrol.updateformat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.Objects;

/**
 * Gson codec for the versioned manifest model. Semantic validation is kept in
 * {@link ManifestValidator} so malformed values can be reported structurally.
 */
public final class ManifestJson {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private ManifestJson() {
    }

    public static String toJson(PackControlManifest manifest) {
        return GSON.toJson(Objects.requireNonNull(manifest, "manifest"));
    }

    public static PackControlManifest fromJson(String json) {
        Objects.requireNonNull(json, "json");
        return fromElement(JsonParser.parseString(json));
    }

    public static PackControlManifest fromJson(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        return fromElement(JsonParser.parseReader(reader));
    }

    private static PackControlManifest fromElement(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new JsonParseException("PackControl manifest root must be a JSON object");
        }
        PackControlManifest manifest = GSON.fromJson(element, PackControlManifest.class);
        if (manifest == null) {
            throw new JsonParseException("PackControl manifest must not be null");
        }
        return manifest;
    }
}
