package com.whaltermc.mixin;

import com.whaltermc.MobileCompat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.moulberry.flashback.editor.ui.ReplayUI", remap = false, priority = 1000)
public abstract class ReplayUIMobileCursorMixin {

    @WrapOperation(
            method = "transitionActiveState",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwSetInputMode(JII)V",
                    remap = false
            ),
            remap = false,
            require = 0
    )
    private static void flashbackRedroided$setCursorMode(Operation<Void> original, long window, int mode, int value) {
        if (mode != org.lwjgl.glfw.GLFW.GLFW_CURSOR || !MobileCompat.isMobile()) {
            original.call(window, mode, value);
            if (mode == org.lwjgl.glfw.GLFW.GLFW_CURSOR) {
                MobileCompat.syncCursorMode(window, value);
            }
        } else {
            MobileCompat.requestCursorMode(window, value);
        }
    }

    @WrapOperation(
            method = "transitionActiveState",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwSetCursorPos(JDD)V",
                    remap = false
            ),
            remap = false,
            require = 0
    )
    private static void flashbackRedroided$setCursorPos(Operation<Void> original, long window, double x, double y) {
        if (!MobileCompat.isMobile()) {
            original.call(window, x, y);
        } else {
            MobileCompat.setCursorPos(window, x, y);
        }
    }
}
