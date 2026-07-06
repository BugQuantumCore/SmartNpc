package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class WaterEnderPearlEscapeGoal extends Goal {
    private static final int SEARCH_RADIUS = 24;
    private static final double MAX_TARGET_DISTANCE_SQR = SEARCH_RADIUS * SEARCH_RADIUS;

    private final PlayerNpcEntity playerNpc;
    private Vec3 pearlTarget;

    public WaterEnderPearlEscapeGoal(PlayerNpcEntity playerNpc) {
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
                || !this.playerNpc.isInWaterOrBubble()
                || this.playerNpc.getEnderPearlCooldown() > 0
                || !InventoryUtils.hasItem(this.playerNpc, Items.ENDER_PEARL)) {
            return false;
        }

        this.pearlTarget = this.findDryLanding(serverLevel);
        return this.pearlTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.pearlTarget == null) {
            this.pearlTarget = null;
            return;
        }

        ItemStack pearl = InventoryUtils.consumeItem(this.playerNpc, Items.ENDER_PEARL, 1).orElse(ItemStack.EMPTY);
        if (pearl.isEmpty()) {
            this.pearlTarget = null;
            return;
        }

        ItemStack previousOffhand = this.playerNpc.getOffhandItem().copy();
        ItemStack displayPearl = pearl.copy();
        displayPearl.setCount(1);
        this.playerNpc.setItemInHand(InteractionHand.OFF_HAND, displayPearl);
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(this.pearlTarget.x, this.pearlTarget.y, this.pearlTarget.z, 60.0F, 60.0F);
        this.playerNpc.setCurrentAiState("ai.player_npc.water_ender_pearl");
        this.playerNpc.swing(InteractionHand.OFF_HAND, true);

        ThrownEnderpearl thrownPearl = new ThrownEnderpearl(serverLevel, this.playerNpc);
        thrownPearl.setPos(this.playerNpc.getX(), this.playerNpc.getEyeY() - 0.1D, this.playerNpc.getZ());
        double x = this.pearlTarget.x - thrownPearl.getX();
        double y = this.pearlTarget.y - thrownPearl.getY();
        double z = this.pearlTarget.z - thrownPearl.getZ();
        double horizontalDistance = Math.sqrt(x * x + z * z);
        thrownPearl.shoot(x, y + horizontalDistance * 0.12D, z, 1.45F, 1.0F);
        serverLevel.addFreshEntity(thrownPearl);
        serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.ENDER_PEARL_THROW, SoundSource.HOSTILE, 1.0F, 1.0F);
        this.playerNpc.setEnderPearlCooldown();
        this.playerNpc.setItemInHand(InteractionHand.OFF_HAND, previousOffhand);
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.pearlTarget = null;
    }

    private Vec3 findDryLanding(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        Vec3 best = null;
        double bestDistanceSqr = Double.MAX_VALUE;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-SEARCH_RADIUS, -6, -SEARCH_RADIUS), center.offset(SEARCH_RADIUS, 8, SEARCH_RADIUS))) {
            BlockPos standPos = pos.immutable();
            if (!this.canStand(serverLevel, standPos)) {
                continue;
            }

            Vec3 landing = Vec3.atBottomCenterOf(standPos);
            double distanceSqr = this.playerNpc.position().distanceToSqr(landing);
            if (distanceSqr <= MAX_TARGET_DISTANCE_SQR && distanceSqr < bestDistanceSqr) {
                best = landing.add(0.0D, 0.1D, 0.0D);
                bestDistanceSqr = distanceSqr;
            }
        }

        return best;
    }

    private boolean canStand(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }
}
