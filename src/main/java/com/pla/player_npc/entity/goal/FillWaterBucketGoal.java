package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class FillWaterBucketGoal extends Goal {
    private static final int SEARCH_RADIUS = 10;
    private static final int SEARCH_INTERVAL_TICKS = 40;
    private static final int MAX_USE_TICKS = 120;

    private final Mob mob;
    private final double speedModifier;
    private BlockPos fluidPos;
    private Item filledBucket;
    private long nextSearchTick;
    private int useTicks;

    public FillWaterBucketGoal(Mob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (!this.mob.isAlive()
                || this.mob.isPassenger()
                || this.mob.isNoAi()
                || this.mob.getTarget() != null
                || this.isHealing()
                || !InventoryUtils.hasItem(this.mob, Items.BUCKET)) {
            return false;
        }

        long gameTime = serverLevel.getGameTime();
        if (gameTime < this.nextSearchTick) {
            return false;
        }

        this.nextSearchTick = gameTime + SEARCH_INTERVAL_TICKS + this.mob.getRandom().nextInt(20);
        FluidTarget target = this.findNearestSourceFluid(serverLevel);
        if (target == null) {
            return false;
        }

        this.fluidPos = target.pos();
        this.filledBucket = target.filledBucket();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.fluidPos != null
                && this.filledBucket != null
                && this.useTicks < MAX_USE_TICKS
                && this.mob.getTarget() == null
                && InventoryUtils.hasItem(this.mob, Items.BUCKET)
                && this.mob.level() instanceof ServerLevel serverLevel
                && this.getFilledBucket(serverLevel, this.fluidPos) == this.filledBucket;
    }

    @Override
    public void start() {
        this.useTicks = 0;
        this.moveToFluid();
    }

    @Override
    public void stop() {
        this.fluidPos = null;
        this.filledBucket = null;
        this.useTicks = 0;
    }

    @Override
    public void tick() {
        if (!(this.mob.level() instanceof ServerLevel serverLevel) || this.fluidPos == null || this.filledBucket == null) {
            return;
        }

        this.useTicks++;
        this.mob.getLookControl().setLookAt(
                this.fluidPos.getX() + 0.5D,
                this.fluidPos.getY() + 0.5D,
                this.fluidPos.getZ() + 0.5D,
                30.0F,
                30.0F
        );

        if (this.mob.distanceToSqr(Vec3.atCenterOf(this.fluidPos)) > 4.0D) {
            if (this.useTicks % 20 == 0) {
                this.moveToFluid();
            }
            return;
        }

        if (this.getFilledBucket(serverLevel, this.fluidPos) != this.filledBucket
                || InventoryUtils.consumeItem(this.mob, Items.BUCKET, 1).isEmpty()) {
            return;
        }

        serverLevel.setBlockAndUpdate(this.fluidPos, Blocks.AIR.defaultBlockState());
        ItemStack filledStack = new ItemStack(this.filledBucket);
        if (!InventoryUtils.addItem(this.mob, filledStack)) {
            this.mob.spawnAtLocation(filledStack);
        }
        serverLevel.playSound(null, this.fluidPos, SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
        this.stop();
    }

    private void moveToFluid() {
        if (this.fluidPos == null) {
            return;
        }

        this.mob.getNavigation().moveTo(
                this.fluidPos.getX() + 0.5D,
                this.fluidPos.getY(),
                this.fluidPos.getZ() + 0.5D,
                this.speedModifier
        );
    }

    private FluidTarget findNearestSourceFluid(ServerLevel serverLevel) {
        BlockPos origin = this.mob.blockPosition();
        FluidTarget best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    Item filledBucket = this.getFilledBucket(serverLevel, pos);
                    if (filledBucket == null) {
                        continue;
                    }

                    double distance = origin.distSqr(pos);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new FluidTarget(pos.immutable(), filledBucket);
                    }
                }
            }
        }

        return best;
    }

    private Item getFilledBucket(ServerLevel serverLevel, BlockPos pos) {
        FluidState fluidState = serverLevel.getFluidState(pos);
        if (!fluidState.isSource()) {
            return null;
        }
        if (fluidState.is(FluidTags.WATER)) {
            return Items.WATER_BUCKET;
        }
        if (fluidState.is(FluidTags.LAVA)) {
            return Items.LAVA_BUCKET;
        }
        return null;
    }

    private boolean isHealing() {
        if (this.mob instanceof PlayerNpcEntity playerNpcEntity) {
            return playerNpcEntity.isHealing();
        }
        return false;
    }

    private record FluidTarget(BlockPos pos, Item filledBucket) {}
}
