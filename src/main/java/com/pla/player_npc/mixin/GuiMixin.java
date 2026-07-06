package com.pla.player_npc.mixin;

import com.pla.player_npc.client.gui.PlayerNpcInspectorOverlay;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "setOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void player_npc$hideInspectatorMountPrompt(Component component, boolean animateColor, CallbackInfo ci) {
        if (PlayerNpcInspectorOverlay.isInspectatorActive()
                && component.getContents() instanceof TranslatableContents contents
                && "mount.onboard".equals(contents.getKey())) {
            ci.cancel();
        }
    }
}
