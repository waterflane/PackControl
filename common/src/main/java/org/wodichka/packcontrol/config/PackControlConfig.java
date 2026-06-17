package org.wodichka.packcontrol.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.wodichka.packcontrol.PackControl;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class PackControlConfig {
    public static final List<String> DEFAULT_INCLUDE_PATTERNS = List.of(
            "mods/**",
            "config/**",
            "kubejs/**",
            "defaultconfigs/**",
            "resourcepacks/**",
            "shaderpacks/**",
            "scripts/**",
            "patchouli_books/**",
            "openloader/**"
    );

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String LEGACY_FILE_NAME = "packcontrol.json";
    private static final String USER_FILE_NAME = "packcontrol-user.json";
    private static final String PACK_FILE_NAME = "packcontrol-pack.json";

    private static PackControlUserConfig USER = PackControlUserConfig.defaults();
    private static PackControlPackConfig PACK = PackControlPackConfig.defaults(USER);
    private static Path configDirectory;
    private static Path gameDirectory;
    private static Path userConfigPath;
    private static Path packConfigPath;

    private PackControlConfig() {
    }

    public static PackControlUserConfig user() {
        return USER;
    }

    public static PackControlPackConfig pack() {
        return PACK;
    }

    public static PackControlConfig get() {
        return legacyView();
    }

    public static Path configDirectory() {
        return configDirectory;
    }

    public static Path gameDirectory() {
        return gameDirectory;
    }

    public static Path userConfigPath() {
        return userConfigPath;
    }

    public static Path packConfigPath() {
        return packConfigPath;
    }

    public static Path loadedPath() {
        return userConfigPath;
    }

    public static void load(Path configDir, Path gameDir) {
        Objects.requireNonNull(configDir, "configDir");
        Objects.requireNonNull(gameDir, "gameDir");
        configDirectory = configDir;
        gameDirectory = gameDir;
        userConfigPath = configDir.resolve(USER_FILE_NAME);
        packConfigPath = gameDir.resolve(PACK_FILE_NAME);

        try {
            Files.createDirectories(configDir);
            Files.createDirectories(gameDir);
            migrateLegacyConfig(configDir.resolve(LEGACY_FILE_NAME));
            USER = readOrCreate(userConfigPath, PackControlUserConfig.defaults(), PackControlUserConfig.class).sanitized();
            PACK = readOrCreate(packConfigPath, PackControlPackConfig.defaults(USER), PackControlPackConfig.class).sanitized(USER);
            save();
        } catch (IOException | RuntimeException exception) {
            USER = PackControlUserConfig.defaults();
            PACK = PackControlPackConfig.defaults(USER);
            PackControl.LOGGER.error("Failed to load PackControl configs. Defaults will be used for this session.", exception);
        }
    }

    public static void save() {
        write(userConfigPath, USER.sanitized());
        write(packConfigPath, PACK.sanitized(USER));
    }

    public static void savePack() {
        write(packConfigPath, PACK.sanitized(USER));
    }

    private static <T> T readOrCreate(Path path, T defaults, Class<T> type) throws IOException {
        if (Files.notExists(path)) {
            write(path, defaults);
            PackControl.LOGGER.info("Created PackControl config at {}", path);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            T loaded = GSON.fromJson(reader, type);
            return loaded == null ? defaults : loaded;
        }
    }

    private static void write(Path path, Object value) {
        if (path == null) {
            return;
        }

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(value, writer);
            }
        } catch (IOException exception) {
            PackControl.LOGGER.error("Failed to save PackControl config to {}", path, exception);
        }
    }

    private static void migrateLegacyConfig(Path legacyPath) {
        if (Files.notExists(legacyPath) || Files.exists(userConfigPath) || Files.exists(packConfigPath)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(legacyPath)) {
            JsonObject legacy = JsonParser.parseReader(reader).getAsJsonObject();
            PackControlUserConfig user = PackControlUserConfig.defaults();
            PackControlPackConfig pack = PackControlPackConfig.defaults(user);

            user.defaultGithubRepository = stringValue(legacy, "githubRepository", user.defaultGithubRepository);
            user.preferredBranch = stringValue(legacy, "githubBranch", user.preferredBranch);
            user.autoUpdate = booleanValue(legacy, "autoUpdate", user.autoUpdate);
            user.checkUpdatesOnStartup = booleanValue(legacy, "checkUpdatesOnStartup", user.checkUpdatesOnStartup);
            user.updateCheckIntervalMinutes = intValue(legacy, "updateCheckIntervalMinutes", user.updateCheckIntervalMinutes);
            user.includeOptionalFiles = booleanValue(legacy, "includeOptionalFiles", user.includeOptionalFiles);
            user.backupBeforeUpdate = booleanValue(legacy, "backupBeforeUpdate", user.backupBeforeUpdate);
            user.verifyAfterUpdate = booleanValue(legacy, "verifyAfterUpdate", user.verifyAfterUpdate);

            pack.targetGithubRepository = user.defaultGithubRepository;
            pack.targetGithubBranch = user.preferredBranch;
            pack.packVersion = stringValue(legacy, "latestKnownVersion", pack.packVersion);
            pack.updateChannel = stringValue(legacy, "updateChannel", pack.updateChannel);
            pack.packwizManifestPath = stringValue(legacy, "packwizManifestPath", pack.packwizManifestPath);
            pack.packwizIndexPath = stringValue(legacy, "packwizIndexPath", pack.packwizIndexPath);

            write(userConfigPath, user.sanitized());
            write(packConfigPath, pack.sanitized(user));
            PackControl.LOGGER.info("Migrated legacy PackControl config from {}", legacyPath);
        } catch (IOException | IllegalStateException exception) {
            PackControl.LOGGER.warn("Could not migrate legacy PackControl config from {}", legacyPath, exception);
        }
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback;
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsBoolean() : fallback;
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : fallback;
    }

    private static PackControlConfig legacyView() {
        return new PackControlConfig();
    }

    public String repositoryDisplay() {
        return PACK.repositoryDisplay();
    }

    public String branchDisplay() {
        return PACK.branchDisplay();
    }

    public String updateModeDisplay() {
        return USER.autoUpdate ? "Auto update enabled" : "Manual updates only";
    }

    public String packwizStatusDisplay() {
        return "Manifest: " + PACK.packwizManifestPath + ", index: " + PACK.packwizIndexPath;
    }

    public String githubRepository() {
        return PACK.targetGithubRepository;
    }

    public String githubBranch() {
        return PACK.targetGithubBranch;
    }

    public boolean autoUpdate() {
        return USER.autoUpdate;
    }

    public static final class PackControlUserConfig {
        public int schemaVersion = 1;
        public String defaultGithubRepository = "waterflane/packcontrol-pack";
        public String preferredBranch = "main";
        public String githubApiBaseUrl = "https://api.github.com";
        public String githubRawBaseUrl = "https://raw.githubusercontent.com";
        public boolean useGitHubReleases = true;
        public boolean allowPrereleaseVersions = false;
        public boolean autoUpdate = false;
        public boolean checkUpdatesOnStartup = true;
        public int updateCheckIntervalMinutes = 60;
        public boolean requireManualConfirmation = true;
        public boolean notifyWhenUpdateAvailable = true;
        public boolean showChangelogAfterUpdate = true;
        public boolean includeOptionalFiles = false;
        public boolean preserveLocalChanges = true;
        public boolean backupBeforeUpdate = true;
        public boolean verifyBeforeLaunch = false;
        public boolean verifyAfterUpdate = true;
        public boolean dryRunUpdates = false;
        public boolean showGitHubPanel = true;
        public boolean showPackwizPanel = true;
        public boolean showAdvancedSettings = false;
        public List<String> defaultIncludePatterns = new ArrayList<>(DEFAULT_INCLUDE_PATTERNS);
        public List<String> defaultExcludePatterns = new ArrayList<>(List.of(
                "pack.toml",
                "index.toml",
                ".gitattributes",
                "packcontrol-pack.json"
        ));

        public static PackControlUserConfig defaults() {
            return new PackControlUserConfig().sanitized();
        }

        public PackControlUserConfig sanitized() {
            schemaVersion = Math.max(1, schemaVersion);
            defaultGithubRepository = clean(defaultGithubRepository, "waterflane/packcontrol-pack");
            preferredBranch = clean(preferredBranch, "main");
            githubApiBaseUrl = clean(githubApiBaseUrl, "https://api.github.com");
            githubRawBaseUrl = clean(githubRawBaseUrl, "https://raw.githubusercontent.com");
            updateCheckIntervalMinutes = Math.max(5, updateCheckIntervalMinutes);
            defaultIncludePatterns = unique(defaultIncludePatterns, DEFAULT_INCLUDE_PATTERNS);
            defaultExcludePatterns = unique(defaultExcludePatterns, List.of("pack.toml", "index.toml", ".gitattributes", "packcontrol-pack.json"));
            return this;
        }
    }

    public static final class PackControlPackConfig {
        public int schemaVersion = 1;
        public String packName = "PackControl Pack";
        public String packVersion = "0.1.0-dev";
        public String packAuthor = "waterflane";
        public String packDescription = "Generated by PackControl using Packwiz-compatible metadata.";
        public String minecraftVersion = "1.21.1";
        public String modLoader = "neoforge";
        public String modLoaderVersion = "21.1.233";
        public String targetGithubRepository = "waterflane/packcontrol-pack";
        public String targetGithubBranch = "main";
        public String updateChannel = "development";
        public String installedVersion = "not-installed";
        public String latestKnownVersion = "0.1.0-dev";
        public String lastUpdateCheck = "never";
        public String packwizManifestPath = "pack.toml";
        public String packwizIndexPath = "index.toml";
        public String packwizHashFormat = "sha256";
        public List<String> includePatterns = new ArrayList<>();
        public List<String> excludePatterns = new ArrayList<>();
        public String selectionVersion = "0.1.0-dev";
        public List<String> expandedTreePaths = new ArrayList<>(List.of("mods", "config", "kubejs"));
        public String lastSavedPreset = "none";
        public String lastGeneratedAt = "never";
        public int lastGeneratedFileCount = 0;
        public int lastSkippedFileCount = 0;
        public String lastGenerationStatus = "Never generated";

        public static PackControlPackConfig defaults(PackControlUserConfig user) {
            PackControlPackConfig config = new PackControlPackConfig();
            config.targetGithubRepository = user.defaultGithubRepository;
            config.targetGithubBranch = user.preferredBranch;
            config.includePatterns = new ArrayList<>(user.defaultIncludePatterns);
            config.excludePatterns = new ArrayList<>(user.defaultExcludePatterns);
            return config.sanitized(user);
        }

        public PackControlPackConfig sanitized(PackControlUserConfig user) {
            schemaVersion = Math.max(1, schemaVersion);
            packName = clean(packName, "PackControl Pack");
            packVersion = clean(packVersion, "0.1.0-dev");
            selectionVersion = clean(selectionVersion, packVersion);
            packAuthor = clean(packAuthor, "waterflane");
            packDescription = clean(packDescription, "Generated by PackControl using Packwiz-compatible metadata.");
            minecraftVersion = clean(minecraftVersion, "1.21.1");
            modLoader = clean(modLoader, "neoforge").toLowerCase();
            modLoaderVersion = clean(modLoaderVersion, "21.1.233");
            targetGithubRepository = clean(targetGithubRepository, user.defaultGithubRepository);
            targetGithubBranch = clean(targetGithubBranch, user.preferredBranch);
            updateChannel = clean(updateChannel, "development");
            installedVersion = clean(installedVersion, "not-installed");
            latestKnownVersion = clean(latestKnownVersion, packVersion);
            lastUpdateCheck = clean(lastUpdateCheck, "never");
            packwizManifestPath = clean(packwizManifestPath, "pack.toml");
            packwizIndexPath = clean(packwizIndexPath, "index.toml");
            packwizHashFormat = "sha256";
            includePatterns = unique(includePatterns, user.defaultIncludePatterns);
            excludePatterns = unique(excludePatterns, user.defaultExcludePatterns);
            expandedTreePaths = unique(expandedTreePaths, List.of("mods", "config", "kubejs"));
            lastSavedPreset = clean(lastSavedPreset, "none");
            lastGeneratedAt = clean(lastGeneratedAt, "never");
            lastGenerationStatus = clean(lastGenerationStatus, "Never generated");
            return this;
        }

        public String repositoryDisplay() {
            return clean(targetGithubRepository, "Not configured");
        }

        public String branchDisplay() {
            return clean(targetGithubBranch, "main");
        }
    }

    private static List<String> unique(List<String> values, List<String> fallback) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        List<String> source = values == null || values.isEmpty() ? fallback : values;
        for (String value : source) {
            String cleaned = value == null ? "" : value.trim().replace('\\', '/');
            if (!cleaned.isEmpty() && !cleaned.startsWith("/") && !cleaned.contains("..")) {
                result.add(cleaned);
            }
        }
        return new ArrayList<>(result);
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

