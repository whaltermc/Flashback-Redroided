// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba;

import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

// One-entry SDL filter built like vanilla Flashback: description plus
// ;-joined patterns. Top-level on purpose: helper types inside a mixin
// package cannot be referenced and crash loading with IllegalClassLoadError.
public final class SdlFilter {
    public final SDL_DialogFileFilter.Buffer buffer;
    private final ByteBuffer name;
    private final ByteBuffer pattern;

    public static SdlFilter of(String filterDescription, String[] filters) {
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

    public void free() {
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
