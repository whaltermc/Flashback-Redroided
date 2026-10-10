// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.mixin;

import dev.whaltermc.fba.AndroidInput;
import dev.whaltermc.fba.dialog.AndroidFileDialogs;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

    /**
     * Flashback's own in-flight dialog marker. We cancel the real dialog at
     * HEAD, so without mirroring our future here {@code hasDialog()} would
     * always report false and callers could stack duplicate pickers.
     */
    @Shadow
    private static CompletableFuture<String> currentSaveOrOpenFileDialog;

    private static CompletableFuture<String> flashbackRedroided$track(
            CompletableFuture<String> future) {
        currentSaveOrOpenFileDialog = future;
        future.whenComplete((result, error) -> currentSaveOrOpenFileDialog = null);
        return future;
    }

    private static File flashbackRedroided$getExportDir() {
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
            method = "saveFileDialog",
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
        if (AndroidInput.isMobile()) {
            cir.setReturnValue(flashbackRedroided$track(AndroidFileDialogs.saveFileDialog(
                    defaultPath, defaultName, filterDescription, filters)));
            return;
        }

        File exportDir = flashbackRedroided$getExportDir();
        String fileName = flashbackRedroided$addExtension(defaultName, filters);
        File output = flashbackRedroided$uniqueOutput(exportDir, fileName);
        cir.setReturnValue(CompletableFuture.completedFuture(output.getAbsolutePath()));
    }

    @Inject(
            method = "openFolderDialog",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void flashbackRedroided$openFolderDialog(
            String defaultPath,
            CallbackInfoReturnable<CompletableFuture<String>> cir
    ) {
        if (AndroidInput.isMobile()) {
            cir.setReturnValue(flashbackRedroided$track(
                    AndroidFileDialogs.openFolderDialog(defaultPath)));
            return;
        }

        File exportDir = flashbackRedroided$getExportDir();
        cir.setReturnValue(CompletableFuture.completedFuture(exportDir.getAbsolutePath()));
    }

    @Inject(
            method = "openFileDialog",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void flashbackRedroided$openFileDialog(
            String defaultPath,
            String filterDescription,
            String[] filters,
            CallbackInfoReturnable<CompletableFuture<String>> cir
    ) {
        if (AndroidInput.isMobile()) {
            cir.setReturnValue(flashbackRedroided$track(AndroidFileDialogs.openFileDialog(
                    defaultPath, filterDescription, filters)));
        }
    }
}
