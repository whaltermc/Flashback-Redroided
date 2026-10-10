// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.utils;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

// Backs Flashback's file dialogs with folders instead of native UI. Every
// call returns a future: a path on success, null when the dialog counts as
// cancelled. Work runs on daemon threads, mirroring Flashback's own dialog
// thread.
//
// Import has no picker to return to, so the import folder doubles as the
// drop point: the Files app is opened on it and the call completes once a
// fresh file settles there.
//
// Tuning flags:
// - "-Dfba.fileDir=/abs/path" pins the import/export root.
// - "-Dfba.fileActions=false" switches the whole hook off.
// - "-Dfba.importMode=newest" picks the newest file instead of waiting.
// - "-Dfba.importWaitSeconds=N" caps the folder watch (default 300).
// - "-Dfba.picker=launcher" asks the launcher picker first (default).
//
// When startup selection never ran, the folders fall back to hardcoded
// paths (shared storage, else the game directory) instead of failing.
public final class FileActionsBridge {
    private static final ExecutorService JOBS = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "FbaFileActions");
        worker.setDaemon(true);
        return worker;
    });

    // Shared-storage home used when the app can write there; keeps exports visible in file managers.
    private static final String SHARED_ROOT = "/storage/emulated/0/Download/FlashbackAndroid";
    private static final String SHARED_PREFIX = "/storage/emulated/0/";
    private static final String DOCUMENTS_BASE = "content://com.android.externalstorage.documents/";
    private static final long WAIT_SECONDS_DEFAULT = 300;
    private static final long SETTLE_POLL_MILLIS = 500;

    private static volatile FileActions files;
    private static final Object IMPORT_GUARD = new Object();
    private static Thread importThread;
    private static CompletableFuture<String> importFuture;

    private FileActionsBridge() {}

    // Chooses the import/export root: the -Dfba.fileDir override when set,
    // otherwise shared storage when it is writable, otherwise the given
    // fallback. Creates both folders.
    static FileActions configure(Path fallbackRoot) throws IOException {
        Path root = fallbackRoot;
        String override = System.getProperty("fba.fileDir");
        if (override == null || override.isBlank()) {
            Path shared = sharedRootIfUsable(Path.of(SHARED_ROOT));
            if (shared != null) {
                root = shared;
            } else {
                System.out.println("[FBA Files] Shared folder unavailable, using " + root
                        + " (the Files app may not reach it; pass -Dfba.fileDir=/path/to/a/visible/folder)");
            }
        }
        var ready = new FileActions(root);
        ready.createFolders();
        files = ready;
        System.out.println("[FBA Files] Using folders: import=" + ready.inbox() + " export=" + ready.outbox());
        return ready;
    }

    // Returns the shared root when the Files app can show it and this process can write into it.
    static Path sharedRootIfUsable(Path shared) {
        try {
            if (shared.getParent() == null || !Files.isDirectory(shared.getParent().getParent())) return null;
            Files.createDirectories(shared.resolve("import"));
            Files.createDirectories(shared.resolve("export"));
            Path probe = Files.createTempFile(shared.resolve("import"), ".probe", ".tmp");
            Files.deleteIfExists(probe);
            return shared;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // Hardcoded fallback behind every entry point: when startup selection
    // never configured the folders, builds them from fixed paths (shared
    // storage, else the game directory) instead of completing with null.
    // Runs once; later calls reuse the same folders.
    private static FileActions ready() {
        var current = files;
        if (current != null) return current;
        synchronized (FileActionsBridge.class) {
            current = files;
            if (current != null) return current;
            try {
                Path root = FabricLoader.getInstance().getGameDir().resolve("flashback-android");
                current = configure(root);
            } catch (Throwable t) {
                System.err.println("[FBA Files] FALLBACK_FAILED " + t);
                return null;
            }
            return current;
        }
    }

    // Stands in for openFileDialog: resolves to a matching file from the import folder.
    public static CompletableFuture<String> open(String[] extensions) {
        var current = ready();
        if (current == null) {
            System.err.println("[FBA Files] NOT_CONFIGURED");
            return CompletableFuture.completedFuture(null);
        }
        var accepted = FileActions.normalizeExtensions(extensions);
        // Frozen at click time so a file dropped right afterwards still reads as new.
        final Map<String, String> snapshot;
        try {
            snapshot = current.importSnapshot(accepted);
        } catch (IOException e) {
            System.err.println("[FBA Files] IMPORT_FAILED " + e);
            return CompletableFuture.completedFuture(null);
        }
        var future = new CompletableFuture<String>();
        synchronized (IMPORT_GUARD) {
            if (importFuture != null) {
                importFuture.complete(null);
                if (importThread != null) importThread.interrupt();
            }
            importFuture = future;
            Thread thread = new Thread(() -> {
                try {
                    future.complete(resolveImport(current, accepted, snapshot));
                } catch (InterruptedException e) {
                    // Superseded by a newer import request.
                    future.complete(null);
                } catch (Throwable t) {
                    t.printStackTrace();
                    future.complete(null);
                }
            }, "FbaImportWatch");
            thread.setDaemon(true);
            importThread = thread;
            thread.start();
        }
        return future;
    }

    private static String resolveImport(FileActions current, List<String> accepted, Map<String, String> before)
            throws IOException, InterruptedException {
        boolean newestOnly = "newest".equalsIgnoreCase(System.getProperty("fba.importMode", "folder"));
        long waitSeconds = Long.getLong("fba.importWaitSeconds", WAIT_SECONDS_DEFAULT);
        if (newestOnly || waitSeconds <= 0) {
            return newestNow(current, accepted);
        }
        if ("launcher".equalsIgnoreCase(System.getProperty("fba.picker", "launcher"))) {
            long timeout = Long.getLong("fba.pickerTimeoutSeconds", 900);
            var answer = LauncherPicker.pick(LauncherPicker.baseDir(), "", "", accepted, timeout);
            switch (answer.status()) {
                case "ok" -> {
                    if (!Files.isRegularFile(Path.of(answer.path()))) {
                        System.out.println("[FBA Files] Picker path is not readable: " + answer.path());
                    }
                    System.out.println("[FBA Files] IMPORT " + answer.path());
                    return answer.path();
                }
                case "cancel" -> {
                    System.out.println("[FBA Files] Import cancelled: " + answer.message());
                    return null;
                }
                case "timeout" -> {
                    System.out.println("[FBA Files] Picker timed out: " + answer.message());
                    return null;
                }
                default -> System.out.println("[FBA Files] No launcher picker (" + answer.message()
                        + "); watching the import folder instead");
            }
        }
        return watchForDrop(current, accepted, before, waitSeconds);
    }

    private static String newestNow(FileActions current, List<String> accepted) throws IOException {
        var file = current.newestImport(accepted);
        if (file.isEmpty()) {
            System.out.println("[FBA Files] Import folder has no " + labelFor(accepted) + ": " + current.inbox());
            return null;
        }
        System.out.println("[FBA Files] IMPORT " + file.get());
        return file.get().toString();
    }

    private static String watchForDrop(FileActions current, List<String> accepted, Map<String, String> baseline, long waitSeconds)
            throws InterruptedException {
        showInFilesApp(current.inbox());
        System.out.println("[FBA Files] Waiting up to " + waitSeconds + "s for a new " + labelFor(accepted)
                + " in " + current.inbox());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
        Path settling = null;
        String settlingStamp = null;
        while (System.nanoTime() < deadline) {
            Thread.sleep(SETTLE_POLL_MILLIS);
            Path changed;
            try {
                changed = current.newestChanged(accepted, baseline).orElse(null);
            } catch (IOException e) {
                continue;
            }
            if (changed == null) {
                settling = null;
                continue;
            }
            String stamp;
            long size;
            try {
                stamp = Files.getLastModifiedTime(changed).toMillis() + ":" + Files.size(changed);
                size = Files.size(changed);
            } catch (IOException e) {
                continue;
            }
            // Only accept a file that stopped growing between two polls.
            if (changed.equals(settling) && stamp.equals(settlingStamp) && size > 0) {
                System.out.println("[FBA Files] IMPORT " + changed);
                return changed.toString();
            }
            settling = changed;
            settlingStamp = stamp;
        }
        System.out.println("[FBA Files] Timed out with nothing new in " + current.inbox());
        return null;
    }

    private static String labelFor(List<String> accepted) {
        return accepted.isEmpty() ? "file" : "." + String.join(", .", accepted) + " file";
    }

    // Stands in for saveFileDialog: reserves a never-overwritten path in the export folder.
    public static CompletableFuture<String> save(String defaultName, String[] extensions) {
        return submit(() -> {
            var current = ready();
            if (current == null) return null;
            try {
                Path target = current.allocateExport(defaultName, FileActions.normalizeExtensions(extensions));
                System.out.println("[FBA Files] EXPORT " + target);
                return target.toString();
            } catch (IOException e) {
                System.err.println("[FBA Files] EXPORT_FAILED " + e);
                return null;
            }
        });
    }

    // Stands in for openFolderDialog: the export folder, created on demand.
    public static CompletableFuture<String> folder() {
        return submit(() -> {
            var current = ready();
            if (current == null) return null;
            try {
                Files.createDirectories(current.outbox());
                return current.outbox().toString();
            } catch (IOException e) {
                System.err.println("[FBA Files] FOLDER_FAILED " + e);
                return null;
            }
        });
    }

    // Runs work on the file-actions thread; falls back to hardcoded folders
    // when startup selection never ran, and completes with null only when
    // even that fails.
    private static CompletableFuture<String> submit(Supplier<String> work) {
        if (ready() == null) {
            System.err.println("[FBA Files] NOT_CONFIGURED");
            return CompletableFuture.completedFuture(null);
        }
        var future = new CompletableFuture<String>();
        try {
            JOBS.execute(() -> {
                try {
                    future.complete(work.get());
                } catch (Throwable t) {
                    t.printStackTrace();
                    future.complete(null);
                }
            });
        } catch (RuntimeException e) {
            future.complete(null);
        }
        return future;
    }

    // ---- Opening the Android Files app (best effort; the launcher may restrict this) ----

    // DocumentsUI link showing this folder, or null when it sits outside primary shared storage.
    static String documentsUri(Path dir) {
        String path = dir.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (path.startsWith("/sdcard/")) path = SHARED_PREFIX + path.substring("/sdcard/".length());
        if (!path.startsWith(SHARED_PREFIX)) return null;
        String relative = path.substring(SHARED_PREFIX.length());
        if (relative.isEmpty() || relative.equals("Android") || relative.startsWith("Android/")) return null;
        return DOCUMENTS_BASE + "document/" + URLEncoder.encode("primary:" + relative, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // Kept for callers that used the previous name.
    static String folderUri(Path dir) {
        return documentsUri(dir);
    }

    private static void showInFilesApp(Path folder) {
        String uri = documentsUri(folder);
        var attempts = new ArrayList<List<String>>();
        if (uri != null) {
            attempts.add(List.of("-a", "android.intent.action.VIEW", "-d", uri, "-t", "vnd.android.document/directory"));
        }
        attempts.add(List.of("-a", "android.provider.action.BROWSE", "-d", DOCUMENTS_BASE + "root/primary"));
        var runners = List.of(
                List.of("/system/bin/am", "start"),
                List.of("/system/bin/cmd", "activity", "start-activity"));
        String lastError = "no Android activity manager found";
        for (List<String> attempt : attempts) {
            for (List<String> runner : runners) {
                if (!Files.isExecutable(Path.of(runner.get(0)))) continue;
                var command = new ArrayList<>(runner);
                command.addAll(attempt);
                String failure = runCommand(command);
                if (failure == null) {
                    System.out.println("[FBA Files] Files app opened (" + String.join(" ", attempt) + ")");
                    return;
                }
                lastError = failure;
            }
        }
        System.out.println("[FBA Files] Could not open the Files app (" + lastError
                + "). Open " + folder + " yourself and copy the file there.");
    }

    // Runs an activity-manager command with the game process env scrubbed; null on success, else a reason.
    private static String runCommand(List<String> command) {
        try {
            var builder = new ProcessBuilder(command).redirectErrorStream(true);
            for (String key : List.of("LD_LIBRARY_PATH", "LD_PRELOAD", "JAVA_HOME", "JAVA_TOOL_OPTIONS",
                    "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH")) builder.environment().remove(key);
            Process process = builder.start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "timed out";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() != 0 || output.contains("Error") || output.contains("Exception")) {
                return output.isEmpty() ? "exit " + process.exitValue() : output.lines().findFirst().orElse(output);
            }
            return null;
        } catch (IOException e) {
            return e.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }
}
