package com.pla.smart_npc.mixin;

import com.pla.smart_npc.util.compat.PersistentDataHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-implements Forge 1.20.1's {@code BlockEntity#getPersistentData()} on Fabric:
 * a mod-owned {@link CompoundTag} stored under {@code "ForgeData"} when the block
 * entity is written to NBT and read back on load, exactly like the Forge patch.
 */
@Mixin(BlockEntity.class)
public abstract class BlockEntityPersistentDataMixin implements PersistentDataHolder {
    @Unique
    private CompoundTag smartNpc$persistentData;

    @Override
    public CompoundTag getPersistentData() {
        if (this.smartNpc$persistentData == null) {
            this.smartNpc$persistentData = new CompoundTag();
        }
        return this.smartNpc$persistentData;
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void smartNpc$readPersistentData(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains("ForgeData", Tag.TAG_COMPOUND)) {
            this.smartNpc$persistentData = tag.getCompound("ForgeData");
        }
    }

    @Inject(method = "saveAdditional", at = @At("RETURN"))
    private void smartNpc$writePersistentData(CompoundTag tag, CallbackInfo ci) {
        if (this.smartNpc$persistentData != null && !this.smartNpc$persistentData.isEmpty()) {
            tag.put("ForgeData", this.smartNpc$persistentData.copy());
        }
    }
}
