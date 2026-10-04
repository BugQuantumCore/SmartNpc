package com.pla.smart_npc.util.compat;

import com.pla.smart_npc.util.compat.ForgeDataCompat;
import net.minecraft.nbt.CompoundTag;

/**
 * Fabric port of Forge's {@code Entity#getPersistentData()}.
 *
 * Implemented on every {@link net.minecraft.world.entity.Entity} through
 * {@link com.pla.smart_npc.mixin.EntityPersistentDataMixin}. The tag is stored
 * under the {@code "ForgeData"} key when the entity is serialized, exactly like
 * Forge 1.20.1 does, which keeps world NBT compatible between the two loaders.
 *
 * Because the method is duck-typed onto Entity itself, every call site written
 * against Forge's API (e.g. {@code ForgeDataCompat.get(npc).putLong(...)})
 * keeps compiling without any source change.
 */
public interface PersistentDataHolder {
    CompoundTag getPersistentData();
}
