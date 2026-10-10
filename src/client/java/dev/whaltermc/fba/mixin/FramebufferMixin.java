// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;

@Mixin(targets = "com.moulberry.flashback.exporting.SaveableFramebuffer", remap = false, priority = 1000)
public abstract class FramebufferMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("flashback_android");

    private static final java.util.concurrent.atomic.AtomicBoolean ENTRY_LOG =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static String glErr() {
        int e = GL11C.glGetError();
        return switch (e) {
            case GL11C.GL_NO_ERROR -> "GL_NO_ERROR";
            case GL11C.GL_INVALID_ENUM -> "GL_INVALID_ENUM";
            case GL11C.GL_INVALID_VALUE -> "GL_INVALID_VALUE";
            case GL11C.GL_INVALID_OPERATION -> "GL_INVALID_OPERATION";
            case GL11C.GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY";
            default -> "0x" + Integer.toHexString(e);
        };
    }

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
    private ByteBuffer flashbackRedroided$mapPixelBufferRange(
            int target,
            int access,
            Operation<ByteBuffer> original,
            @Local(argsOnly = true, index = 0) int width,
            @Local(argsOnly = true, index = 1) int height
    ) {
        long want = (long) width * (long) height * 4L;

        // Log once per session on entry so we can tell definitively whether
        // this handler runs at all and with what dimensions, and log every
        // path that yields null -- a silent null is indistinguishable from the
        // injection not matching.
        if (ENTRY_LOG.compareAndSet(false, true)) {
            LOGGER.warn("pbo map: handler entered, target={} access={} {}x{} want={}",
                    target, access, width, height, want);
        }

        // Never map past what was actually allocated: an over-long range is
        // GL_INVALID_VALUE and glMapBufferRange then returns null.
        long size = want;
        int allocated = -1;
        try {
            int[] bufSize = new int[1];
            GL15C.glGetBufferParameteriv(target, GL15C.GL_BUFFER_SIZE, bufSize);
            allocated = bufSize[0];
            if (allocated > 0 && allocated < size) {
                size = allocated;
            }
        } catch (Throwable t) {
            LOGGER.warn("pbo map: glGetBufferParameteriv failed (err={})", glErr(), t);
        }

        if (size <= 0 || size > Integer.MAX_VALUE) {
            LOGGER.warn("pbo map: refusing to map, size={} allocated={} {}x{} (err={})",
                    size, allocated, width, height, glErr());
            return null;
        }

        // Flashback leaves the PBO bound to GL_PIXEL_PACK_BUFFER before mapping;
        // GLES forbids mapping a buffer that is bound to a target.
        GL30C.glBindBuffer(target, 0);

        ByteBuffer mapped = null;
        try {
            mapped = GL30C.glMapBufferRange(target, 0, size, GL30C.GL_MAP_READ_BIT);
        } catch (Throwable t) {
            LOGGER.warn("pbo map: glMapBufferRange threw (err={})", glErr(), t);
        }
        if (mapped != null) {
            return mapped;
        }

        // No mapping available: read the buffer back synchronously instead.
        try {
            ByteBuffer copy = MemoryUtil.memAlloc((int) size);
            GL15C.glGetBufferSubData(target, 0, copy);
            LOGGER.warn("pbo map: fell back to glGetBufferSubData (err={})", glErr());
            return copy;
        } catch (Throwable t) {
            LOGGER.warn("pbo map: glMapBufferRange and glGetBufferSubData both failed (err={})",
                    glErr(), t);
            return null;
        }
    }
}