package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.BedBlock;

import java.util.EnumSet;
import java.util.Optional;

public class SleepAtHomeGoal extends Goal {
    private static final double BED_DISTANCE_SQR = 4.0D * 4.0D;
    private static final int MIN_SLEEP_TICKS = 20 * 20;
    private static final int MAX_SLEEP_TICKS = 20 * 60;

    private final PlayerNpcEntity playerNpc;
    private BlockPos bedPos;
    private int sleepTicks;

    public SleepAtHomeGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !serverLevel.isNight()
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.isSleeping()
                || this.playerNpc.getSleepCooldown() > 0
                || this.playerNpc.getRandom().nextFloat() > 0.35F) {
            return false;
        }

        this.bedPos = this.findHomeBed(serverLevel);
        return this.bedPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.sleepTicks > 0
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && serverLevel.isNight();
    }

    @Override
    public void start() {
        this.sleepTicks = MIN_SLEEP_TICKS + this.playerNpc.getRandom().nextInt(MAX_SLEEP_TICKS - MIN_SLEEP_TICKS + 1);
        this.playerNpc.setCurrentAiState("ai.player_npc.sleeping");
        this.moveToBed();
    }

    @Override
    public void tick() {
        if (this.bedPos == null) {
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.bedPos.getX() + 0.5D, this.bedPos.getY() + 0.5D, this.bedPos.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.playerNpc.distanceToSqr(this.bedPos.getX() + 0.5D, this.bedPos.getY(), this.bedPos.getZ() + 0.5D) > BED_DISTANCE_SQR) {
            this.moveToBed();
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (!this.playerNpc.isSleeping()) {
            this.playerNpc.startSleeping(this.bedPos);
        }
        this.sleepTicks--;
    }

    @Override
    public void stop() {
        if (this.playerNpc.isSleeping()) {
            this.playerNpc.stopSleeping();
        }
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setSleepCooldown(20 * 90 + this.playerNpc.getRandom().nextInt(20 * 120));
        }
        this.bedPos = null;
        this.sleepTicks = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void moveToBed() {
        if (this.bedPos != null) {
            this.playerNpc.getNavigation().moveTo(this.bedPos.getX() + 0.5D, this.bedPos.getY(), this.bedPos.getZ() + 0.5D, 1.0D);
        }
    }

    private BlockPos findHomeBed(ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return null;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        for (BlockPos pos : BlockPos.betweenClosed(
                homeArea.origin(),
                homeArea.origin().offset(homeArea.width() - 1, 3, homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).getBlock() instanceof BedBlock) {
                return pos.immutable();
            }
        }
        return null;
    }
}
