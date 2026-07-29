package org.wodichka.packcontrol.updateformat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.util.Objects;

public final class PackBootstrapJson {
    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    private PackBootstrapJson() {
    }

    public static String toJson(PackBootstrap bootstrap) {
        return GSON.toJson(Objects.requireNonNull(bootstrap, "bootstrap"));
    }

    public static PackBootstrap fromJson(String json) {
        Objects.requireNonNull(json, "json");
        return fromElement(JsonParser.parseString(json));
    }

    public static PackBootstrap fromJson(Reader reader) {
        Objects.requireNonNull(reader, "reader");
        return fromElement(JsonParser.parseReader(reader));
    }

    private static PackBootstrap fromElement(com.google.gson.JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            throw new JsonParseException("PackControl bootstrap root must be a JSON object");
        }
        PackBootstrap bootstrap = GSON.fromJson(element, PackBootstrap.class);
        if (bootstrap == null) {
            throw new JsonParseException("PackControl bootstrap must not be null");
        }
        return bootstrap;
    }
}
