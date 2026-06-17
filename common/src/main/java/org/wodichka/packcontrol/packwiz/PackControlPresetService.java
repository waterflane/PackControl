package org.wodichka.packcontrol.packwiz;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.wodichka.packcontrol.PackControl;
import org.wodichka.packcontrol.config.PackControlConfig;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PackControlPresetService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DIRECTORY_NAME = "packcontrol-presets";

    private PackControlPresetService() {
    }

    public static Path presetDirectory() {
        Path configDirectory = PackControlConfig.configDirectory();
        return configDirectory == null ? Path.of(DIRECTORY_NAME) : configDirectory.resolve(DIRECTORY_NAME);
    }

    public static PresetSaveResult saveCurrent() {
        PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
        PackSelectionPreset preset = PackSelectionPreset.from(pack);
        String fileName = safeName(pack.packName + "-" + pack.packVersion) + ".json";
        Path path = presetDirectory().resolve(fileName);
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(preset, writer);
            }
            pack.lastSavedPreset = path.getFileName().toString();
            pack.selectionVersion = pack.packVersion;
            PackControlConfig.savePack();
            return new PresetSaveResult(true, "Saved preset: " + path.getFileName());
        } catch (IOException exception) {
            PackControl.LOGGER.error("Failed to save PackControl preset to {}", path, exception);
            return new PresetSaveResult(false, "Preset save failed: " + exception.getMessage());
        }
    }

    public static PresetSaveResult loadFirst() {
        String lastSaved = PackControlConfig.pack().lastSavedPreset;
        if (lastSaved != null && !lastSaved.isBlank() && !lastSaved.equals("none")) {
            PresetSaveResult result = load(lastSaved);
            if (result.success()) {
                return result;
            }
        }

        List<PresetSummary> presets = listPresets();
        if (presets.isEmpty()) {
            return new PresetSaveResult(false, "No presets saved yet");
        }
        return load(presets.get(0).fileName());
    }

    public static PresetSaveResult load(String fileName) {
        String safeFile = safeName(fileName.replace(".json", "")) + ".json";
        Path path = presetDirectory().resolve(safeFile);
        try (Reader reader = Files.newBufferedReader(path)) {
            PackSelectionPreset preset = GSON.fromJson(reader, PackSelectionPreset.class);
            if (preset == null) {
                return new PresetSaveResult(false, "Preset is empty");
            }
            PackControlConfig.PackControlPackConfig pack = PackControlConfig.pack();
            pack.includePatterns = sanitize(preset.includePatterns, PackControlConfig.user().defaultIncludePatterns);
            pack.excludePatterns = sanitize(preset.excludePatterns, PackControlConfig.user().defaultExcludePatterns);
            pack.expandedTreePaths = sanitize(preset.expandedTreePaths, List.of("mods", "config", "kubejs"));
            pack.selectionVersion = pack.packVersion;
            pack.lastSavedPreset = safeFile;
            PackControlConfig.savePack();
            return new PresetSaveResult(true, "Loaded preset: " + safeFile);
        } catch (IOException exception) {
            PackControl.LOGGER.error("Failed to load PackControl preset from {}", path, exception);
            return new PresetSaveResult(false, "Preset load failed: " + exception.getMessage());
        }
    }

    public static List<PresetSummary> listPresets() {
        Path directory = presetDirectory();
        if (Files.notExists(directory)) {
            return List.of();
        }
        List<PresetSummary> presets = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            paths.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .forEach(path -> presets.add(new PresetSummary(path.getFileName().toString())));
        } catch (IOException exception) {
            PackControl.LOGGER.warn("Could not list PackControl presets from {}", directory, exception);
        }
        return List.copyOf(presets);
    }

    private static List<String> sanitize(List<String> values, List<String> fallback) {
        List<String> source = values == null || values.isEmpty() ? fallback : values;
        List<String> result = new ArrayList<>();
        for (String value : source) {
            String cleaned = value == null ? "" : value.trim().replace('\\', '/');
            if (!cleaned.isEmpty() && !cleaned.startsWith("/") && !cleaned.contains("..") && !result.contains(cleaned)) {
                result.add(cleaned);
            }
        }
        return result;
    }

    private static String safeName(String value) {
        String cleaned = value == null ? "preset" : value.trim().toLowerCase().replaceAll("[^a-z0-9._-]+", "-");
        while (cleaned.startsWith("-") || cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        while (cleaned.endsWith("-") || cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned.isBlank() ? "preset" : cleaned;
    }

    public static final class PackSelectionPreset {
        public int schemaVersion = 1;
        public String name = "PackControl preset";
        public String packVersion = "0.1.0-dev";
        public String repository = "";
        public String branch = "main";
        public String savedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        public List<String> includePatterns = new ArrayList<>();
        public List<String> excludePatterns = new ArrayList<>();
        public List<String> expandedTreePaths = new ArrayList<>();

        public static PackSelectionPreset from(PackControlConfig.PackControlPackConfig pack) {
            PackSelectionPreset preset = new PackSelectionPreset();
            preset.name = pack.packName + " " + pack.packVersion;
            preset.packVersion = pack.packVersion;
            preset.repository = pack.targetGithubRepository;
            preset.branch = pack.targetGithubBranch;
            preset.includePatterns = new ArrayList<>(pack.includePatterns);
            preset.excludePatterns = new ArrayList<>(pack.excludePatterns);
            preset.expandedTreePaths = new ArrayList<>(pack.expandedTreePaths);
            return preset;
        }
    }

    public record PresetSummary(String fileName) {
    }

    public record PresetSaveResult(boolean success, String message) {
    }
}
