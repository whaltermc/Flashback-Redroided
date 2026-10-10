// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.utils;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Decides at startup whether the file-actions hook takes over Flashback's
 * dialogs. It only selects when every check passes: enabled flag, Android
 * ARM64, listed game version and the inspected dialog class. Anything else
 * leaves the vanilla dialogs alone.
 */
public final class FileActionsPatch {
    static final String CLASS_PATH = "com/moulberry/flashback/utils/AsyncFileDialogs.class";
    /** Flashback 0.43.6 for MC 26.3. Pin a hash here once that release's dialog class has been read. */
    static final Set<String> CLASS_HASHES = Set.of();
    /** Minecraft versions this branch supports. */
    static final Set<String> GAME_VERSIONS = Set.of("26.3");
    private static volatile boolean selected;
    private static volatile String status = "NOT_EVALUATED";

    private FileActionsPatch() {}
    public static boolean selected() { return selected; }
    public static String status() { return status; }

    /** True when the platform checks pass and the dialog bytes are the inspected ones (any bytes while unpinned). */
    static boolean matches(boolean android, boolean listedGame, byte[] dialogClass) throws Exception {
        if (!android || !listedGame) return false;
        if (CLASS_HASHES.isEmpty()) return dialogClass != null && dialogClass.length > 0;
        return CLASS_HASHES.contains(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dialogClass)));
    }

    /** Import/export root: the {@code -Dfba.fileDir} override when absolute, else the game directory. */
    static Path root(String override, Path gameDir) {
        if (override != null && !override.isBlank()) {
            Path custom = Path.of(override.strip());
            if (custom.isAbsolute()) return custom;
            System.out.println("[FBA Files] IGNORED_RELATIVE_FILE_DIR " + override);
        }
        return gameDir.resolve("flashback-android");
    }

    /** Android ARM64 probe that stays out of client-only classes (this lives in the main source set). */
    static boolean isAndroidArm64() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
        if (!arm64) return false;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("android")) return true;
        if (Files.exists(Path.of("/system/build.prop"))) return true;
        for (String variable : new String[]{
                "POJAV_ENVIRON", "POJAV_RENDERER", "POJAV_NATIVEDIR", "POJAV_GAME_DIR",
                "FCL_NATIVEDIR", "FCL_RENDERER", "ZALITH_RENDERER", "MG_DIR_PATH",
                "MOBILEGLUES_PATH"}) {
            try {
                if (System.getenv(variable) != null) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** Runs the checks in order and configures the folders on success; never throws. */
    public static void select() {
        selected = false;
        try {
            status = "DISABLED";
            if (!Boolean.parseBoolean(System.getProperty("fba.fileActions", "true"))) return;
            status = "NOT_ANDROID_ARM64";
            if (!isAndroidArm64()) return;
            var loader = FabricLoader.getInstance();
            var game = loader.getModContainer("minecraft");
            status = "UNLISTED_GAME";
            if (game.isEmpty() || !GAME_VERSIONS.contains(game.get().getMetadata().getVersion().getFriendlyString())) return;
            var flashback = loader.getModContainer("flashback");
            status = "FLASHBACK_ABSENT";
            if (flashback.isEmpty()) return;
            status = "UNRESEARCHED_FILE_DIALOGS";
            var wrapper = flashback.get().findPath(CLASS_PATH);
            if (wrapper.isEmpty() || !matches(true, true, Files.readAllBytes(wrapper.get()))) return;
            status = "FOLDER_ERROR";
            FileActionsBridge.configure(root(System.getProperty("fba.fileDir"), loader.getGameDir()));
            status = CLASS_HASHES.isEmpty() ? "SELECTED_UNPINNED" : "SELECTED";
            selected = true;
        } catch (Exception e) {
            status = "SELECTION_ERROR_" + e.getClass().getSimpleName();
        } finally {
            System.out.println("[FBA Files] " + status);
        }
    }
}
