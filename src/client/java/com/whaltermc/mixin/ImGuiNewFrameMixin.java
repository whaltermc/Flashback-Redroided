package com.whaltermc.mixin;

import com.whaltermc.UiScale;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks the start of every ImGui frame so the editor can be auto-scaled.
 *
 * Targets the bundled ImGui binding, not Flashback itself, so it does not
 * depend on Flashback's internals. {@code require = 0} means a mismatch
 * only skips auto-scaling instead of crashing the game.
 */
@Mixin(targets = "imgui.moulberry92.ImGui", remap = false)
public abstract class ImGuiNewFrameMixin {

    @Inject(
            method = "newFrame()V",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private static void flashbackRedroided$applyUiScale(CallbackInfo ci) {
        UiScale.apply();
    }
}
