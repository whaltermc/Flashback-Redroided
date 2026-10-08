package com.whaltermc.mixin;

import com.whaltermc.MobileCompat;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.moulberry.flashback.editor.ui.ReplayUI", remap = false, priority = 1000)
public abstract class ReplayUIMobileCursorMixin {

    @Redirect(
            method = "transitionActiveState",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwSetInputMode(JII)V",
                    remap = false
            ),
            remap = false
    )
    private static void flashbackRedroided$setCursorMode(long window, int mode, int value) {
        if (mode == GLFW.GLFW_CURSOR) {
            MobileCompat.requestCursorMode(window, value);
        } else {
            GLFW.glfwSetInputMode(window, mode, value);
        }
    }

    @Redirect(
            method = "transitionActiveState",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwSetCursorPos(JDD)V",
                    remap = false
            ),
            remap = false
    )
    private static void flashbackRedroided$setCursorPos(long window, double x, double y) {
        MobileCompat.setCursorPos(window, x, y);
    }
}
