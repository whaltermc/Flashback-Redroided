// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.mixin;

import com.moulberry.flashback.Flashback;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallbackI;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Native dialogs, chained: SDL first, then tinyfd, then a hardcoded path.
// SDL is the platform dialog Flashback itself uses; when its backend is
// missing or errors, the blocking tinyfd call runs off-thread, and when that
// has no backend either, exports still land in the game directory instead of
// failing. A user-cancelled SDL dialog completes with null and never
// cascades: cancelling must stay a cancel.
@Mixin(targets = "com.moulberry.flashback.utils.AsyncFileDialogs")
public class FileDialogMixin {

    private static final ExecutorService DIALOG_WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "FbaDialogs");
        worker.setDaemon(true);
        return worker;
    });

    // One dialog at a time, mirroring vanilla hasDialog. Also keeps the SDL
    // callback reachable until it fires so native code never calls freed memory.
    private static volatile CompletableFuture<String> ongoingDialog;
    private static volatile SDL_DialogFileCallbackI heldSdlCallback;

    private static File flashbackRedroided$getDefaultExportDir() {
        File dir = new File(
                Minecraft.getInstance().gameDirectory,
                "flashback/exports"
        );

        if (!dir.exists() && !dir.mkdirs()) {
            Flashback.LOGGER.warn(
                    "Could not create default export directory: {}",
                    dir.getAbsolutePath()
            );
        }

        return dir;
    }

    private static String flashbackRedroided$filter(CharSequence in) {
        return flashbackRedroided$filterLT20(
                in.toString()
                        .replace("'", "")
                        .replace("\"", "")
                        .replace("$", "")
                        .replace("`", "")
        );
    }

    private static String flashbackRedroided$filterLT20(CharSequence in) {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);

            if (c >= 32 || c == '\n') {
                builder.append(c);
            }
        }

        return builder.toString();
    }

    // Claims the dialog slot, or null when another dialog is already open.
    private static CompletableFuture<String> flashbackRedroided$claimDialog() {
        synchronized (FileDialogMixin.class) {
            if (ongoingDialog != null
                    || com.moulberry.flashback.utils.AsyncFileDialogs.hasDialog()) {
                return null;
            }
            var future = new CompletableFuture<String>();
            ongoingDialog = future;
            return future;
        }
    }

    // Releases the slot, drops the held callback, and completes the future.
    private static void flashbackRedroided$settleDialog(CompletableFuture<String> future, String value) {
        synchronized (FileDialogMixin.class) {
            heldSdlCallback = null;
            if (ongoingDialog == future) {
                ongoingDialog = null;
            }
        }
        future.complete(value);
    }

    // One-entry SDL filter built like vanilla: description plus ;-joined patterns.
    private static final class SdlFilter {
        final SDL_DialogFileFilter.Buffer buffer;
        final ByteBuffer name;
        final ByteBuffer pattern;

        static SdlFilter of(String filterDescription, String[] filters) {
            var joined = new StringBuilder();
            if (filters != null) {
                for (String entry : filters) {
                    if (entry == null || entry.isEmpty()) {
                        continue;
                    }
                    if (joined.length() > 0) {
                        joined.append(';');
                    }
                    joined.append(entry);
                }
            }
            ByteBuffer name = MemoryUtil.memUTF8(
                    filterDescription != null ? filterDescription : "", true);
            ByteBuffer pattern = MemoryUtil.memUTF8(joined.toString(), true);
            SDL_DialogFileFilter.Buffer buffer = SDL_DialogFileFilter.calloc(1);
            buffer.get(0).name(name).pattern(pattern);
            return new SdlFilter(buffer, name, pattern);
        }

        private SdlFilter(SDL_DialogFileFilter.Buffer buffer, ByteBuffer name, ByteBuffer pattern) {
            this.buffer = buffer;
            this.name = name;
            this.pattern = pattern;
        }

        void free() {
            try {
                buffer.free();
            } catch (Throwable ignored) {
            }
            try {
                MemoryUtil.memFree(name);
            } catch (Throwable ignored) {
            }
            try {
                MemoryUtil.memFree(pattern);
            } catch (Throwable ignored) {
            }
        }
    }

    // Bare extensions for tinyfd: "mp4", ".png" and "*.replay" all become "mp4".
    private static String[] flashbackRedroided$tinyExtensions(String[] filters) {
        var cleaned = new ArrayList<String>();
        if (filters != null) {
            for (String entry : filters) {
                if (entry == null) {
                    continue;
                }
                for (String part : entry.split("[,;\\s]+")) {
                    String ext = part.strip();
                    while (ext.startsWith("*")) {
                        ext = ext.substring(1);
                    }
                    while (ext.startsWith(".")) {
                        ext = ext.substring(1);
                    }
                    if (!ext.isEmpty()) {
                        cleaned.add(ext);
                    }
                }
            }
        }
        return cleaned.toArray(new String[0]);
    }

    // tinyfd wants "*.ext" entries in a pointer buffer; empty means no filtering.
    private static PointerBuffer flashbackRedroided$tinyPatterns(MemoryStack stack, String[] filters) {
        String[] exts = flashbackRedroided$tinyExtensions(filters);
        if (exts.length == 0) {
            return null;
        }
        PointerBuffer pointers = stack.mallocPointer(exts.length);
        for (String ext : exts) {
            pointers.put(stack.UTF8("*." + ext));
        }
        pointers.flip();
        return pointers;
    }

    @Overwrite
    public static CompletableFuture<String> saveFileDialog(
            String defaultPath,
            String defaultName,
            String filterDescription,
            String... filters
    ) {
        var future = flashbackRedroided$claimDialog();
        if (future == null) {
            return CompletableFuture.completedFuture(null);
        }

        String defaultLocation =
                flashbackRedroided$filter(defaultPath + "/" + defaultName);

        String autoExtension =
                filters.length == 1 ? filters[0] : null;

        SdlFilter sdlFilter;
        try {
            sdlFilter = SdlFilter.of(filterDescription, filters);
        } catch (Throwable t) {
            Flashback.LOGGER.debug("SDL filter alloc failed, trying tinyfd", t);
            flashbackRedroided$tinySaveThenFallback(future, defaultName, filterDescription, filters, autoExtension);
            return future;
        }

        long window;
        try {
            window = Minecraft.getInstance().getWindow().handle();
        } catch (Throwable t) {
            sdlFilter.free();
            Flashback.LOGGER.debug("No game window for SDL dialog, trying tinyfd", t);
            flashbackRedroided$tinySaveThenFallback(future, defaultName, filterDescription, filters, autoExtension);
            return future;
        }

        SDL_DialogFileCallbackI callback = (userdata, filelist, filter) -> {
            sdlFilter.free();
            if (filelist == 0) {
                String error;
                try {
                    error = SDLError.SDL_GetError();
                } catch (Throwable ignored) {
                    error = "";
                }
                if (error == null || error.isEmpty()) {
                    flashbackRedroided$settleDialog(future, null);
                    return;
                }
                Flashback.LOGGER.warn("SDL save dialog failed ({}); trying tinyfd", error);
                flashbackRedroided$tinySaveThenFallback(future, defaultName, filterDescription, filters, autoExtension);
                return;
            }
            String path = MemoryUtil.memUTF8Safe(MemoryUtil.memGetAddress(filelist));
            if (path != null && autoExtension != null && path.indexOf('.') < 0) {
                path = path + "." + autoExtension;
            }
            flashbackRedroided$settleDialog(future, path);
        };
        heldSdlCallback = callback;
        try {
            SDLError.SDL_ClearError();
            SDLDialog.SDL_ShowSaveFileDialog(callback, 0L, window, sdlFilter.buffer, defaultLocation);
        } catch (Throwable t) {
            sdlFilter.free();
            Flashback.LOGGER.debug("SDL save dialog unavailable, trying tinyfd", t);
            flashbackRedroided$tinySaveThenFallback(future, defaultName, filterDescription, filters, autoExtension);
        }
        return future;
    }

    // Blocking tinyfd save plus the hardcoded game-dir fallback, always off-thread.
    private static void flashbackRedroided$tinySaveThenFallback(
            CompletableFuture<String> future,
            String defaultName,
            String filterDescription,
            String[] filters,
            String autoExtension
    ) {
        DIALOG_WORKER.execute(() -> {
            try {
                String path = flashbackRedroided$tinySave(defaultName, filterDescription, filters);
                if (path != null && !path.isEmpty()) {
                    if (autoExtension != null && path.indexOf('.') < 0) {
                        path = path + "." + autoExtension;
                    }
                    flashbackRedroided$settleDialog(future, path);
                    return;
                }
            } catch (Throwable ignored) {
            }
            try {
                String name = defaultName;
                if (name != null && autoExtension != null && name.indexOf('.') < 0) {
                    name = name + "." + autoExtension;
                }
                File fallback = new File(
                        flashbackRedroided$getDefaultExportDir(),
                        name != null ? name : "export"
                );
                Flashback.LOGGER.warn(
                        "Using default export path: {}",
                        fallback.getAbsolutePath()
                );
                flashbackRedroided$settleDialog(future, fallback.getAbsolutePath());
            } catch (Throwable t) {
                flashbackRedroided$settleDialog(future, null);
            }
        });
    }

    private static String flashbackRedroided$tinySave(
            String defaultName,
            String filterDescription,
            String[] filters
    ) {
        try (var stack = MemoryStack.stackPush()) {
            return TinyFileDialogs.tinyfd_saveFileDialog(
                    filterDescription != null ? filterDescription : "Save file",
                    defaultName != null && !defaultName.isEmpty() ? defaultName : "export",
                    flashbackRedroided$tinyPatterns(stack, filters),
                    filterDescription);
        }
    }

    @Overwrite
    public static CompletableFuture<String> openFileDialog(
            String defaultPath,
            String filterDescription,
            String... filters
    ) {
        var future = flashbackRedroided$claimDialog();
        if (future == null) {
            return CompletableFuture.completedFuture(null);
        }

        String defaultLocation =
                flashbackRedroided$filter(defaultPath);

        SdlFilter sdlFilter;
        try {
            sdlFilter = SdlFilter.of(filterDescription, filters);
        } catch (Throwable t) {
            Flashback.LOGGER.debug("SDL filter alloc failed, trying tinyfd", t);
            flashbackRedroided$tinyOpen(future, defaultPath, filterDescription, filters);
            return future;
        }

        long window;
        try {
            window = Minecraft.getInstance().getWindow().handle();
        } catch (Throwable t) {
            sdlFilter.free();
            Flashback.LOGGER.debug("No game window for SDL dialog, trying tinyfd", t);
            flashbackRedroided$tinyOpen(future, defaultPath, filterDescription, filters);
            return future;
        }

        SDL_DialogFileCallbackI callback = (userdata, filelist, filter) -> {
            sdlFilter.free();
            if (filelist == 0) {
                String error;
                try {
                    error = SDLError.SDL_GetError();
                } catch (Throwable ignored) {
                    error = "";
                }
                if (error == null || error.isEmpty()) {
                    flashbackRedroided$settleDialog(future, null);
                    return;
                }
                Flashback.LOGGER.warn("SDL open dialog failed ({}); trying tinyfd", error);
                flashbackRedroided$tinyOpen(future, defaultPath, filterDescription, filters);
                return;
            }
            flashbackRedroided$settleDialog(
                    future, MemoryUtil.memUTF8Safe(MemoryUtil.memGetAddress(filelist)));
        };
        heldSdlCallback = callback;
        try {
            SDLError.SDL_ClearError();
            SDLDialog.SDL_ShowOpenFileDialog(callback, 0L, window, sdlFilter.buffer, defaultLocation, false);
        } catch (Throwable t) {
            sdlFilter.free();
            Flashback.LOGGER.debug("SDL open dialog unavailable, trying tinyfd", t);
            flashbackRedroided$tinyOpen(future, defaultPath, filterDescription, filters);
        }
        return future;
    }

    // Blocking tinyfd open, always off-thread. Null when it has no backend:
    // unlike exports, an import has no sensible hardcoded path to invent.
    private static void flashbackRedroided$tinyOpen(
            CompletableFuture<String> future,
            String defaultPath,
            String filterDescription,
            String[] filters
    ) {
        DIALOG_WORKER.execute(() -> {
            try (var stack = MemoryStack.stackPush()) {
                String path = TinyFileDialogs.tinyfd_openFileDialog(
                        filterDescription != null ? filterDescription : "Open file",
                        defaultPath != null ? defaultPath : "",
                        flashbackRedroided$tinyPatterns(stack, filters),
                        filterDescription,
                        false);
                flashbackRedroided$settleDialog(
                        future, path != null && !path.isEmpty() ? path : null);
            } catch (Throwable t) {
                flashbackRedroided$settleDialog(future, null);
            }
        });
    }

    @Overwrite
    public static CompletableFuture<String> openFolderDialog(
            String defaultPath
    ) {
        var future = flashbackRedroided$claimDialog();
        if (future == null) {
            return CompletableFuture.completedFuture(null);
        }

        String defaultLocation =
                flashbackRedroided$filter(defaultPath);

        long window;
        try {
            window = Minecraft.getInstance().getWindow().handle();
        } catch (Throwable t) {
            Flashback.LOGGER.debug("No game window for SDL dialog, trying tinyfd", t);
            flashbackRedroided$tinyFolder(future, defaultPath);
            return future;
        }

        SDL_DialogFileCallbackI callback = (userdata, filelist, filter) -> {
            if (filelist == 0) {
                String error;
                try {
                    error = SDLError.SDL_GetError();
                } catch (Throwable ignored) {
                    error = "";
                }
                if (error == null || error.isEmpty()) {
                    flashbackRedroided$settleDialog(future, null);
                    return;
                }
                Flashback.LOGGER.warn("SDL folder dialog failed ({}); trying tinyfd", error);
                flashbackRedroided$tinyFolder(future, defaultPath);
                return;
            }
            flashbackRedroided$settleDialog(
                    future, MemoryUtil.memUTF8Safe(MemoryUtil.memGetAddress(filelist)));
        };
        heldSdlCallback = callback;
        try {
            SDLError.SDL_ClearError();
            SDLDialog.SDL_ShowOpenFolderDialog(callback, 0L, window, defaultLocation, false);
        } catch (Throwable t) {
            Flashback.LOGGER.debug("SDL folder dialog unavailable, trying tinyfd", t);
            flashbackRedroided$tinyFolder(future, defaultPath);
        }
        return future;
    }

    // Blocking tinyfd folder pick plus the hardcoded game-dir fallback, always off-thread.
    private static void flashbackRedroided$tinyFolder(
            CompletableFuture<String> future,
            String defaultPath
    ) {
        DIALOG_WORKER.execute(() -> {
            try {
                String path = TinyFileDialogs.tinyfd_selectFolderDialog(
                        "Select folder",
                        defaultPath != null ? defaultPath : "");
                if (path != null && !path.isEmpty()) {
                    flashbackRedroided$settleDialog(future, path);
                    return;
                }
            } catch (Throwable ignored) {
            }
            try {
                File fallback = flashbackRedroided$getDefaultExportDir();
                Flashback.LOGGER.warn(
                        "Using default export folder: {}",
                        fallback.getAbsolutePath()
                );
                flashbackRedroided$settleDialog(future, fallback.getAbsolutePath());
            } catch (Throwable t) {
                flashbackRedroided$settleDialog(future, null);
            }
        });
    }
}
