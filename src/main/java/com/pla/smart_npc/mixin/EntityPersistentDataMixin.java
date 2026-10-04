package com.pla.smart_npc.mixin;

import com.pla.smart_npc.util.compat.PersistentDataHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Re-implements Forge 1.20.1's {@code Entity#getPersistentData()} semantics on Fabric:
 * a mod-owned {@link CompoundTag} that is stored under {@code "ForgeData"} when the
 * entity is written to NBT and read back on load.
 */
@Mixin(Entity.class)
public abstract class EntityPersistentDataMixin implements PersistentDataHolder {
    @Unique
    private CompoundTag smartNpc$persistentData;

    @Override
    public CompoundTag getPersistentData() {
        if (this.smartNpc$persistentData == null) {
            this.smartNpc$persistentData = new CompoundTag();
        }
        return this.smartNpc$persistentData;
    }

    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void smartNpc$writePersistentData(CompoundTag tag, CallbackInfoReturnable<Boolean> cir) {
        if (this.smartNpc$persistentData != null && !this.smartNpc$persistentData.isEmpty()) {
            tag.put("ForgeData", this.smartNpc$persistentData.copy());
        }
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void smartNpc$readPersistentData(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains("ForgeData", Tag.TAG_COMPOUND)) {
            this.smartNpc$persistentData = tag.getCompound("ForgeData");
        }
    }
}
