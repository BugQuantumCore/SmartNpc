package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;

public class RareSneakGoal extends Goal {
    private static final double RANGE = 8.0D;

    private final PlayerNpcEntity playerNpc;
    private int sneakTicks;

    public RareSneakGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.playerNpc.level().isClientSide
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getRareSneakCooldown() > 0
                || this.playerNpc.getRandom().nextFloat() > 0.06F) {
            return false;
        }

        AABB area = this.playerNpc.getBoundingBox().inflate(RANGE);
        return !this.playerNpc.level().getEntitiesOfClass(LivingEntity.class, area, this::isPlayerLike).isEmpty();
    }

    @Override
    public boolean canContinueToUse() {
        return this.sneakTicks > 0
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null;
    }

    @Override
    public void start() {
        this.sneakTicks = 40 + this.playerNpc.getRandom().nextInt(50);
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setShiftKeyDown(true);
        this.playerNpc.setPose(Pose.CROUCHING);
        this.playerNpc.setCurrentAiState("ai.player_npc.sneaking");
    }

    @Override
    public void tick() {
        this.sneakTicks--;
        boolean sneaking = (this.sneakTicks / 5) % 2 == 0;
        this.playerNpc.setShiftKeyDown(sneaking);
        this.playerNpc.setPose(sneaking ? Pose.CROUCHING : Pose.STANDING);
    }

    @Override
    public void stop() {
        this.playerNpc.setShiftKeyDown(false);
        this.playerNpc.setPose(Pose.STANDING);
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setRareSneakCooldown(20 * 90 + this.playerNpc.getRandom().nextInt(20 * 180));
        }
        this.sneakTicks = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean isPlayerLike(LivingEntity entity) {
        return entity != this.playerNpc && (entity instanceof Player || entity instanceof PlayerNpcEntity);
    }
}
