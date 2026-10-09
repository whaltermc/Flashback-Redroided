package com.whaltermc.mixin;

import com.whaltermc.MobileCompat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(
        targets = "com.moulberry.flashback.editor.ui.CustomImGuiImplGlfw",
        remap = false
)
public abstract class CustomImGuiImplGlfwMixin {

    @Inject(
            method = "updateKeyModifiers",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void flashbackRedroided$updateKeyModifiers(long window, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(
            method = "glfwKeyToImGuiKey",
            at = @At("RETURN"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private void flashbackRedroided$updateImGui192KeyValues(
            int glfwKey,
            CallbackInfoReturnable<Integer> cir
    ) {
        int imguiKey = cir.getReturnValue();
        cir.setReturnValue(com.whaltermc.FlashbackImGuiKeyMapper.map(imguiKey));
    }

    @WrapOperation(
            method = {"ungrab", "setGrabbed"},
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwSetInputMode(JII)V",
                    remap = false
            ),
            remap = false,
            require = 0
    )
    private void flashbackRedroided$setGrabCursorMode(Operation<Void> original, long window, int mode, int value) {
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
            method = "updateMouseCursor",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwSetInputMode(JII)V",
                    remap = false
            ),
            remap = false,
            require = 0
    )
    private void flashbackRedroided$setCursorModeIfChanged(Operation<Void> original, long window, int mode, int value) {
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
            method = {"ungrab", "updateMousePosAndButtons"},
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwSetCursorPos(JDD)V",
                    remap = false
            ),
            remap = false,
            require = 0
    )
    private void flashbackRedroided$setCursorPos(Operation<Void> original, long window, double x, double y) {
        if (!MobileCompat.isMobile()) {
            original.call(window, x, y);
        } else {
            MobileCompat.setCursorPos(window, x, y);
        }
    }

    @WrapOperation(
            method = {"updateReleaseAllKeys", "updateMouseCursor"},
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwSetCursor(JJ)V",
                    remap = false
            ),
            remap = false,
            require = 0
    )
    private void flashbackRedroided$setCursorShape(Operation<Void> original, long window, long cursor) {
        if (!MobileCompat.isMobile()) {
            original.call(window, cursor);
        } else {
            MobileCompat.setCursorShape(window, cursor);
        }
    }
}
