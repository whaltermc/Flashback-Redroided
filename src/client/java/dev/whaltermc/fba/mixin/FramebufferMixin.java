// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads Flashback's pixel-pack PBO without mapping it.
 *
 * <p>Mapping is the wrong tool on this driver. MobileGlues' {@code glMapBuffer}
 * returns {@code nullptr} whenever a GL error is merely <em>pending</em>
 * ({@code gl/buffer.cpp}: {@code if (buffer_size <= 0 || glGetError() != GL_NO_ERROR)
 * return nullptr;}), so any stray error anywhere earlier in the frame turns a
 * perfectly good readback into Flashback's "OpenGL error occurred while mapping
 * buffer" crash. GLES also refuses to map a buffer that is bound to a target,
 * and Flashback leaves the PBO bound to {@code GL_PIXEL_PACK_BUFFER}.
 *
 * <p>{@code glGetBufferSubData} sidesteps all of it: no mapping, no pending-error
 * sensitivity. It reads from the buffer bound to the target, so the PBO must
 * stay bound for the duration of the call -- Flashback unbinds it itself
 * immediately after the copy.
 */
@Mixin(targets = "com.moulberry.flashback.exporting.SaveableFramebuffer", remap = false, priority = 1000)
public abstract class FramebufferMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("flashback_android");

    private static final AtomicBoolean ENTRY_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean DOWNLOAD_ENTRY_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean READPIXELS_WRAP_LOGGED = new AtomicBoolean();

    /** True when the current call returned a plain readback buffer, not a map. */
    private static boolean usedReadback;

    /**
     * Reused readback buffer. A full-HD frame is ~8 MB of native memory, so
     * allocating a fresh buffer per frame leaks native memory for the whole
     * export (nothing ever frees the copies). The buffer is only ever used
     * sequentially -- Flashback copies it out synchronously via
     * {@code memCopy} before {@code finishDownload} returns -- so one cached
     * buffer, reallocated only when the frame size changes, is safe.
     */
    private static ByteBuffer cachedCopy;
    private static int cachedSize;

    /** Consecutive readback frames whose sampled pixels were all zero. */
    private static int blankStreak;
    /** Total readback frames seen, for throttled logging. */
    private static long framesSeen;

    @WrapOperation(
            method = "finishDownload",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/GL30C;glMapBuffer(II)Ljava/nio/ByteBuffer;",
                    remap = false
            ),
            remap = false,
            require = 0
    )
    private ByteBuffer flashbackRedroided$readPixelBuffer(
            int target,
            int access,
            Operation<ByteBuffer> original
    ) {
        // Unconditional, once per session. Every previous attempt at this was
        // guesswork because a non-matching injection and a failing handler both
        // produce silence; this makes the two distinguishable immediately.
        if (ENTRY_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("pbo readback: handler entered, target={} access={}",
                    target, access);
        }

        usedReadback = false;

        int size = 0;
        try {
            int[] bufSize = new int[1];
            GL15C.glGetBufferParameteriv(target, GL15C.GL_BUFFER_SIZE, bufSize);
            size = bufSize[0];
        } catch (Throwable t) {
            LOGGER.warn("pbo readback: glGetBufferParameteriv failed", t);
        }

        if (size <= 0) {
            LOGGER.warn("pbo readback: no usable buffer size ({}), deferring to Flashback",
                    size);
            return original.call(target, access);
        }

        // The PBO is still bound here (Flashback bound it just before mapping).
        // Both glGetBufferSubData and glMapBufferRange operate on the buffer
        // bound to the target -- unbinding first makes them fail with
        // INVALID_OPERATION and hands the encoder uninitialized memory, which
        // is exactly the black/flickering-video symptom. Flashback unbinds the
        // PBO itself right after the copy, so leave the binding alone.
        // Drain stale errors first so the post-read glGetError check below is
        // meaningful (stale errors are what broke glMapBuffer originally).
        try {
            for (int i = 0; i < 16 && GL30C.glGetError() != GL30C.GL_NO_ERROR; i++) {
                // draining
            }
        } catch (Throwable ignored) {
            // best effort
        }

        try {
            if (cachedCopy == null || cachedSize != size) {
                ByteBuffer fresh = MemoryUtil.memAlloc(size);
                if (cachedCopy != null) {
                    try {
                        MemoryUtil.memFree(cachedCopy);
                    } catch (Throwable ignored) {
                        // best effort
                    }
                }
                cachedCopy = fresh;
                cachedSize = size;
            }
            ByteBuffer copy = cachedCopy;
            copy.clear();
            GL15C.glGetBufferSubData(target, 0, copy);
            int err = GL30C.glGetError();
            if (err != GL30C.GL_NO_ERROR) {
                LOGGER.warn("pbo readback: glGetBufferSubData GL error 0x{} (size={}), trying map fallback",
                        Integer.toHexString(err), size);
            } else {
                usedReadback = true;
                sampleFrameContent(copy, size);
                return copy;
            }
        } catch (Throwable t) {
            LOGGER.warn("pbo readback: glGetBufferSubData failed (size={})", size, t);
        }

        // Second choice: range map (also reads the bound buffer, no unbind).
        try {
            ByteBuffer mapped = GL30C.glMapBufferRange(target, 0, size, GL30C.GL_MAP_READ_BIT);
            if (mapped != null) {
                return mapped;
            }
            LOGGER.warn("pbo readback: glMapBufferRange returned null (size={})", size);
        } catch (Throwable t) {
            LOGGER.warn("pbo readback: glMapBufferRange failed (size={})", size, t);
        }

        // Last resort: Flashback's own behaviour, so we never make it worse.
        return original.call(target, access);
    }

    /**
     * Samples scattered pixels of a successfully read frame and logs when
     * frames are uniformly blank. A consistently blank stream means the PBO
     * itself contains zeros (glReadPixels/flip-blit upstream produced nothing),
     * while frames with content but a black output file point at the encoder
     * instead. Absolute reads only; buffer position is left untouched.
     */
    private static void sampleFrameContent(ByteBuffer copy, int size) {
        boolean blank = true;
        try {
            int samples = Math.min(256, size / 4);
            int stride = Math.max(1, (size / 4) / samples);
            for (int i = 0; i < samples; i++) {
                if (copy.getInt(i * stride * 4) != 0) {
                    blank = false;
                    break;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("pbo readback: frame sampling failed", t);
            return;
        }
        framesSeen++;
        if (blank) {
            blankStreak++;
            if (blankStreak == 1 || blankStreak % 600 == 0) {
                LOGGER.warn("pbo readback: frame pixels are all zero ({} consecutive blank frames, size={})",
                        blankStreak, size);
            }
        } else if (blankStreak > 0) {
            LOGGER.warn("pbo readback: frames have non-zero pixels again after {} blank frames",
                    blankStreak);
            blankStreak = 0;
        }
    }

    /**
     * One-time marker proving this mixin reaches {@code startDownload} at all.
     * If the log shows the finishDownload marker but never this one, the
     * startDownload injections below are silently unmatched (require=0).
     */
    @Inject(method = "startDownload", at = @At("HEAD"), remap = false, require = 0)
    private void flashbackRedroided$markDownloadEntry(CallbackInfo ci) {
        if (DOWNLOAD_ENTRY_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("pbo download: startDownload entered");
        }
    }

    /**
     * Watches Flashback's {@code glReadPixels} into the PBO in
     * {@code startDownload}. If the driver rejects the PACK_BUFFER read, the
     * PBO keeps whatever it had (zeros) and every exported frame is black no
     * matter how well the later readback works. Read-only: the call itself is
     * always forwarded unchanged.
     */
    @WrapOperation(
            method = "startDownload",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/GL30C;glReadPixels(IIIIIIJ)V",
                    remap = false
            ),
            remap = false,
            require = 0
    )
    private void flashbackRedroided$checkReadPixels(
            int x, int y, int width, int height, int format, int type, long pixels,
            Operation<Void> original
    ) {
        if (READPIXELS_WRAP_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("pbo download: readPixels wrapper active");
        }
        try {
            for (int i = 0; i < 16 && GL30C.glGetError() != GL30C.GL_NO_ERROR; i++) {
                // drain stale errors so the post-call check is meaningful
            }
        } catch (Throwable ignored) {
            // best effort
        }
        original.call(x, y, width, height, format, type, pixels);
        try {
            int err = GL30C.glGetError();
            if (err != GL30C.GL_NO_ERROR) {
                LOGGER.warn("pbo download: glReadPixels failed, GL error 0x{} ({}x{} fmt={} type={})",
                        Integer.toHexString(err), width, height, format, type);
            }
        } catch (Throwable ignored) {
            // best effort
        }
        probeDirectRead(width, height, format, type);
    }

    private static final int GL_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int GL_PIXEL_PACK_BUFFER_BINDING = 0x88ED;

    /** Reused scratch buffer for the direct-read probe below. */
    private static ByteBuffer probeCopy;
    private static int probeSize;
    private static long probeFrames;
    private static int probeBlankStreak;

    /**
     * Diagnostic probe: after Flashback's PBO read, read the same framebuffer
     * region straight into client memory (no PBO) and check for content. This
     * tells a black flip-blit / wrong READ binding apart from a broken PBO
     * pack path: if the direct read has pixels while the PBO stream is blank,
     * only the PBO path is broken; if both are blank, the source framebuffer
     * itself holds nothing at read time. Read-only with respect to GL state
     * that matters (rebinds the PBO Flashback expects to still be bound).
     */
    private static void probeDirectRead(int width, int height, int format, int type) {
        try {
            int readBinding = GL30C.glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            long bytes = (long) width * height * 4;
            if (bytes <= 0 || bytes > Integer.MAX_VALUE) return;
            int size = (int) bytes;
            if (probeCopy == null || probeSize != size) {
                ByteBuffer fresh = MemoryUtil.memAlloc(size);
                if (probeCopy != null) {
                    try {
                        MemoryUtil.memFree(probeCopy);
                    } catch (Throwable ignored) {
                        // best effort
                    }
                }
                probeCopy = fresh;
                probeSize = size;
            }
            // Client-memory reads need no pack buffer bound -- but Flashback's
            // real PBO is bound to this target right now and must come back
            // bound when we're done, or its own glReadPixels write never lands
            // (this was the actual cause of the all-zero PBO readback: this
            // probe used to leave GL_PIXEL_PACK_BUFFER bound to 0 for the rest
            // of the frame instead of restoring it).
            int realPbo = GL30C.glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);
            probeCopy.clear();
            GL30C.glReadPixels(0, 0, width, height, format, type, probeCopy);
            int err = GL30C.glGetError();
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, realPbo);
            if (err != GL30C.GL_NO_ERROR) {
                if (probeFrames == 0) {
                    LOGGER.warn("pbo probe: direct glReadPixels failed, GL error 0x{} (readBinding={})",
                            Integer.toHexString(err), readBinding);
                }
                return;
            }
            boolean blank = true;
            int samples = Math.min(256, size / 4);
            int stride = Math.max(1, (size / 4) / samples);
            for (int i = 0; i < samples; i++) {
                if (probeCopy.getInt(i * stride * 4) != 0) {
                    blank = false;
                    break;
                }
            }
            probeFrames++;
            if (blank) {
                probeBlankStreak++;
                if (probeBlankStreak == 1 || probeBlankStreak % 600 == 0) {
                    LOGGER.warn("pbo probe: direct read is blank too ({} consecutive, readBinding={})",
                            probeBlankStreak, readBinding);
                }
            } else if (probeBlankStreak > 0) {
                LOGGER.warn("pbo probe: direct read HAS pixels after {} blank reads (readBinding={})",
                        probeBlankStreak, readBinding);
                probeBlankStreak = 0;
            } else if (probeFrames == 1) {
                LOGGER.warn("pbo probe: direct read has pixels on first frame (readBinding={})",
                        readBinding);
            }
        } catch (Throwable t) {
            LOGGER.warn("pbo probe: direct read failed", t);
        }
    }

    /**
     * Flashback unmaps whatever it was handed. Nothing was mapped on the
     * readback path, and unmapping an unmapped buffer is itself a GL error,
     * which would re-poison the frame and undo the fix on the next export.
     */
    @WrapOperation(
            method = "finishDownload",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/GL30C;glUnmapBuffer(I)Z",
                    remap = false
            ),
            remap = false,
            require = 0
    )
    private boolean flashbackRedroided$skipUnmap(
            int target,
            Operation<Boolean> original
    ) {
        if (usedReadback) {
            return true;
        }
        return original.call(target);
    }
}