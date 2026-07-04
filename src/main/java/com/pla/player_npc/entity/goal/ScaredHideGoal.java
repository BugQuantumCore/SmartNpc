package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

public class ScaredHideGoal extends Goal {
    private static final double RANGE = 10.0D;

    private final PlayerNpcEntity playerNpc;
    private int hideTicks;

    public ScaredHideGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (this.playerNpc.level().isClientSide
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getScaredHideCooldown() > 0) {
            return false;
        }

        float chance = this.playerNpc.getHealth() < this.playerNpc.getMaxHealth() * 0.45F ? 0.12F : 0.025F;
        if (this.playerNpc.getRandom().nextFloat() > chance) {
            return false;
        }

        AABB area = this.playerNpc.getBoundingBox().inflate(RANGE);
        return this.playerNpc.level().getEntitiesOfClass(LivingEntity.class, area, entity -> entity != this.playerNpc && entity.isAlive()).size() >= 2;
    }

    @Override
    public boolean canContinueToUse() {
        return this.hideTicks > 0
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null;
    }

    @Override
    public void start() {
        this.hideTicks = 50 + this.playerNpc.getRandom().nextInt(80);
        this.playerNpc.setTarget(null);
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setShiftKeyDown(true);
        this.playerNpc.setPose(Pose.CROUCHING);
        this.playerNpc.setCurrentAiState("ai.player_npc.scared_hiding");
    }

    @Override
    public void tick() {
        this.hideTicks--;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setTarget(null);
        this.playerNpc.setShiftKeyDown(true);
        this.playerNpc.setPose(Pose.CROUCHING);
    }

    @Override
    public void stop() {
        this.playerNpc.setShiftKeyDown(false);
        this.playerNpc.setPose(Pose.STANDING);
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setScaredHideCooldown(20 * 70 + this.playerNpc.getRandom().nextInt(20 * 120));
        }
        this.hideTicks = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }
}
