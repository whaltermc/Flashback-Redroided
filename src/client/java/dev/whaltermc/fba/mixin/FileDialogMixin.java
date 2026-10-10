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

    // Shows an SDL dialog; the callback and tinyfd/fallback legs differ per dialog.
    private interface SdlShower {
        void show(SDL_DialogFileCallbackI callback, long window) throws Throwable;
    }

    // Post-processes an SDL-picked path; null in, null out when there is nothing to fix.
    private interface PathFix {
        String fix(String path);
    }

    // Blocking tinyfd call; null or empty when it has nothing to offer.
    private interface TinyAttempt {
        String run() throws Throwable;
    }

    // Last-resort hardcoded path; null when there is none (imports).
    private interface HardFallback {
        String run() throws Throwable;
    }

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

    // Appends the single-filter extension when the picked name has none.
    private static String flashbackRedroided$withExtension(String path, String autoExtension) {
        if (path != null && autoExtension != null && path.indexOf('.') < 0) {
            return path + "." + autoExtension;
        }
        return path;
    }

    // Current SDL error, or empty when the dialog was simply cancelled.
    private static String flashbackRedroided$sdlError() {
        try {
            String error = SDLError.SDL_GetError();
            return error != null ? error : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    // Shared SDL attempt used by all three dialogs. Shows the dialog on the
    // caller thread like vanilla; on backend failure it frees the filter and
    // hands the tinyfd/fallback legs to the worker via onSdlFailed.
    private static void flashbackRedroided$attemptSdl(
            CompletableFuture<String> future,
            String kind,
            SdlFilter filter,
            SdlShower shower,
            PathFix fixResult,
            Runnable onSdlFailed
    ) {
        long window;
        try {
            window = Minecraft.getInstance().getWindow().handle();
        } catch (Throwable t) {
            if (filter != null) {
                filter.free();
            }
            Flashback.LOGGER.debug("No game window for SDL {} dialog, trying tinyfd", kind, t);
            onSdlFailed.run();
            return;
        }

        SDL_DialogFileCallbackI callback = (userdata, filelist, filterIndex) -> {
            if (filter != null) {
                filter.free();
            }
            if (filelist == 0) {
                String error = flashbackRedroided$sdlError();
                if (error.isEmpty()) {
                    flashbackRedroided$settleDialog(future, null);
                    return;
                }
                Flashback.LOGGER.warn("SDL {} dialog failed ({}); trying tinyfd", kind, error);
                onSdlFailed.run();
                return;
            }
            flashbackRedroided$settleDialog(
                    future, fixResult.fix(MemoryUtil.memUTF8Safe(MemoryUtil.memGetAddress(filelist))));
        };
        heldSdlCallback = callback;
        try {
            SDLError.SDL_ClearError();
            shower.show(callback, window);
        } catch (Throwable t) {
            if (filter != null) {
                filter.free();
            }
            Flashback.LOGGER.debug("SDL {} dialog unavailable, trying tinyfd", kind, t);
            onSdlFailed.run();
        }
    }

    // Runs a blocking tinyfd call plus a hardcoded fallback, always off-thread.
    // Empty tinyfd results fall through to the fallback; both failing settles null.
    private static void flashbackRedroided$tinyThenFallback(
            CompletableFuture<String> future,
            TinyAttempt tiny,
            HardFallback fallback
    ) {
        DIALOG_WORKER.execute(() -> {
            String value = null;
            try {
                value = tiny.run();
                if (value != null && value.isEmpty()) {
                    value = null;
                }
            } catch (Throwable ignored) {
            }
            if (value == null) {
                try {
                    value = fallback.run();
                } catch (Throwable ignored) {
                    value = null;
                }
            }
            flashbackRedroided$settleDialog(future, value);
        });
    }

    // Hardcoded game-dir export path; exports must land somewhere.
    private static String flashbackRedroided$fallbackExportPath(String defaultName, String autoExtension) {
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
        return fallback.getAbsolutePath();
    }

    // Hardcoded game-dir export folder.
    private static String flashbackRedroided$fallbackExportFolder() {
        File fallback = flashbackRedroided$getDefaultExportDir();
        Flashback.LOGGER.warn(
                "Using default export folder: {}",
                fallback.getAbsolutePath()
        );
        return fallback.getAbsolutePath();
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

        Runnable cascade = () -> flashbackRedroided$tinyThenFallback(
                future,
                () -> {
                    try (var stack = MemoryStack.stackPush()) {
                        return flashbackRedroided$withExtension(
                                TinyFileDialogs.tinyfd_saveFileDialog(
                                        filterDescription != null ? filterDescription : "Save file",
                                        defaultName != null && !defaultName.isEmpty() ? defaultName : "export",
                                        flashbackRedroided$tinyPatterns(stack, filters),
                                        filterDescription),
                                autoExtension);
                    }
                },
                () -> flashbackRedroided$fallbackExportPath(defaultName, autoExtension));

        SdlFilter sdlFilter;
        try {
            sdlFilter = SdlFilter.of(filterDescription, filters);
        } catch (Throwable t) {
            Flashback.LOGGER.debug("SDL filter alloc failed, trying tinyfd", t);
            cascade.run();
            return future;
        }
        flashbackRedroided$attemptSdl(
                future, "save", sdlFilter,
                (callback, window) -> SDLDialog.SDL_ShowSaveFileDialog(
                        callback, 0L, window, sdlFilter.buffer, defaultLocation),
                path -> flashbackRedroided$withExtension(path, autoExtension),
                cascade);
        return future;
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

        // Imports have no hardcoded fallback: null means the user gets no file.
        Runnable cascade = () -> flashbackRedroided$tinyThenFallback(
                future,
                () -> {
                    try (var stack = MemoryStack.stackPush()) {
                        return TinyFileDialogs.tinyfd_openFileDialog(
                                filterDescription != null ? filterDescription : "Open file",
                                defaultPath != null ? defaultPath : "",
                                flashbackRedroided$tinyPatterns(stack, filters),
                                filterDescription,
                                false);
                    }
                },
                () -> null);

        SdlFilter sdlFilter;
        try {
            sdlFilter = SdlFilter.of(filterDescription, filters);
        } catch (Throwable t) {
            Flashback.LOGGER.debug("SDL filter alloc failed, trying tinyfd", t);
            cascade.run();
            return future;
        }
        flashbackRedroided$attemptSdl(
                future, "open", sdlFilter,
                (callback, window) -> SDLDialog.SDL_ShowOpenFileDialog(
                        callback, 0L, window, sdlFilter.buffer, defaultLocation, false),
                path -> path,
                cascade);
        return future;
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

        Runnable cascade = () -> flashbackRedroided$tinyThenFallback(
                future,
                () -> TinyFileDialogs.tinyfd_selectFolderDialog(
                        "Select folder",
                        defaultPath != null ? defaultPath : ""),
                FileDialogMixin::flashbackRedroided$fallbackExportFolder);

        flashbackRedroided$attemptSdl(
                future, "folder", null,
                (callback, window) -> SDLDialog.SDL_ShowOpenFolderDialog(
                        callback, 0L, window, defaultLocation, false),
                path -> path,
                cascade);
        return future;
    }
}
