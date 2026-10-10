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

        // Never map past what was actually allocated: an over-long range is
        // GL_INVALID_VALUE and glMapBufferRange then returns null.
        long size = want;
        try {
            int[] bufSize = new int[1];
            GL15C.glGetBufferParameteriv(target, GL15C.GL_BUFFER_SIZE, bufSize);
            if (bufSize[0] > 0 && bufSize[0] < size) {
                size = bufSize[0];
            }
            LOGGER.warn("FBA pbo map: {}x{} want={} alloc={} errBefore={}",
                    width, height, want, bufSize[0], glErr());
        } catch (Throwable t) {
            LOGGER.warn("FBA pbo map: glGetBufferParameteriv failed", t);
        }

        if (size <= 0 || size > Integer.MAX_VALUE) {
            return null;
        }

        // Flashback leaves the PBO bound to GL_PIXEL_PACK_BUFFER before mapping;
        // GLES forbids mapping a buffer that is bound to a target.
        GL30C.glBindBuffer(target, 0);

        ByteBuffer mapped = null;
        try {
            mapped = GL30C.glMapBufferRange(target, 0, size, GL30C.GL_MAP_READ_BIT);
            LOGGER.warn("FBA pbo map: range={} mapped={} err={}", size, mapped != null, glErr());
        } catch (Throwable t) {
            LOGGER.warn("FBA pbo map: glMapBufferRange threw", t);
        }
        if (mapped != null) {
            return mapped;
        }

        // No mapping available: read the buffer back synchronously instead.
        try {
            ByteBuffer copy = MemoryUtil.memAlloc((int) size);
            GL15C.glGetBufferSubData(target, 0, copy);
            LOGGER.warn("FBA pbo map: fallback glGetBufferSubData ok err={}", glErr());
            return copy;
        } catch (Throwable t) {
            LOGGER.warn("FBA pbo map: fallback glGetBufferSubData failed", t);
            return null;
        }
    }
}