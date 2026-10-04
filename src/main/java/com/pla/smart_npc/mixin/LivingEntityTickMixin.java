package com.pla.smart_npc.mixin;

import com.pla.smart_npc.event.SmartNpcEvents;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric port of Forge's {@code LivingEvent.LivingTickEvent}: dispatches every
 * living entity tick to {@link SmartNpcEvents#onLivingTick(LivingEntity)}.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void smartNpc$onLivingTick(CallbackInfo ci) {
        SmartNpcEvents.onLivingTick((LivingEntity) (Object) this);
    }
}
