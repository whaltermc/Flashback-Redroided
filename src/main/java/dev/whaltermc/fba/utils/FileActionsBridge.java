// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.utils;

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

/**
 * Entry points for the {@code AsyncFileDialogs} hook. Futures complete on daemon
 * threads, like Flashback's own dialog thread, with a path on success and null
 * for a cancelled dialog.
 *
 * <p>Import has no system picker to return to: the import folder is the hand-over
 * point. The Files app is opened on it and the call completes once a new file
 * lands there.
 *
 * <p>Tuning flags:
 * <ul>
 *   <li>{@code -Dfba.fileDir=/abs/path} pins the import/export root.</li>
 *   <li>{@code -Dfba.fileActions=false} disables the hook entirely.</li>
 *   <li>{@code -Dfba.importMode=newest} returns the newest file instead of waiting.</li>
 *   <li>{@code -Dfba.importWaitSeconds=N} caps the folder watch (default 300).</li>
 *   <li>{@code -Dfba.picker=launcher} asks the launcher's picker first (default).</li>
 * </ul>
 */
public final class FileActionsBridge {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "FbaFileActions");
        thread.setDaemon(true);
        return thread;
    });
    private static final String DEFAULT_SHARED_ROOT = "/storage/emulated/0/Download/FlashbackAndroid";
    private static final String EXTERNAL_STORAGE = "/storage/emulated/0/";
    private static final String DOCUMENTS = "content://com.android.externalstorage.documents/";
    private static final long DEFAULT_WAIT_SECONDS = 300;
    private static final long POLL_MILLIS = 500;
    private static volatile FileActions actions;
    private static final Object WATCH_LOCK = new Object();
    private static Thread watcher;
    private static CompletableFuture<String> watching;

    private FileActionsBridge() {}

    /** Picks the import/export root: the {@code -Dfba.fileDir} override, else shared storage when writable, else the given root. */
    static FileActions configure(Path requestedRoot) throws IOException {
        Path root = requestedRoot;
        String override = System.getProperty("fba.fileDir");
        if (override == null || override.isBlank()) {
            Path shared = sharedRootIfUsable(Path.of(DEFAULT_SHARED_ROOT));
            if (shared != null) {
                root = shared;
            } else {
                System.out.println("[FBA Files] SHARED_FOLDER_UNAVAILABLE using " + root
                        + " (the Files app may not be able to open it; set -Dfba.fileDir to a folder it can)");
            }
        }
        var configured = new FileActions(root);
        configured.createFolders();
        actions = configured;
        System.out.println("[FBA Files] FOLDERS import=" + configured.importDir() + " export=" + configured.exportDir());
        return configured;
    }

    /** Returns the shared root when the Files app can browse it and this process can write to it. */
    static Path sharedRootIfUsable(Path shared) {
        try {
            if (!Files.isDirectory(shared.getParent().getParent())) return null;
            Path importDir = shared.resolve("import");
            Files.createDirectories(importDir);
            Files.createDirectories(shared.resolve("export"));
            Path probe = Files.createTempFile(importDir, ".probe", ".tmp");
            Files.deleteIfExists(probe);
            return shared;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Stands in for {@code openFileDialog}: waits for a matching file in the import folder. */
    public static CompletableFuture<String> open(String[] extensions) {
        var current = actions;
        if (current == null) {
            System.err.println("[FBA Files] NOT_CONFIGURED");
            return CompletableFuture.completedFuture(null);
        }
        var accepted = FileActions.normalizeExtensions(extensions);
        // Snapshot at click time so a file copied right afterwards still counts as new.
        Map<String, String> before;
        try {
            before = current.importSnapshot(accepted);
        } catch (IOException e) {
            before = Map.of();
        }
        final Map<String, String> snapshot = before;
        var future = new CompletableFuture<String>();
        synchronized (WATCH_LOCK) {
            if (watching != null) {
                watching.complete(null);
                if (watcher != null) watcher.interrupt();
            }
            watching = future;
            Thread thread = new Thread(() -> {
                try {
                    future.complete(importFlow(current, accepted, snapshot));
                } catch (InterruptedException e) {
                    // Superseded by a newer import click.
                    future.complete(null);
                } catch (Throwable t) {
                    t.printStackTrace();
                    future.complete(null);
                }
            }, "FbaImportWatch");
            thread.setDaemon(true);
            watcher = thread;
            thread.start();
        }
        return future;
    }

    private static String importFlow(FileActions current, List<String> accepted, Map<String, String> before) throws IOException, InterruptedException {
        boolean newestMode = "newest".equalsIgnoreCase(System.getProperty("fba.importMode", "folder"));
        long waitSeconds = Long.getLong("fba.importWaitSeconds", DEFAULT_WAIT_SECONDS);
        if (newestMode || waitSeconds <= 0) {
            var file = current.newestImport(accepted);
            if (file.isEmpty()) {
                System.out.println("[FBA Files] IMPORT_EMPTY: no " + describe(accepted) + " in " + current.importDir());
                return null;
            }
            System.out.println("[FBA Files] IMPORT " + file.get());
            return file.get().toString();
        }
        if ("launcher".equalsIgnoreCase(System.getProperty("fba.picker", "launcher"))) {
            long timeout = Long.getLong("fba.pickerTimeoutSeconds", 900);
            var result = LauncherPicker.pick(LauncherPicker.baseDir(), "", "", accepted, timeout);
            switch (result.status()) {
                case "ok" -> {
                    if (!Files.isRegularFile(Path.of(result.path()))) {
                        System.out.println("[FBA Files] IMPORT_PATH_NOT_READABLE " + result.path());
                    }
                    System.out.println("[FBA Files] IMPORT " + result.path());
                    return result.path();
                }
                case "cancel" -> {
                    System.out.println("[FBA Files] IMPORT_CANCELLED " + result.message());
                    return null;
                }
                case "timeout" -> {
                    System.out.println("[FBA Files] IMPORT_TIMEOUT " + result.message());
                    return null;
                }
                default -> System.out.println("[FBA Files] PICKER_UNAVAILABLE " + result.message()
                        + " - falling back to the Files app and the import folder");
            }
        }
        openFilesApp(current.importDir());
        System.out.println("[FBA Files] IMPORT_WAITING for a new " + describe(accepted) + " in " + current.importDir()
                + " (up to " + waitSeconds + "s)");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds);
        Path candidate = null;
        String candidateStamp = null;
        while (System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS);
            var changed = current.newestChanged(accepted, before);
            if (changed.isEmpty()) { candidate = null; continue; }
            Path path = changed.get();
            String stamp = Files.getLastModifiedTime(path).toMillis() + ":" + Files.size(path);
            // Accept once the file stops growing between two polls so half-copied files are never read.
            if (path.equals(candidate) && stamp.equals(candidateStamp) && Files.size(path) > 0) {
                System.out.println("[FBA Files] IMPORT " + path);
                return path.toString();
            }
            candidate = path;
            candidateStamp = stamp;
        }
        System.out.println("[FBA Files] IMPORT_TIMEOUT: nothing new appeared in " + current.importDir());
        return null;
    }

    private static String describe(List<String> accepted) {
        return accepted.isEmpty() ? "file" : "." + String.join(", .", accepted) + " file";
    }

    /** Stands in for {@code saveFileDialog}: reserves a never-overwritten path in the export folder. */
    public static CompletableFuture<String> save(String defaultName, String[] extensions) {
        return submit(() -> {
            var current = actions;
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

    /** Stands in for {@code openFolderDialog}: the export folder, created on demand. */
    public static CompletableFuture<String> folder() {
        return submit(() -> {
            var current = actions;
            if (current == null) return null;
            try {
                Files.createDirectories(current.exportDir());
                return current.exportDir().toString();
            } catch (IOException e) {
                System.err.println("[FBA Files] FOLDER_FAILED " + e);
                return null;
            }
        });
    }

    /** Runs work on the file-actions thread; completes with null when the hook was never configured. */
    private static CompletableFuture<String> submit(Supplier<String> work) {
        if (actions == null) {
            System.err.println("[FBA Files] NOT_CONFIGURED");
            return CompletableFuture.completedFuture(null);
        }
        var future = new CompletableFuture<String>();
        try {
            EXECUTOR.execute(() -> {
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

    /** DocumentsUI URI showing this folder, or null when it lives outside primary shared storage. */
    static String folderUri(Path dir) {
        String path = dir.toAbsolutePath().normalize().toString().replace('\\', '/');
        if (path.startsWith("/sdcard/")) path = EXTERNAL_STORAGE + path.substring("/sdcard/".length());
        if (!path.startsWith(EXTERNAL_STORAGE)) return null;
        String relative = path.substring(EXTERNAL_STORAGE.length());
        if (relative.isEmpty() || relative.equals("Android") || relative.startsWith("Android/")) return null;
        return DOCUMENTS + "document/" + URLEncoder.encode("primary:" + relative, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void openFilesApp(Path folder) {
        String uri = folderUri(folder);
        List<List<String>> attempts = new ArrayList<>();
        if (uri != null) attempts.add(List.of("-a", "android.intent.action.VIEW", "-d", uri, "-t", "vnd.android.document/directory"));
        attempts.add(List.of("-a", "android.provider.action.BROWSE", "-d", DOCUMENTS + "root/primary"));
        String lastError = "no Android activity manager found";
        for (List<String> attempt : attempts) {
            for (List<String> launcher : List.of(List.of("/system/bin/am", "start"),
                    List.of("/system/bin/cmd", "activity", "start-activity"))) {
                if (!Files.isExecutable(Path.of(launcher.get(0)))) continue;
                var command = new ArrayList<>(launcher);
                command.addAll(attempt);
                String result = run(command);
                if (result == null) {
                    System.out.println("[FBA Files] FILES_APP_OPENED " + String.join(" ", attempt));
                    return;
                }
                lastError = result;
            }
        }
        System.out.println("[FBA Files] FILES_APP_FAILED " + lastError + ". Open " + folder + " yourself and copy the file there.");
    }

    /** Runs an activity-manager command with the game process env scrubbed; null on success, else a reason. */
    private static String run(List<String> command) {
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
