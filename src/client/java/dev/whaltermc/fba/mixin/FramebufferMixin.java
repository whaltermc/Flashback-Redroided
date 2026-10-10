// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import org.lwjgl.opengl.GL30C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import java.nio.ByteBuffer;

@Mixin(targets = "com.moulberry.flashback.exporting.SaveableFramebuffer", remap = false)
public abstract class FramebufferMixin {
    @Redirect(
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
            @Local(argsOnly = true, index = 0) int width,
            @Local(argsOnly = true, index = 1) int height
    ) {
        long size = (long) width * height * 4L;
        return GL30C.glMapBufferRange(target, 0, size, GL30C.GL_MAP_READ_BIT);
    }
}