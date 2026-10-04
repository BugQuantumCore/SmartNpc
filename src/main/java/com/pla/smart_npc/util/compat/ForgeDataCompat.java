package com.pla.smart_npc.util.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Compile-time bridge for Forge's {@code Entity#getPersistentData()}.
 *
 * <p>The method itself is duck-typed onto {@link Entity} by
 * {@code EntityPersistentDataMixin} (implementing {@link PersistentDataHolder}),
 * so all Forge-era call sites are rewritten to
 * {@code ForgeDataCompat.get(entity).putLong(...)} etc.</p>
 */
public final class ForgeDataCompat {
    private ForgeDataCompat() {
    }

    public static CompoundTag get(Entity entity) {
        return ((PersistentDataHolder) entity).getPersistentData();
    }

    public static CompoundTag get(BlockEntity blockEntity) {
        return ((PersistentDataHolder) blockEntity).getPersistentData();
    }
}
