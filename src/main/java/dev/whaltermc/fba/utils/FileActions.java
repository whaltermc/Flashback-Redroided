// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Folder-based replacement for desktop file dialogs: two fixed folders and no UI.
 * Import picks a matching file out of {@code import/}; export reserves a
 * never-overwritten path inside {@code export/}.
 */
final class FileActions {
    private static final int MAX_NAME_BYTES = 200;
    private final Path importDir;
    private final Path exportDir;

    FileActions(Path root) {
        this.importDir = root.toAbsolutePath().normalize().resolve("import");
        this.exportDir = root.toAbsolutePath().normalize().resolve("export");
    }

    Path importDir() { return importDir; }
    Path exportDir() { return exportDir; }

    void createFolders() throws IOException {
        Files.createDirectories(importDir);
        Files.createDirectories(exportDir);
    }

    /** Splits filters like "mp4", ".png", "*.replay" or "mp4,mov" into lowercase bare extensions. */
    static List<String> normalizeExtensions(String... raw) {
        var result = new LinkedHashSet<String>();
        if (raw == null) return List.of();
        for (String entry : raw) {
            if (entry == null) continue;
            for (String part : entry.split("[,;\\s]+")) {
                String ext = part.strip();
                while (ext.startsWith("*")) ext = ext.substring(1);
                while (ext.startsWith(".")) ext = ext.substring(1);
                ext = ext.toLowerCase(Locale.ROOT);
                if (!ext.isEmpty()) result.add(ext);
            }
        }
        return List.copyOf(result);
    }

    /** True when the name ends with one of the extensions. An empty list matches every name. */
    static boolean hasExtension(String fileName, List<String> extensions) {
        if (extensions.isEmpty()) return true;
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.length() > ext.length() + 1 && lower.endsWith("." + ext)) return true;
        }
        return false;
    }

    /** Newest acceptable file in the import folder. */
    Optional<Path> newestImport(List<String> extensions) throws IOException {
        return newest(extensions, Map.of());
    }

    /** Snapshot of the import folder as name to "modifiedMillis:size" for every acceptable file. */
    Map<String, String> importSnapshot(List<String> extensions) throws IOException {
        var result = new HashMap<String, String>();
        if (!Files.isDirectory(importDir)) return result;
        try (Stream<Path> files = Files.list(importDir)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                String name = path.getFileName().toString();
                if (acceptable(path, name, extensions)) result.put(name, stamp(path));
            }
        }
        return result;
    }

    /** Newest acceptable file that is new or changed (time or size) since the snapshot. */
    Optional<Path> newestChanged(List<String> extensions, Map<String, String> before) throws IOException {
        return newest(extensions, before);
    }

    private static boolean acceptable(Path path, String name, List<String> extensions) {
        return !name.startsWith(".") && hasExtension(name, extensions)
                && Files.isRegularFile(path) && Files.isReadable(path);
    }

    private static String stamp(Path path) throws IOException {
        return Files.getLastModifiedTime(path).toMillis() + ":" + Files.size(path);
    }

    private Optional<Path> newest(List<String> extensions, Map<String, String> unchanged) throws IOException {
        if (!Files.isDirectory(importDir)) return Optional.empty();
        Path best = null;
        FileTime bestTime = null;
        try (Stream<Path> files = Files.list(importDir)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                String name = path.getFileName().toString();
                if (!acceptable(path, name, extensions)) continue;
                if (stamp(path).equals(unchanged.get(name))) continue;
                FileTime time = Files.getLastModifiedTime(path);
                int order = best == null ? 1 : time.compareTo(bestTime);
                if (order == 0) order = name.compareTo(best.getFileName().toString());
                if (order > 0) { best = path; bestTime = time; }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Keeps one path segment and drops reserved characters, leading dots, trailing dots/spaces and over-long names. */
    static String sanitizeName(String requested) {
        String name = requested == null ? "" : requested;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        var out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || "<>:\"|?*".indexOf(c) >= 0) continue;
            out.append(c);
        }
        String cleaned = out.toString().strip();
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1).stripLeading();
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        while (cleaned.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (!cleaned.isEmpty() && Character.isHighSurrogate(cleaned.charAt(cleaned.length() - 1))) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    /** Reserves a path in the export folder, appending " (n)" instead of overwriting: "a.mp4" becomes "a (1).mp4". */
    Path allocateExport(String requestedName, List<String> extensions) throws IOException {
        String name = sanitizeName(requestedName);
        if (name.isEmpty()) name = "export";
        if (!extensions.isEmpty() && !hasExtension(name, extensions)) name = name + "." + extensions.get(0);
        Files.createDirectories(exportDir);
        Path target = exportDir.resolve(name).normalize();
        if (!exportDir.equals(target.getParent())) throw new IOException("Export name escaped the export folder");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            String suffix = suffixOf(name, extensions);
            String stem = name.substring(0, name.length() - suffix.length());
            target = null;
            for (int i = 1; i < 10_000 && target == null; i++) {
                Path candidate = exportDir.resolve(stem + " (" + i + ")" + suffix);
                if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) target = candidate;
            }
            if (target == null) throw new IOException("Too many exports named " + name);
        }
        return target;
    }

    private static String suffixOf(String name, List<String> extensions) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.endsWith("." + ext)) return name.substring(name.length() - ext.length() - 1);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }
}
