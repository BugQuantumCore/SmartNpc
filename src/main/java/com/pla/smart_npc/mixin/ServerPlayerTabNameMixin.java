package com.pla.smart_npc.mixin;

import com.pla.smart_npc.util.PlayerNpcForceTickManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fabric port of Forge's {@code PlayerEvent.TabListNameFormat}: gives the force-tick
 * manager a chance to swap the tab list display name of a managed (fake-tab) player.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTabNameMixin {
    @Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
    private void smartNpc$overrideTabListName(CallbackInfoReturnable<Component> cir) {
        Component custom = PlayerNpcForceTickManager.getTabListDisplayNameOverride((ServerPlayer) (Object) this);
        if (custom != null) {
            cir.setReturnValue(custom);
        }
    }
}
