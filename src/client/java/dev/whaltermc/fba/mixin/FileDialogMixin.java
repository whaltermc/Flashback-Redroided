// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.mixin;

import dev.whaltermc.fba.AndroidInput;
import dev.whaltermc.fba.utils.FileActionsBridge;
import dev.whaltermc.fba.utils.FileActionsPatch;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

@Mixin(
        targets = "com.moulberry.flashback.exporting.AsyncFileDialogs",
        remap = false,
        priority = 1000
)
public class FileDialogMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("flashback_android");

    private static final String DOWNLOADS_PATH = "/storage/emulated/0/Downloads";

    /** Cached probe result; -1 unknown, 1 usable, 0 not. */
    private static int downloadsUsable = -1;

    /**
     * Shared Downloads is the friendliest destination -- the file shows up in
     * any file manager and most gallery apps. It is not reliably writable,
     * though: from Android 10 scoped storage blocks direct writes outside the
     * app's own directories unless the launcher was granted broad storage
     * access, and some launchers run the game in a sandbox that cannot reach it
     * at all. So probe it for real once instead of assuming, and fall back to
     * the game directory when it is not usable.
     */
    private static synchronized boolean flashbackRedroided$canUseDownloads() {
        if (downloadsUsable >= 0) {
            return downloadsUsable == 1;
        }

        boolean usable = false;
        File dir = new File(DOWNLOADS_PATH);
        if (dir.isDirectory() && dir.canWrite()) {
            File probe = null;
            try {
                probe = File.createTempFile("fba-write-probe", ".tmp", dir);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(probe)) {
                    out.write(0);
                }
                usable = true;
            } catch (Throwable t) {
                LOGGER.debug("Downloads is not writable", t);
            } finally {
                if (probe != null) {
                    try {
                        probe.delete();
                    } catch (Throwable ignored) {
                        // best effort
                    }
                }
            }
        }

        downloadsUsable = usable ? 1 : 0;
        LOGGER.info("Export directory: {}",
                usable ? DOWNLOADS_PATH : "<game dir>/flashback/exports");
        return usable;
    }

    private static File flashbackRedroided$getExportDir() {
        if (AndroidInput.isMobile() && flashbackRedroided$canUseDownloads()) {
            return new File(DOWNLOADS_PATH);
        }

        File dir = new File(
                Minecraft.getInstance().gameDirectory,
                "flashback/exports"
        );
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.debug("Could not create export directory: {}", dir.getAbsolutePath());
        }
        return dir;
    }

    private static String flashbackRedroided$addExtension(
            String name,
            String... filters
    ) {
        name = flashbackRedroided$sanitizeFileName(name);
        if (name == null || name.isEmpty()) {
            name = "export";
        }

        if (filters != null && filters.length > 0
                && filters[0] != null && !filters[0].isEmpty()
                && name.lastIndexOf('.') <= 0) {
            String extension = filters[0].split(",", 2)[0].trim()
                    .replaceFirst("^\\*?\\.", "")
                    .replaceAll("[^A-Za-z0-9_-]", "");
            if (!extension.isEmpty()) {
                name += "." + extension;
            }
        }

        return name;
    }

    private static String flashbackRedroided$sanitizeFileName(String name) {
        if (name == null) {
            return "";
        }

        String cleaned = name.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        cleaned = cleaned.replaceAll("[<>:\"|?*\\x00-\\x1f]", "_").trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        return cleaned;
    }

    private static File flashbackRedroided$uniqueOutput(File directory, String name) {
        Path root = directory.toPath().toAbsolutePath().normalize();
        Path candidate = root.resolve(name).normalize();
        if (!candidate.startsWith(root)) {
            candidate = root.resolve("export");
        }

        if (Files.exists(candidate)) {
            String fileName = candidate.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;
            String extension = dot > 0 ? fileName.substring(dot) : "";
            for (int suffix = 1; suffix < 10_000 && Files.exists(candidate); suffix++) {
                candidate = root.resolve(base + " (" + suffix + ")" + extension);
            }
        }
        return candidate.toFile();
    }

    @Inject(
            method = "saveFileDialog(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void flashbackRedroided$saveFileDialog(
            String defaultPath,
            String defaultName,
            String filterDescription,
            String[] filters,
            CallbackInfoReturnable<CompletableFuture<String>> cir
    ) {
        if (FileActionsPatch.selected()) {
            cir.setReturnValue(FileActionsBridge.save(defaultName, filters));
            return;
        }
        File exportDir = flashbackRedroided$getExportDir();
        String fileName = flashbackRedroided$addExtension(defaultName, filters);
        File output = flashbackRedroided$uniqueOutput(exportDir, fileName);
        cir.setReturnValue(CompletableFuture.completedFuture(output.getAbsolutePath()));
    }

    @Inject(
            method = "openFolderDialog(Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void flashbackRedroided$openFolderDialog(
            String defaultPath,
            CallbackInfoReturnable<CompletableFuture<String>> cir
    ) {
        if (FileActionsPatch.selected()) {
            cir.setReturnValue(FileActionsBridge.folder());
            return;
        }
        File exportDir = flashbackRedroided$getExportDir();
        cir.setReturnValue(CompletableFuture.completedFuture(exportDir.getAbsolutePath()));
    }

    @Inject(
            method = "openFileDialog(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)Ljava/util/concurrent/CompletableFuture;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void flashbackRedroided$disableNativeOpenDialogOnMobile(
            String defaultPath,
            String filterDescription,
            String[] filters,
            CallbackInfoReturnable<CompletableFuture<String>> cir
    ) {
        if (FileActionsPatch.selected()) {
            cir.setReturnValue(FileActionsBridge.open(filters));
            return;
        }
        if (AndroidInput.isMobile()) {
            cir.setReturnValue(CompletableFuture.completedFuture(null));
        }
    }
}
