package com.pla.smart_npc.mixin;

import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.PlayerRideableJumping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "setOverlayMessage", at = @At("HEAD"), cancellable = true)
    private void player_npc$hideInspectatorMountPrompt(Component component, boolean animateColor, CallbackInfo ci) {
        if (SmartNpcInspectorOverlay.isInspectatorActive()
                && component.getContents() instanceof TranslatableContents contents
                && "mount.onboard".equals(contents.getKey())) {
            ci.cancel();
        }
    }

    /**
     * Fabric port of the Forge {@code RenderGuiOverlayEvent.Pre} veto for
     * {@code VanillaGuiOverlay.JUMP_BAR}: hide the jump meter while an
     * inspectator session is active.
     */
    @Inject(method = "renderJumpMeter", at = @At("HEAD"), cancellable = true)
    private void player_npc$hideJumpMeterDuringInspectation(PlayerRideableJumping jumping, GuiGraphics guiGraphics, int x, CallbackInfo ci) {
        if (SmartNpcInspectorOverlay.isInspectatorActive()) {
            ci.cancel();
        }
    }
}
