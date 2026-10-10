// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.utils;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

// Decides at startup whether the folder hook replaces Flashback's dialogs.
// Every gate has to pass: the feature flag, Android ARM64, a supported game
// version and the expected dialog class. Anything failing leaves the vanilla
// dialogs untouched, and the outcome is printed once as "[FBA Files] <status>"
// for bug reports.
public final class FileActionsPatch {
    static final String CLASS_PATH = "com/moulberry/flashback/exporting/AsyncFileDialogs.class";
    // Flashback 0.39.10 for MC 1.21-1.21.11. Pin a hash here once that release's dialog class has been read.
    static final Set<String> CLASS_HASHES = Set.of();
    // Game versions this branch supports.
    static final Set<String> GAME_VERSIONS = Set.of(
            "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
            "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11");

    private static volatile boolean selected;
    private static volatile String status = "NOT_EVALUATED";

    private FileActionsPatch() {}

    public static boolean selected() { return selected; }
    public static String status() { return status; }

    // Whether this device looks like Android ARM64. Public so dialog hooks
    // outside this package can route mobile devices to the folders even when
    // startup selection did not run.
    public static boolean android() {
        return onAndroidArm64();
    }

    // Entry point, called from pre-launch. Records the outcome and never throws.
    public static void select() {
        selected = false;
        String outcome;
        try {
            outcome = evaluate();
        } catch (Exception e) {
            outcome = "SELECTION_ERROR_" + e.getClass().getSimpleName();
        }
        status = outcome;
        if (outcome.startsWith("SELECTED")) selected = true;
        System.out.println("[FBA Files] " + outcome);
    }

    private static String evaluate() throws Exception {
        if (!Boolean.parseBoolean(System.getProperty("fba.fileActions", "true"))) return "DISABLED";
        if (!onAndroidArm64()) return "NOT_ANDROID_ARM64";
        var loader = FabricLoader.getInstance();
        var game = loader.getModContainer("minecraft");
        if (game.isEmpty()) return "UNLISTED_GAME";
        String gameVersion = game.get().getMetadata().getVersion().getFriendlyString();
        if (!GAME_VERSIONS.contains(gameVersion)) return "UNLISTED_GAME";
        var flashback = loader.getModContainer("flashback");
        if (flashback.isEmpty()) return "FLASHBACK_ABSENT";
        var dialog = flashback.get().findPath(CLASS_PATH);
        if (dialog.isEmpty()) return "UNRESEARCHED_FILE_DIALOGS";
        if (!dialogMatches(Files.readAllBytes(dialog.get()))) return "UNRESEARCHED_FILE_DIALOGS";
        try {
            FileActionsBridge.configure(resolveRoot(System.getProperty("fba.fileDir"), loader.getGameDir()));
        } catch (Exception e) {
            return "FOLDER_ERROR";
        }
        return CLASS_HASHES.isEmpty() ? "SELECTED_UNPINNED" : "SELECTED";
    }

    // True for the inspected dialog bytes; any non-empty bytes while no hash is pinned yet.
    static boolean dialogMatches(byte[] dialogClass) throws Exception {
        if (dialogClass == null || dialogClass.length == 0) return false;
        if (CLASS_HASHES.isEmpty()) return true;
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dialogClass));
        return CLASS_HASHES.contains(hash);
    }

    // Import/export root: the -Dfba.fileDir override when absolute, else the game directory.
    static Path resolveRoot(String override, Path gameDir) {
        if (override != null && !override.isBlank()) {
            Path custom = Path.of(override.strip());
            if (custom.isAbsolute()) return custom;
            System.out.println("[FBA Files] Relative -Dfba.fileDir ignored: " + override);
        }
        return gameDir.resolve("flashback-android");
    }

    // Android ARM64 probe kept free of client-only types, since this class
    // lives in the main source set: ARM64 CPU plus an Android system marker
    // or a known mobile-launcher variable.
    static boolean onAndroidArm64() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!arch.contains("aarch64") && !arch.contains("arm64")) return false;
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
}
