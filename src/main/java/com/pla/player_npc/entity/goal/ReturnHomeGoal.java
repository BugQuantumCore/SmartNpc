package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;
import java.util.Optional;

public class ReturnHomeGoal extends Goal {
    private static final double MIN_DISTANCE_SQR = 28.0D * 28.0D;
    private static final double STOP_DISTANCE_SQR = 4.0D * 4.0D;
    private static final int MAX_RETURN_TICKS = 20 * 20;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private BlockPos homeCenter;
    private int returnTicks;

    public ReturnHomeGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getReturnHomeCooldown() > 0) {
            return false;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return false;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        this.homeCenter = homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
        if (this.playerNpc.distanceToSqr(this.homeCenter.getX() + 0.5D, this.homeCenter.getY(), this.homeCenter.getZ() + 0.5D) < MIN_DISTANCE_SQR) {
            return false;
        }

        return this.inventoryMoreThanHalfFull() || this.playerNpc.getRandom().nextFloat() < 0.35F;
    }

    @Override
    public boolean canContinueToUse() {
        return this.homeCenter != null
                && this.returnTicks > 0
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.distanceToSqr(this.homeCenter.getX() + 0.5D, this.homeCenter.getY(), this.homeCenter.getZ() + 0.5D) > STOP_DISTANCE_SQR;
    }

    @Override
    public void start() {
        this.returnTicks = MAX_RETURN_TICKS;
        this.playerNpc.setCurrentAiState("ai.player_npc.returning_home");
        this.moveHome();
    }

    @Override
    public void tick() {
        this.returnTicks--;
        if (this.homeCenter != null) {
            this.playerNpc.getLookControl().setLookAt(this.homeCenter.getX() + 0.5D, this.homeCenter.getY(), this.homeCenter.getZ() + 0.5D, 40.0F, 40.0F);
            if (this.playerNpc.getNavigation().isDone() || this.returnTicks % 40 == 0) {
                this.moveHome();
            }
        }
    }

    @Override
    public void stop() {
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setReturnHomeCooldown(20 * 60 + this.playerNpc.getRandom().nextInt(20 * 60));
        }
        this.homeCenter = null;
        this.returnTicks = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void moveHome() {
        if (this.homeCenter != null) {
            this.playerNpc.getNavigation().moveTo(this.homeCenter.getX() + 0.5D, this.homeCenter.getY(), this.homeCenter.getZ() + 0.5D, this.speed);
        }
    }

    private boolean inventoryMoreThanHalfFull() {
        int used = 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            if (!this.playerNpc.getInventory().getItem(i).isEmpty()) {
                used++;
            }
        }
        return used > this.playerNpc.getInventory().getContainerSize() / 2;
    }
}
