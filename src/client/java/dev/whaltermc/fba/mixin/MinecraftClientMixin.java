// Copyright (c) 2026 WhalterMC. All Rights Reserved.

package dev.whaltermc.fba.mixin;

import dev.whaltermc.fba.AndroidInput;
import dev.whaltermc.fba.dialog.AndroidFileDialogs;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pumps queued file-dialog requests so the picker is raised from the client
 * thread, rather than from whatever ImGui/screen code asked for it.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void flashbackRedroided$pumpFileDialogs(CallbackInfo ci) {
        if (!AndroidInput.isMobile()) {
            return;
        }
        try {
            AndroidFileDialogs.tick();
        } catch (Throwable ignored) {
            // Never let the dialog plumbing break a tick
        }
    }
}