// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.mixin;

import dev.whaltermc.fba.AndroidInput;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "com.moulberry.flashback.editor.ui.ReplayUI", remap = false, priority = 1000)
public abstract class CursorInputMixin {

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
        if (mode != org.lwjgl.glfw.GLFW.GLFW_CURSOR || !AndroidInput.isMobile()) {
            original.call(window, mode, value);
            if (mode == org.lwjgl.glfw.GLFW.GLFW_CURSOR) {
                AndroidInput.syncCursorMode(window, value);
            }
        } else {
            AndroidInput.requestCursorMode(window, value);
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
        if (!AndroidInput.isMobile()) {
            original.call(window, x, y);
        } else {
            AndroidInput.setCursorPos(window, x, y);
        }
    }
}
