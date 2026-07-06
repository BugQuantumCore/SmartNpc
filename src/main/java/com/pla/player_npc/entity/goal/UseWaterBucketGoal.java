package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;

import java.util.EnumSet;

public class UseWaterBucketGoal extends Goal {
    private static final int PICKUP_DELAY_TICKS = 18;
    private static final int MAX_PICKUP_TICKS = 60;

    private final PlayerNpcEntity playerNpc;
    private BlockPos placePos;
    private BlockPos placedWaterPos;
    private int pickupDelayTicks;
    private int pickupTicks;
    private boolean finished;

    public UseWaterBucketGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || !InventoryUtils.hasItem(this.playerNpc, Items.WATER_BUCKET)
                || this.playerNpc.getBucketCooldown() > 0) {
            return false;
        }

        if (!this.playerNpc.isOnFire() && !this.playerNpc.isInLava()) {
            return false;
        }

        this.placePos = this.findPlacement(serverLevel);
        return this.placePos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.placedWaterPos != null
                && this.pickupTicks < MAX_PICKUP_TICKS
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && this.playerNpc.level() instanceof ServerLevel;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || this.placePos == null
                || InventoryUtils.consumeItem(this.playerNpc, Items.WATER_BUCKET, 1).isEmpty()) {
            this.placePos = null;
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(this.placePos.getX() + 0.5D, this.placePos.getY() + 0.5D, this.placePos.getZ() + 0.5D, 40.0F, 40.0F);
        this.playerNpc.setCurrentAiState("ai.player_npc.using_water_bucket");
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.setBlockAndUpdate(this.placePos, Blocks.WATER.defaultBlockState());
        this.placedWaterPos = this.placePos.immutable();
        this.pickupDelayTicks = PICKUP_DELAY_TICKS;
        this.pickupTicks = 0;
        this.finished = false;
        this.playerNpc.clearFire();
        this.giveOrDrop(new ItemStack(Items.BUCKET));
        serverLevel.playSound(null, this.placePos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
        this.playerNpc.setBucketCooldown();
        this.placePos = null;
    }

    @Override
    public void stop() {
        if (!this.finished
                && this.pickupDelayTicks <= 0
                && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.tryPickupWater(serverLevel);
        }

        this.placePos = null;
        this.placedWaterPos = null;
        this.pickupDelayTicks = 0;
        this.pickupTicks = 0;
        this.finished = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.placedWaterPos == null) {
            this.finished = true;
            return;
        }

        this.pickupTicks++;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(this.placedWaterPos.getX() + 0.5D, this.placedWaterPos.getY() + 0.5D, this.placedWaterPos.getZ() + 0.5D, 40.0F, 40.0F);

        if (this.pickupDelayTicks > 0) {
            this.pickupDelayTicks--;
            return;
        }

        FluidState fluidState = serverLevel.getFluidState(this.placedWaterPos);
        if (!fluidState.is(FluidTags.WATER) || !fluidState.isSource()) {
            this.finished = true;
            return;
        }

        if (this.tryPickupWater(serverLevel)) {
            this.finished = true;
        }
    }

    private BlockPos findPlacement(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos[] candidates = {
                feet,
                feet.above(),
                feet.below(),
                feet.relative(this.playerNpc.getDirection()),
                feet.relative(this.playerNpc.getDirection().getOpposite())
        };

        for (BlockPos candidate : candidates) {
            if (this.canPlaceWater(serverLevel, candidate)) {
                return candidate.immutable();
            }
        }

        return null;
    }

    private boolean canPlaceWater(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && (serverLevel.getBlockState(pos).isAir() || serverLevel.getFluidState(pos).is(FluidTags.LAVA));
    }

    private boolean tryPickupWater(ServerLevel serverLevel) {
        if (this.placedWaterPos == null) {
            return false;
        }

        FluidState fluidState = serverLevel.getFluidState(this.placedWaterPos);
        if (!fluidState.is(FluidTags.WATER) || !fluidState.isSource()) {
            return false;
        }
        if (InventoryUtils.consumeItem(this.playerNpc, Items.BUCKET, 1).isEmpty()) {
            return false;
        }

        serverLevel.setBlockAndUpdate(this.placedWaterPos, Blocks.AIR.defaultBlockState());
        this.giveOrDrop(new ItemStack(Items.WATER_BUCKET));
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(null, this.placedWaterPos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    private void giveOrDrop(ItemStack stack) {
        if (!InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }
}
