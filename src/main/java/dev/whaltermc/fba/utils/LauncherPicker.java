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
 * Talks to the launcher's own file picker through request/response files.
 * The game drops a request into {@code .droidbridge/}; the launcher shows
 * the system picker and answers with a readable path. Both files are removed
 * afterwards so stale requests never leak into the next pick.
 *
 * <pre>
 * .droidbridge/flashdroid-file-dialog.request.properties   token, defaultPath, description, filters (a;b;c)
 * .droidbridge/flashdroid-file-dialog.response.properties  token, status (ok|cancel|...), path, message
 * </pre>
 */
final class LauncherPicker {
    static final String BRIDGE_DIR = ".droidbridge";
    static final String REQUEST_NAME = "flashdroid-file-dialog.request.properties";
    static final String RESPONSE_NAME = "flashdroid-file-dialog.response.properties";
    private static final long POLL_MILLIS = 100;

    /** "ok" carries a path; "cancel"/"timeout" carry none; "error" means no picker answered. */
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

    /** Blocks until the launcher answers, the timeout runs out, or this thread is interrupted. */
    static Result pick(Path baseDir, String defaultPath, String description, List<String> extensions, long timeoutSeconds)
            throws InterruptedException {
        Path bridgeDir = baseDir.resolve(BRIDGE_DIR);
        Path request = bridgeDir.resolve(REQUEST_NAME);
        Path response = bridgeDir.resolve(RESPONSE_NAME);
        String token = UUID.randomUUID().toString();
        try {
            Files.createDirectories(bridgeDir);
            Files.deleteIfExists(response);
            storeRequest(request, token, defaultPath, description, joinFilters(extensions));
        } catch (IOException | RuntimeException e) {
            return Result.of("error", "could not write request: " + e);
        }
        System.out.println("[FBA Files] Picker requested (token=" + token + ", filters=" + joinFilters(extensions) + ")");
        try {
            return awaitResponse(response, token, timeoutSeconds);
        } finally {
            deleteQuietly(response);
            deleteQuietly(request);
        }
    }

    private static void storeRequest(Path request, String token, String defaultPath, String description, String filters)
            throws IOException {
        var properties = new Properties();
        properties.setProperty("token", token);
        properties.setProperty("defaultPath", defaultPath == null ? "" : defaultPath);
        properties.setProperty("description", description == null ? "" : description);
        properties.setProperty("filters", filters);
        Path draft = request.resolveSibling(request.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(draft)) {
            properties.store(out, "FBA file dialog bridge");
        }
        try {
            Files.move(draft, request, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(draft, request, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Result awaitResponse(Path response, String token, long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(response)) {
                Result done = readAnswer(response, token);
                if (done != null) return done;
            }
            Thread.sleep(POLL_MILLIS);
        }
        return Result.of("timeout", "no answer from the launcher within " + timeoutSeconds + "s");
    }

    /**
     * Reads one response attempt. Returns null when the file belongs to an
     * older request or is still being written, so the poll loop retries.
     */
    private static Result readAnswer(Path response, String token) throws InterruptedException {
        Properties answer = new Properties();
        try (InputStream in = Files.newInputStream(response)) {
            answer.load(in);
        } catch (IOException e) {
            Thread.sleep(POLL_MILLIS);
            return null;
        }
        if (!token.equals(answer.getProperty("token", ""))) return null;
        String status = answer.getProperty("status", "cancel");
        String message = answer.getProperty("message", "");
        if ("ok".equalsIgnoreCase(status)) {
            String path = answer.getProperty("path", "").trim();
            return path.isEmpty() ? Result.of("cancel", "") : new Result("ok", path, "");
        }
        String kind = "cancel".equalsIgnoreCase(status) ? "cancel" : "error";
        return Result.of(kind, status + (message.isEmpty() ? "" : ": " + message));
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
