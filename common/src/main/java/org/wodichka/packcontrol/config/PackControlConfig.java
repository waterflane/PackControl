package org.wodichka.packcontrol.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.wodichka.packcontrol.PackControl;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class PackControlConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "packcontrol.json";

    private static PackControlConfig INSTANCE = defaults();
    private static Path loadedPath;

    public int schemaVersion = 1;

    public String githubRepository = "waterflane/packcontrol-pack";
    public String githubBranch = "main";
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

    public String updateChannel = "development";
    public String installedVersion = "not-installed";
    public String latestKnownVersion = "0.1.0-dev";
    public String lastUpdateCheck = "never";

    public String packwizManifestPath = "pack.toml";
    public String packwizIndexPath = "index.toml";
    public String packwizCacheDirectory = "packcontrol/cache";
    public boolean includeOptionalFiles = false;
    public boolean preserveLocalChanges = true;
    public boolean backupBeforeUpdate = true;
    public boolean verifyBeforeLaunch = false;
    public boolean verifyAfterUpdate = true;
    public boolean dryRunUpdates = false;

    public boolean showGitHubPanel = true;
    public boolean showPackwizPanel = true;
    public boolean showAdvancedSettings = false;

    private PackControlConfig() {
    }

    public static PackControlConfig get() {
        return INSTANCE;
    }

    public static Path loadedPath() {
        return loadedPath;
    }

    public static void load(Path configDirectory) {
        Objects.requireNonNull(configDirectory, "configDirectory");
        loadedPath = configDirectory.resolve(FILE_NAME);

        try {
            Files.createDirectories(configDirectory);
            if (Files.notExists(loadedPath)) {
                INSTANCE = defaults();
                save();
                PackControl.LOGGER.info("Created default PackControl config at {}", loadedPath);
                return;
            }

            try (Reader reader = Files.newBufferedReader(loadedPath)) {
                PackControlConfig loaded = GSON.fromJson(reader, PackControlConfig.class);
                INSTANCE = loaded == null ? defaults() : loaded.sanitized();
            }
        } catch (IOException | RuntimeException exception) {
            INSTANCE = defaults();
            PackControl.LOGGER.error("Failed to load PackControl config from {}. Defaults will be used for this session.", loadedPath, exception);
        }
    }

    public static void save() {
        if (loadedPath == null) {
            return;
        }

        try {
            Files.createDirectories(loadedPath.getParent());
            try (Writer writer = Files.newBufferedWriter(loadedPath)) {
                GSON.toJson(INSTANCE.sanitized(), writer);
            }
        } catch (IOException exception) {
            PackControl.LOGGER.error("Failed to save PackControl config to {}", loadedPath, exception);
        }
    }

    public String repositoryDisplay() {
        return githubRepository == null || githubRepository.isBlank() ? "Not configured" : githubRepository;
    }

    public String branchDisplay() {
        return githubBranch == null || githubBranch.isBlank() ? "main" : githubBranch;
    }

    public String updateModeDisplay() {
        return autoUpdate ? "Auto update enabled" : "Manual updates only";
    }

    public String packwizStatusDisplay() {
        return "Manifest: " + packwizManifestPath + ", index: " + packwizIndexPath;
    }

    private PackControlConfig sanitized() {
        PackControlConfig config = this;
        config.schemaVersion = Math.max(1, config.schemaVersion);
        config.githubRepository = clean(config.githubRepository, "waterflane/packcontrol-pack");
        config.githubBranch = clean(config.githubBranch, "main");
        config.githubApiBaseUrl = clean(config.githubApiBaseUrl, "https://api.github.com");
        config.githubRawBaseUrl = clean(config.githubRawBaseUrl, "https://raw.githubusercontent.com");
        config.updateChannel = clean(config.updateChannel, "development");
        config.installedVersion = clean(config.installedVersion, "not-installed");
        config.latestKnownVersion = clean(config.latestKnownVersion, "0.1.0-dev");
        config.lastUpdateCheck = clean(config.lastUpdateCheck, "never");
        config.packwizManifestPath = clean(config.packwizManifestPath, "pack.toml");
        config.packwizIndexPath = clean(config.packwizIndexPath, "index.toml");
        config.packwizCacheDirectory = clean(config.packwizCacheDirectory, "packcontrol/cache");
        config.updateCheckIntervalMinutes = Math.max(5, config.updateCheckIntervalMinutes);
        return config;
    }

    private static PackControlConfig defaults() {
        return new PackControlConfig().sanitized();
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}