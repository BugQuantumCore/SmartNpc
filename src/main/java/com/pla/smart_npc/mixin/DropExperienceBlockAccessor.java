package com.pla.smart_npc.mixin;

import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.DropExperienceBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Fabric port of Forge's {@code BlockState#getExpDrop(Level, RandomSource, BlockPos, int, int)}:
 * exposes the XP range of vanilla experience-dropping blocks so the mod can award
 * the experience directly (to NPCs) instead of spawning orbs.
 */
@Mixin(DropExperienceBlock.class)
public interface DropExperienceBlockAccessor {
    @Accessor("xpRange")
    IntProvider smartNpc$getExperienceRange();
}
