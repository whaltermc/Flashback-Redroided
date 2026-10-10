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

/**
 * Folder-based substitute for desktop file dialogs.
 *
 * <p>Imports resolve to a file inside {@code import/}; exports reserve a
 * fresh, never-overwritten path inside {@code export/}. There is no UI at
 * all: the two folders are the interface.
 */
final class FileActions {
    /** Cap on file-name length so exports stay within filesystem limits. */
    static final int MAX_NAME_BYTES = 200;

    private final Path inboxDir;
    private final Path outboxDir;

    FileActions(Path root) {
        Path base = root.toAbsolutePath().normalize();
        this.inboxDir = base.resolve("import");
        this.outboxDir = base.resolve("export");
    }

    Path inbox() { return inboxDir; }
    Path outbox() { return outboxDir; }

    void createFolders() throws IOException {
        Files.createDirectories(inboxDir);
        Files.createDirectories(outboxDir);
    }

    /**
     * Turns dialog filters into plain lowercase extensions. {@code "mp4"},
     * {@code ".png"}, {@code "*.replay"} and {@code "mp4,mov"} are all
     * accepted, and comma/space/semicolon separated lists are split apart.
     */
    static List<String> normalizeExtensions(String... raw) {
        var cleaned = new LinkedHashSet<String>();
        if (raw != null) {
            for (String group : raw) {
                if (group == null) continue;
                for (String token : group.split("[,;\\s]+")) {
                    String ext = stripMarkers(token.strip()).toLowerCase(Locale.ROOT);
                    if (!ext.isEmpty()) cleaned.add(ext);
                }
            }
        }
        return List.copyOf(cleaned);
    }

    private static String stripMarkers(String token) {
        int start = 0;
        while (start < token.length() && (token.charAt(start) == '*' || token.charAt(start) == '.')) start++;
        return token.substring(start);
    }

    /**
     * Whether a file name carries one of the extensions. An empty list
     * accepts everything; a bare {@code ".ext"} with no stem never matches.
     */
    static boolean hasExtension(String fileName, List<String> extensions) {
        if (extensions.isEmpty()) return true;
        String name = fileName.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            String dotted = "." + ext;
            if (name.endsWith(dotted) && name.length() > dotted.length()) return true;
        }
        return false;
    }

    /** Newest acceptable file in the import folder, if any. */
    Optional<Path> newestImport(List<String> extensions) throws IOException {
        return findNewest(extensions, Map.of());
    }

    /**
     * Captures the import folder as file name to {@code "modifiedMillis:size"},
     * so later polls can tell new or replaced files apart from untouched ones.
     */
    Map<String, String> importSnapshot(List<String> extensions) throws IOException {
        var snap = new HashMap<String, String>();
        if (!Files.isDirectory(inboxDir)) return snap;
        try (var listed = Files.list(inboxDir)) {
            for (Path path : listed.toList()) {
                String name = fileNameOf(path);
                if (isCandidate(path, name, extensions)) snap.put(name, fingerprint(path));
            }
        }
        return snap;
    }

    /** Newest acceptable file that is new or changed since the given snapshot. */
    Optional<Path> newestChanged(List<String> extensions, Map<String, String> before) throws IOException {
        return findNewest(extensions, before);
    }

    private static String fileNameOf(Path path) {
        return path.getFileName().toString();
    }

    private static boolean isCandidate(Path path, String name, List<String> extensions) {
        if (name.startsWith(".")) return false;
        if (!hasExtension(name, extensions)) return false;
        return Files.isRegularFile(path) && Files.isReadable(path);
    }

    private static String fingerprint(Path path) throws IOException {
        return Files.getLastModifiedTime(path).toMillis() + ":" + Files.size(path);
    }

    private Optional<Path> findNewest(List<String> extensions, Map<String, String> unchanged) throws IOException {
        if (!Files.isDirectory(inboxDir)) return Optional.empty();
        Path best = null;
        FileTime bestModified = null;
        String bestName = null;
        try (var listed = Files.list(inboxDir)) {
            for (Path path : listed.toList()) {
                String name = fileNameOf(path);
                if (!isCandidate(path, name, extensions)) continue;
                if (fingerprint(path).equals(unchanged.get(name))) continue;
                FileTime modified = Files.getLastModifiedTime(path);
                if (best == null || modified.compareTo(bestModified) > 0
                        || (modified.compareTo(bestModified) == 0 && name.compareTo(bestName) > 0)) {
                    best = path;
                    bestModified = modified;
                    bestName = name;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Reduces a requested name to a single safe path segment: directory parts,
     * control characters, reserved symbols, leading dots and trailing
     * dots/spaces are removed, and the result is capped at 200 UTF-8 bytes
     * without splitting a surrogate pair.
     */
    static String sanitizeName(String requested) {
        String name = requested == null ? "" : requested;
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) name = name.substring(cut + 1);
        var kept = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) continue;
            if (c == '<' || c == '>' || c == ':' || c == '"' || c == '|' || c == '?' || c == '*') continue;
            kept.append(c);
        }
        String safe = kept.toString().strip();
        while (safe.startsWith(".")) safe = safe.substring(1).stripLeading();
        while (safe.endsWith(".") || safe.endsWith(" ")) safe = safe.substring(0, safe.length() - 1);
        while (safe.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
            safe = safe.substring(0, safe.length() - 1);
        }
        if (!safe.isEmpty() && Character.isHighSurrogate(safe.charAt(safe.length() - 1))) {
            safe = safe.substring(0, safe.length() - 1);
        }
        return safe;
    }

    /**
     * Reserves a path in the export folder for a new file. Name collisions
     * gain a counter instead of overwriting: {@code "clip.mp4"} becomes
     * {@code "clip (1).mp4"}.
     */
    Path allocateExport(String requestedName, List<String> extensions) throws IOException {
        String name = sanitizeName(requestedName);
        if (name.isEmpty()) name = "export";
        if (!extensions.isEmpty() && !hasExtension(name, extensions)) name += "." + extensions.get(0);
        Files.createDirectories(outboxDir);
        Path target = outboxDir.resolve(name).normalize();
        if (!outboxDir.equals(target.getParent())) throw new IOException("Export name escaped the export folder");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return target;
        String suffix = extensionSuffix(name, extensions);
        String stem = name.substring(0, name.length() - suffix.length());
        for (int n = 1; n < 10_000; n++) {
            Path free = outboxDir.resolve(stem + " (" + n + ")" + suffix);
            if (!Files.exists(free, LinkOption.NOFOLLOW_LINKS)) return free;
        }
        throw new IOException("Too many exports named " + name);
    }

    private static String extensionSuffix(String name, List<String> extensions) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.endsWith("." + ext)) return name.substring(name.length() - ext.length() - 1);
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }
}
