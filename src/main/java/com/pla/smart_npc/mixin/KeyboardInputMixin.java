package com.pla.smart_npc.mixin;

import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric port of Forge's {@code MovementInputUpdateEvent}: after the vanilla
 * keyboard input is computed, the inspector overlay may zero it out while an
 * inspectator session is active.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {
    @Inject(method = "tick(ZF)V", at = @At("TAIL"))
    private void smartNpc$onMovementInputUpdate(boolean slowDown, float sensitivityMultiplier, CallbackInfo ci) {
        SmartNpcInspectorOverlay.onMovementInputUpdate(this);
    }
}
