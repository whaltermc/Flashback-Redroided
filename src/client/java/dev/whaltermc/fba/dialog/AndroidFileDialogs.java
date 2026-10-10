// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.dialog;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.Locale;

/**
 * Backs Flashback's {@code AsyncFileDialogs} on Android.
 *
 * <p>The platform picker (SAF / {@code ACTION_OPEN_DOCUMENT}) is not usable here:
 * the game runs on a desktop JVM that has no {@code android.*} classes on its
 * classpath at all, so there is no {@code Intent} to construct -- the same gap
 * that makes FFmpeg's JNI MediaCodec backend fail. What we do have is a real
 * filesystem, so this drives an in-game browser and hands Flashback a plain
 * path, which is exactly what its {@code java.io.File}-based callers expect.
 */
public final class AndroidFileDialogs {

    public static final Logger LOGGER = LoggerFactory.getLogger("flashback_android");

    public enum Mode { SAVE_FILE, OPEN_FILE, OPEN_FOLDER }

    private static final Deque<Request> PENDING = new ArrayDeque<>();
    private static FilePickerScreen activeScreen;

    private AndroidFileDialogs() {
    }

    private record Request(
            Mode mode,
            File startDir,
            String startName,
            List<String> extensions,
            CompletableFuture<String> future
    ) {
    }

    public static CompletableFuture<String> saveFileDialog(
            String defaultPath, String defaultName, String filterDescription, String... filters) {
        return submit(Mode.SAVE_FILE, defaultPath, defaultName, filters);
    }

    public static CompletableFuture<String> openFileDialog(
            String defaultPath, String filterDescription, String... filters) {
        return submit(Mode.OPEN_FILE, defaultPath, null, filters);
    }

    public static CompletableFuture<String> openFolderDialog(String defaultPath) {
        return submit(Mode.OPEN_FOLDER, defaultPath, null, null);
    }

    private static CompletableFuture<String> submit(
            Mode mode, String defaultPath, String defaultName, String[] filters) {
        CompletableFuture<String> future = new CompletableFuture<>();

        File start = resolveStartDir(defaultPath, defaultName, mode);
        synchronized (PENDING) {
            PENDING.add(new Request(mode, start, defaultName, parseExtensions(filters), future));
        }
        LOGGER.info("Queued {} dialog starting at {}", mode, start);
        return future;
    }

    /** Resolves the directory the picker should open in, tolerating bad input. */
    private static File resolveStartDir(String defaultPath, String defaultName, Mode mode) {
        File dir = null;

        if (defaultPath != null && !defaultPath.isBlank()) {
            File f = new File(defaultPath);
            if (f.isDirectory()) {
                dir = f;
            } else if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                dir = f.getParentFile();
            }
        }

        if (dir == null && defaultName != null && !defaultName.isBlank()) {
            File f = new File(defaultName);
            if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                dir = f.getParentFile();
            }
        }

        if (dir == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                dir = new File(mc.gameDirectory, "flashback/exports");
            }
        }

        if (dir == null) {
            dir = new File(System.getProperty("user.home", "/."));
        }

        // Walk up until we land on something listable; a bad start dir must not
        // leave the picker stuck on an unreadable screen.
        File probe = dir;
        for (int i = 0; i < 64 && probe != null; i++) {
            if (probe.isDirectory() && probe.canRead()) {
                return probe;
            }
            probe = probe.getParentFile();
        }
        return new File(".");
    }

    /** Flashback passes filters like "*.mp4" or "mp4,mkv"; keep the extensions only. */
    static List<String> parseExtensions(String[] filters) {
        if (filters == null || filters.length == 0) {
            return List.of();
        }
        StringBuilder sb = new StringBuilder();
        for (String f : filters) {
            if (f != null && !f.isBlank()) {
                sb.append(f).append(',');
            }
        }
        if (sb.length() == 0) {
            return List.of();
        }

        List<String> out = new ArrayList<>();
        for (String part : sb.toString().split(",")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.isEmpty()) {
                continue;
            }
            if (p.startsWith("*.")) {
                p = p.substring(2);
            } else if (p.startsWith(".")) {
                p = p.substring(1);
            }
            // Guard against a filter like "*.tar.gz" producing "tar.gz".
            int dot = p.indexOf('.');
            if (dot > 0) {
                p = p.substring(dot + 1);
            }
            if (!p.isEmpty() && !out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }

    static boolean matchesExtension(String fileName, List<String> extensions) {
        if (extensions == null || extensions.isEmpty()) {
            return true;
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (String ext : extensions) {
            if (lower.endsWith("." + ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Raises the picker on the next tick. Must run on the client thread; the
     * caller (Flashback) may invoke the dialog from its ImGui or screen code.
     */
    public static void tick() {
        Request request;
        synchronized (PENDING) {
            request = PENDING.poll();
        }
        if (request == null) {
            return;
        }

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                request.future().complete(null);
                return;
            }
            FilePickerScreen screen = new FilePickerScreen(
                    request.mode(), request.startDir(), request.startName(),
                    request.extensions(), request.future());
            activeScreen = screen;
            mc.setScreen(screen);
        } catch (Throwable t) {
            LOGGER.warn("Could not open file picker", t);
            request.future().complete(null);
        }
    }

    static void clearActive() {
        activeScreen = null;
    }

    /** Test/diagnostic helper: is a picker currently on screen? */
    public static boolean hasPicker() {
        return activeScreen != null;
    }


}