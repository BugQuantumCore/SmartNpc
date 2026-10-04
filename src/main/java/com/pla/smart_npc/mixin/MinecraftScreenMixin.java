package com.pla.smart_npc.mixin;

import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric port of Forge's {@code ScreenEvent.Opening} (cancellable) for the one
 * place the mod uses it: vetoing the vanilla inventory screen while an
 * inspectator session is active and the inventory screen would be opened by the
 * toggle key.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftScreenMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void smartNpc$onScreenOpening(Screen screen, CallbackInfo ci) {
        if (screen != null && SmartNpcInspectorOverlay.shouldBlockScreenOpening(screen)) {
            ci.cancel();
        }
    }
}
