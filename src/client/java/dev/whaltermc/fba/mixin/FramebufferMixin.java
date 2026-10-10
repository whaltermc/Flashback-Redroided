// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;

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
 * sensitivity. Both calls need the buffer unbound first, so we unbind up front --
 * which is what Flashback does immediately afterwards anyway.
 *
 * <p>Only if that fails do we try a range map, and only if that fails do we let
 * Flashback run its original call, so behaviour is never worse than before.
 */
@Mixin(targets = "com.moulberry.flashback.exporting.SaveableFramebuffer", remap = false, priority = 1000)
public abstract class FramebufferMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("flashback_android");

    /** True when the current call returned a plain readback buffer, not a map. */
    private static boolean usedReadback;

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
            Operation<ByteBuffer> original,
            @Local(argsOnly = true, index = 0) int width,
            @Local(argsOnly = true, index = 1) int height
    ) {
        usedReadback = false;

        long want = (long) width * (long) height * 4L;
        long size = want;

        int allocated = 0;
        try {
            int[] bufSize = new int[1];
            GL15C.glGetBufferParameteriv(target, GL15C.GL_BUFFER_SIZE, bufSize);
            allocated = bufSize[0];
        } catch (Throwable ignored) {
            // Fall back to the computed size below.
        }

        // Never read past the real allocation: an over-long range is
        // GL_INVALID_VALUE and the call returns nothing.
        if (allocated > 0 && allocated < size) {
            size = allocated;
        }

        if (size <= 0 || size > Integer.MAX_VALUE) {
            LOGGER.warn("pbo readback: bad size {} ({}x{}, allocated {})",
                    size, width, height, allocated);
            return original.call(target, access);
        }

        // GLES requires the buffer to be unbound for both of the calls below.
        GL30C.glBindBuffer(target, 0);

        ByteBuffer copy = null;
        try {
            copy = MemoryUtil.memAlloc((int) size);
            GL15C.glGetBufferSubData(target, 0, copy);
            usedReadback = true;
            return copy;
        } catch (Throwable t) {
            if (copy != null) {
                try {
                    MemoryUtil.memFree(copy);
                } catch (Throwable ignored) {
                    // best effort
                }
            }
            LOGGER.warn("pbo readback: glGetBufferSubData failed", t);
        }

        // Second choice: range map, now that the buffer is unbound.
        try {
            ByteBuffer mapped = GL30C.glMapBufferRange(target, 0, size, GL30C.GL_MAP_READ_BIT);
            if (mapped != null) {
                return mapped;
            }
        } catch (Throwable t) {
            LOGGER.warn("pbo readback: glMapBufferRange failed", t);
        }

        // Last resort: Flashback's own behaviour, so we never make it worse.
        LOGGER.warn("pbo readback: falling back to glMapBuffer ({}x{}, allocated {})",
                width, height, allocated);
        return original.call(target, access);
    }

    /**
     * Flashback unmaps whatever it was handed. Nothing was mapped on the
     * readback path, and unmapping an unmapped buffer is a GL error.
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