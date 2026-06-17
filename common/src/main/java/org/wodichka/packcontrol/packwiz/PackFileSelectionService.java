package org.wodichka.packcontrol.packwiz;

import org.wodichka.packcontrol.config.PackControlConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class PackFileSelectionService {
    private static final List<String> ALWAYS_SKIP = List.of(
            "pack.toml",
            "index.toml",
            ".gitattributes",
            "packcontrol-pack.json",
            "packcontrol-user.json"
    );

    private PackFileSelectionService() {
    }

    public static PackFileScanResult scan() {
        Path root = PackControlConfig.gameDirectory();
        PackControlConfig.PackControlPackConfig config = PackControlConfig.pack();
        if (root == null) {
            return new PackFileScanResult(List.of(), List.of(), List.of("Game directory is not available"));
        }

        List<Pattern> includePatterns = compile(config.includePatterns);
        List<Pattern> excludePatterns = compile(config.excludePatterns);
        List<PackFileEntry> included = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> missing = missingRoots(root, config.includePatterns);

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String relative = relativePath(root, path);
                if (relative == null) {
                    return;
                }

                if (isAlwaysSkipped(relative)) {
                    skipped.add(relative + " (generated/control file)");
                    return;
                }

                if (!matches(includePatterns, relative)) {
                    return;
                }

                if (matches(excludePatterns, relative)) {
                    skipped.add(relative + " (excluded)");
                    return;
                }

                try {
                    included.add(new PackFileEntry(relative, Files.size(path), path));
                } catch (IOException exception) {
                    skipped.add(relative + " (unreadable)");
                }
            });
        } catch (IOException exception) {
            skipped.add("Scan failed: " + exception.getMessage());
        }

        included.sort(Comparator.comparing(PackFileEntry::relativePath));
        skipped.sort(String::compareToIgnoreCase);
        missing.sort(String::compareToIgnoreCase);
        return new PackFileScanResult(List.copyOf(included), List.copyOf(skipped), List.copyOf(missing));
    }

    public static boolean isSelectedPreset(String pattern) {
        return PackControlConfig.pack().includePatterns.contains(pattern);
    }

    public static void setPresetEnabled(String pattern, boolean enabled) {
        PackControlConfig.PackControlPackConfig config = PackControlConfig.pack();
        if (enabled && !config.includePatterns.contains(pattern)) {
            config.includePatterns.add(pattern);
        } else if (!enabled) {
            config.includePatterns.remove(pattern);
        }
        PackControlConfig.savePack();
    }

    public static void addIncludePattern(String pattern) {
        String cleaned = cleanPattern(pattern);
        if (!cleaned.isEmpty() && !PackControlConfig.pack().includePatterns.contains(cleaned)) {
            PackControlConfig.pack().includePatterns.add(cleaned);
            PackControlConfig.savePack();
        }
    }

    public static void excludePath(String relativePath) {
        String cleaned = cleanPattern(relativePath);
        if (!cleaned.isEmpty() && !PackControlConfig.pack().excludePatterns.contains(cleaned)) {
            PackControlConfig.pack().excludePatterns.add(cleaned);
            PackControlConfig.savePack();
        }
    }

    private static List<String> missingRoots(Path root, List<String> patterns) {
        List<String> missing = new ArrayList<>();
        for (String pattern : patterns) {
            String folder = pattern.replace('\\', '/');
            int slash = folder.indexOf('/');
            if (slash <= 0) {
                continue;
            }
            folder = folder.substring(0, slash);
            if (!folder.contains("*") && Files.notExists(root.resolve(folder))) {
                missing.add(folder + "/");
            }
        }
        return missing;
    }

    private static boolean isAlwaysSkipped(String relative) {
        return ALWAYS_SKIP.contains(relative) || relative.startsWith(".git/") || relative.startsWith("logs/") || relative.startsWith("crash-reports/");
    }

    private static String relativePath(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            return null;
        }

        String relative = normalizedRoot.relativize(normalizedPath).toString().replace('\\', '/');
        if (relative.isBlank() || relative.startsWith("../") || relative.contains("/../") || relative.equals("..")) {
            return null;
        }
        return relative;
    }

    private static List<Pattern> compile(List<String> globs) {
        List<Pattern> patterns = new ArrayList<>();
        for (String glob : globs) {
            String cleaned = cleanPattern(glob);
            if (!cleaned.isEmpty()) {
                patterns.add(Pattern.compile(globToRegex(cleaned)));
            }
        }
        return patterns;
    }

    private static boolean matches(List<Pattern> patterns, String relative) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(relative).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String cleanPattern(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim().replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.contains("..")) {
            return "";
        }
        return cleaned;
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append("[^/]");
            } else if (".()[]{}+$^|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        regex.append('$');
        return regex.toString();
    }

    public record PackFileEntry(String relativePath, long size, Path absolutePath) {
    }

    public record PackFileScanResult(List<PackFileEntry> includedFiles, List<String> skippedFiles, List<String> missingFolders) {
        public int includedCount() {
            return includedFiles.size();
        }

        public int skippedCount() {
            return skippedFiles.size();
        }
    }
}