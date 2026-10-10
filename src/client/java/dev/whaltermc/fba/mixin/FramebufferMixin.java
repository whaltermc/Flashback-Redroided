// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.ByteBuffer;

@Mixin(targets = "com.moulberry.flashback.exporting.SaveableFramebuffer", remap = false, priority = 1000)
public abstract class FramebufferMixin {

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
        // Flashback's stock glMapBuffer does not exist on GLES/MobileGlues.
        // WrapOperation chains with other mods instead of conflicting like Redirect.
        long size = (long) width * (long) height * 4L;
        if (size <= 0 || size > Integer.MAX_VALUE) {
            return null;
        }

        // Flashback leaves the PBO bound to GL_PIXEL_PACK_BUFFER before mapping.
        // GLES forbids mapping a buffer that is bound to a target, and
        // MobileGlues surfaces that as glMapBufferRange returning null.
        // Unbinding is legal everywhere and glBindBuffer(.., 0) is what
        // Flashback does immediately afterwards anyway.
        GL30C.glBindBuffer(target, 0);

        ByteBuffer mapped = GL30C.glMapBufferRange(target, 0, size, GL30C.GL_MAP_READ_BIT);
        if (mapped != null) {
            return mapped;
        }

        // Some MobileGlues builds still refuse to map. glGetBufferSubData needs
        // no mapping at all, so fall back to a plain synchronous readback.
        ByteBuffer copy = null;
        try {
            int[] bufSize = new int[1];
            GL15C.glGetBufferParameteriv(target, GL15C.GL_BUFFER_SIZE, bufSize);
            long len = (bufSize[0] > 0 && bufSize[0] < size) ? bufSize[0] : size;
            copy = MemoryUtil.memAlloc((int) len);
            GL15C.glGetBufferSubData(target, 0, copy);
        } catch (Throwable t) {
            if (copy != null) {
                MemoryUtil.memFree(copy);
                copy = null;
            }
        }
        return copy;
    }
}