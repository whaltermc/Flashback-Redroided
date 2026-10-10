// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Asks the launcher to show Android's own file picker through a request/response
 * file hand-over. The game writes a request; the launcher shows the picker and
 * answers with a readable path.
 *
 * <pre>
 * &lt;user.dir&gt;/.droidbridge/flashdroid-file-dialog.request.properties   token, defaultPath, description, filters (a;b;c)
 * &lt;user.dir&gt;/.droidbridge/flashdroid-file-dialog.response.properties  token, status (ok|cancel|...), path, message
 * </pre>
 */
final class LauncherPicker {
    static final String BRIDGE_DIR = ".droidbridge";
    static final String REQUEST_NAME = "flashdroid-file-dialog.request.properties";
    static final String RESPONSE_NAME = "flashdroid-file-dialog.response.properties";
    private static final long POLL_MILLIS = 100;

    /** "ok" carries a path; "cancel" and "timeout" carry none; "error" means no picker answered. */
    record Result(String status, String path, String message) {
        static Result of(String status, String message) { return new Result(status, null, message); }
    }

    private LauncherPicker() {}

    static Path baseDir() {
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    static String joinFilters(List<String> extensions) {
        return extensions.isEmpty() ? "*" : String.join(";", extensions);
    }

    /** Blocks until the launcher answers, the timeout passes, or this thread is interrupted. */
    static Result pick(Path baseDir, String defaultPath, String description, List<String> extensions, long timeoutSeconds)
            throws InterruptedException {
        Path bridge = baseDir.resolve(BRIDGE_DIR);
        Path request = bridge.resolve(REQUEST_NAME);
        Path response = bridge.resolve(RESPONSE_NAME);
        String token = UUID.randomUUID().toString();
        try {
            Files.createDirectories(bridge);
            Files.deleteIfExists(response);
            var properties = new Properties();
            properties.setProperty("token", token);
            properties.setProperty("defaultPath", defaultPath == null ? "" : defaultPath);
            properties.setProperty("description", description == null ? "" : description);
            properties.setProperty("filters", joinFilters(extensions));
            writeAtomic(request, properties);
        } catch (IOException | RuntimeException e) {
            return Result.of("error", "could not write request: " + e);
        }
        System.out.println("[FBA Files] PICKER_REQUESTED token=" + token + " filters=" + joinFilters(extensions));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(response)) {
                    Properties answer;
                    try {
                        answer = load(response);
                    } catch (IOException e) {
                        // Response is still being written; retry on the next poll.
                        Thread.sleep(POLL_MILLIS);
                        continue;
                    }
                    if (token.equals(answer.getProperty("token", ""))) {
                        String status = answer.getProperty("status", "cancel");
                        String message = answer.getProperty("message", "");
                        if ("ok".equalsIgnoreCase(status)) {
                            String path = answer.getProperty("path", "").trim();
                            return path.isEmpty() ? Result.of("cancel", "") : new Result("ok", path, "");
                        }
                        return Result.of("cancel".equalsIgnoreCase(status) ? "cancel" : "error", status + (message.isEmpty() ? "" : ": " + message));
                    }
                }
                Thread.sleep(POLL_MILLIS);
            }
            return Result.of("timeout", "no answer from the launcher within " + timeoutSeconds + "s");
        } finally {
            try { Files.deleteIfExists(response); } catch (IOException | RuntimeException ignored) {}
            try { Files.deleteIfExists(request); } catch (IOException | RuntimeException ignored) {}
        }
    }

    private static Properties load(Path file) throws IOException {
        var properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private static void writeAtomic(Path target, Properties properties) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            properties.store(out, "FBA file dialog bridge");
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
