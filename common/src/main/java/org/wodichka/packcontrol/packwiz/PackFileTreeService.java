package org.wodichka.packcontrol.packwiz;

import org.wodichka.packcontrol.config.PackControlConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class PackFileTreeService {
    private static final int MAX_TREE_NODES = 8000;

    private PackFileTreeService() {
    }

    public static PackFileTreeView view(int offset, int pageSize) {
        Path root = PackControlConfig.gameDirectory();
        if (root == null || Files.notExists(root)) {
            return new PackFileTreeView(List.of(), 1, List.of("Game directory is not available"), 0);
        }

        List<TreeRow> allRows = new ArrayList<>();
        Set<String> roots = discoverRoots(root);
        int[] visited = new int[]{0};

        for (String rootName : roots) {
            Path child = root.resolve(rootName).normalize();
            if (!child.startsWith(root) || Files.notExists(child)) {
                continue;
            }
            appendNode(root, child, 0, allRows, visited);
            if (visited[0] >= MAX_TREE_NODES) {
                break;
            }
        }

        int maxOffset = Math.max(0, allRows.size() - pageSize);
        int safeOffset = Math.max(0, Math.min(offset, maxOffset));
        int to = Math.min(allRows.size(), safeOffset + pageSize);
        return new PackFileTreeView(List.copyOf(allRows.subList(safeOffset, to)), maxOffset + 1, missingOptionalFolders(root), allRows.size());
    }

    public static void toggleExpanded(String relativePath) {
        String cleaned = clean(relativePath);
        if (cleaned.isEmpty()) {
            return;
        }

        PackControlConfig.PackControlPackConfig config = PackControlConfig.pack();
        if (config.expandedTreePaths.contains(cleaned)) {
            config.expandedTreePaths.remove(cleaned);
        } else {
            config.expandedTreePaths.add(cleaned);
        }
        config.selectionVersion = config.packVersion;
        PackControlConfig.savePack();
    }

    public static void setSelected(String relativePath, boolean directory, boolean selected) {
        String cleaned = clean(relativePath);
        if (cleaned.isEmpty()) {
            return;
        }

        String pattern = directory ? cleaned + "/**" : cleaned;
        PackControlConfig.PackControlPackConfig config = PackControlConfig.pack();
        if (selected) {
            removePattern(config.excludePatterns, pattern);
            removePattern(config.excludePatterns, cleaned);
            if (!containsPattern(config.includePatterns, pattern)) {
                config.includePatterns.add(pattern);
            }
        } else {
            removePattern(config.includePatterns, pattern);
            if (!containsPattern(config.excludePatterns, pattern)) {
                config.excludePatterns.add(pattern);
            }
        }
        config.selectionVersion = config.packVersion;
        PackControlConfig.savePack();
    }

    public static boolean isSelected(String relativePath, boolean directory) {
        String cleaned = clean(relativePath);
        if (cleaned.isEmpty()) {
            return false;
        }

        String probe = directory ? cleaned + "/__packcontrol_folder_probe__" : cleaned;
        PackControlConfig.PackControlPackConfig config = PackControlConfig.pack();
        return matches(config.includePatterns, probe) && !matches(config.excludePatterns, probe) && !matches(config.excludePatterns, cleaned);
    }

    private static void appendNode(Path root, Path path, int depth, List<TreeRow> rows, int[] visited) {
        if (visited[0] >= MAX_TREE_NODES) {
            return;
        }

        String relative = relativePath(root, path);
        if (relative == null || shouldHide(relative)) {
            return;
        }

        boolean directory = Files.isDirectory(path);
        boolean expanded = directory && PackControlConfig.pack().expandedTreePaths.contains(relative);
        int childCount = directory ? childCount(path) : 0;
        long size = directory ? 0L : size(path);
        SelectionInfo selection = selectionInfo(root, path, directory);
        rows.add(new TreeRow(relative, name(path), directory, depth, expanded, selection.selected(), selection.partial(), size, childCount));
        visited[0]++;

        if (!directory || !expanded) {
            return;
        }

        try (Stream<Path> children = Files.list(path)) {
            children.sorted(Comparator.comparing((Path child) -> !Files.isDirectory(child)).thenComparing(child -> child.getFileName().toString().toLowerCase()))
                    .forEach(child -> appendNode(root, child, depth + 1, rows, visited));
        } catch (IOException ignored) {
            rows.add(new TreeRow(relative + "/unreadable", "unreadable", false, depth + 1, false, false, false, 0L, 0));
        }
    }

    private static SelectionInfo selectionInfo(Path root, Path path, boolean directory) {
        if (!directory) {
            String relative = relativePath(root, path);
            boolean selected = relative != null && isSelected(relative, false);
            return new SelectionInfo(selected, false);
        }

        int[] counts = new int[]{0, 0};
        try (Stream<Path> descendants = Files.walk(path)) {
            descendants.filter(Files::isRegularFile).forEach(file -> {
                String relative = relativePath(root, file);
                if (relative == null || shouldHide(relative)) {
                    return;
                }
                counts[0]++;
                if (isSelected(relative, false)) {
                    counts[1]++;
                }
            });
        } catch (IOException ignored) {
        }

        if (counts[0] == 0) {
            String relative = relativePath(root, path);
            return new SelectionInfo(relative != null && isSelected(relative, true), false);
        }
        if (counts[1] == counts[0]) {
            return new SelectionInfo(true, false);
        }
        if (counts[1] > 0) {
            return new SelectionInfo(false, true);
        }
        return new SelectionInfo(false, false);
    }

    private static Set<String> discoverRoots(Path root) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (String pattern : PackControlConfig.DEFAULT_INCLUDE_PATTERNS) {
            addRoot(roots, pattern);
        }
        for (String pattern : PackControlConfig.pack().includePatterns) {
            addRoot(roots, pattern);
        }

        try (Stream<Path> children = Files.list(root)) {
            children.sorted(Comparator.comparing((Path child) -> !Files.isDirectory(child)).thenComparing(child -> child.getFileName().toString().toLowerCase()))
                    .forEach(child -> {
                        String relative = relativePath(root, child);
                        if (relative != null && !shouldHide(relative)) {
                            roots.add(relative);
                        }
                    });
        } catch (IOException ignored) {
        }
        return roots;
    }

    private static List<String> missingOptionalFolders(Path root) {
        List<String> missing = new ArrayList<>();
        for (String pattern : PackControlConfig.DEFAULT_INCLUDE_PATTERNS) {
            String folder = clean(pattern).replace("/**", "");
            int slash = folder.indexOf('/');
            folder = slash >= 0 ? folder.substring(0, slash) : folder;
            if (!folder.isBlank() && !folder.contains("*") && Files.notExists(root.resolve(folder))) {
                missing.add(folder + "/");
            }
        }
        missing.sort(String::compareToIgnoreCase);
        return List.copyOf(missing);
    }

    private static void addRoot(Set<String> roots, String pattern) {
        String cleaned = clean(pattern).replace("/**", "");
        int slash = cleaned.indexOf('/');
        String root = slash >= 0 ? cleaned.substring(0, slash) : cleaned;
        if (!root.isBlank() && !root.contains("*")) {
            roots.add(root);
        }
    }

    private static int childCount(Path path) {
        try (Stream<Path> children = Files.list(path)) {
            return (int) children.filter(child -> {
                Path parent = path.getParent();
                String relative = parent == null ? child.getFileName().toString() : relativePath(parent, child);
                return !shouldHide(relative);
            }).count();
        } catch (IOException exception) {
            return 0;
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return 0L;
        }
    }

    private static String name(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : fileName.toString();
    }

    private static boolean shouldHide(String relative) {
        if (relative == null || relative.isBlank()) {
            return true;
        }
        return relative.equals("pack.toml")
                || relative.equals("index.toml")
                || relative.equals(".gitattributes")
                || relative.equals("packcontrol-pack.json")
                || relative.equals("packcontrol-user.json")
                || relative.equals("config/packcontrol-presets")
                || relative.startsWith("config/packcontrol-presets/")
                || relative.startsWith(".git/")
                || relative.equals(".git")
                || relative.startsWith("logs/")
                || relative.equals("logs")
                || relative.startsWith("crash-reports/")
                || relative.equals("crash-reports");
    }

    private static boolean matches(List<String> globs, String relative) {
        for (String glob : globs) {
            String cleaned = clean(glob);
            if (!cleaned.isEmpty() && Pattern.compile(globToRegex(cleaned)).matcher(relative).matches()) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPattern(List<String> patterns, String pattern) {
        return patterns.stream().anyMatch(value -> clean(value).equals(pattern));
    }

    private static void removePattern(List<String> patterns, String pattern) {
        patterns.removeIf(value -> clean(value).equals(pattern));
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim().replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.contains("..")) {
            return "";
        }
        while (cleaned.endsWith("/") && cleaned.length() > 1) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    private static String relativePath(Path root, Path path) {
        if (root == null || path == null) {
            return null;
        }
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

    public record TreeRow(String relativePath, String name, boolean directory, int depth, boolean expanded, boolean selected, boolean partial, long size, int childCount) {
    }

    public record PackFileTreeView(List<TreeRow> rows, int totalPages, List<String> missingFolders, int totalRows) {
    }

    private record SelectionInfo(boolean selected, boolean partial) {
    }
}