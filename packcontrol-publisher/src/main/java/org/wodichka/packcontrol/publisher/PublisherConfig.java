package org.wodichka.packcontrol.publisher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record PublisherConfig(
        String packId,
        String name,
        String version,
        String releaseId,
        String summary,
        String minecraftVersion,
        String loader,
        String loaderVersion,
        String minimumPackControlVersion,
        String releaseBaseUrl,
        List<String> optionalMods,
        Map<String, GitHubModMapping> githubMods
) {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Set<String> SECRET_NAMES = Set.of(
            "token", "secret", "password", "authorization", "apikey", "api_key"
    );

    public PublisherConfig {
        optionalMods = optionalMods == null ? List.of() : List.copyOf(optionalMods);
        githubMods = githubMods == null ? Map.of() : Map.copyOf(githubMods);
    }

    public static PublisherConfig read(Path path) throws IOException, PublisherException {
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement root = JsonParser.parseReader(reader);
            rejectSecrets(root, "$");
            PublisherConfig config = GSON.fromJson(root, PublisherConfig.class);
            if (config == null) {
                throw new PublisherException("Publisher configuration must be a JSON object");
            }
            return config;
        } catch (JsonParseException exception) {
            throw new PublisherException("Invalid publisher configuration: " + exception.getMessage(), exception);
        }
    }

    private static void rejectSecrets(JsonElement element, String path) throws PublisherException {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                String normalized = entry.getKey().toLowerCase(Locale.ROOT);
                if (SECRET_NAMES.contains(normalized)
                        || normalized.contains("token")
                        || normalized.contains("secret")
                        || normalized.contains("password")
                        || normalized.contains("authorization")) {
                    throw new PublisherException("Secrets are not allowed in publisher configuration: "
                            + path + "." + entry.getKey());
                }
                rejectSecrets(entry.getValue(), path + "." + entry.getKey());
            }
        } else if (element.isJsonArray()) {
            for (int index = 0; index < element.getAsJsonArray().size(); index++) {
                rejectSecrets(element.getAsJsonArray().get(index), path + "[" + index + "]");
            }
        }
    }

    public record GitHubModMapping(
            String owner,
            String repository,
            String tag,
            String assetName,
            boolean allowThirdPartyJar
    ) {
    }
}
