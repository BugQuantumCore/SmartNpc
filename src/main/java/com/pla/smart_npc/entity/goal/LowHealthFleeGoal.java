package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class LowHealthFleeGoal extends Goal {
    private static final float START_HEALTH_RATIO = 0.40F;
    private static final float STOP_HEALTH_RATIO = 0.65F;
    private static final double START_FLEE_DISTANCE_SQR = 18.0D * 18.0D;
    private static final double STOP_FLEE_DISTANCE_SQR = 26.0D * 26.0D;
    private static final double RUN_SPEED = 1.0D;
    private static final int MIN_FLEE_TICKS = 70;
    private static final int MAX_FLEE_TICKS = 130;
    private static final int PATH_RECALCULATE_TICKS = 10;
    private static final int MIN_JUMP_COOLDOWN_TICKS = 12;
    private static final int MAX_JUMP_COOLDOWN_TICKS = 28;
    private static final int POST_FLEE_ESCAPE_COOLDOWN_TICKS = 20 * 5;

    private final PlayerNpcEntity playerNpc;
    private LivingEntity threat;
    private Vec3 fleePos;
    private int fleeTicks;
    private int pathRecalculateTicks;
    private int jumpCooldownTicks;

    public LowHealthFleeGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = this.playerNpc.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (!this.canFleeFrom(target)) {
            return false;
        }

        double distanceSqr = this.playerNpc.distanceToSqr(target);
        float startHealthRatio = this.getStartHealthRatio(target);
        if (distanceSqr > START_FLEE_DISTANCE_SQR && this.getHealthRatio() > startHealthRatio * 0.65F) {
            return false;
        }
        if (!this.isHighDangerThreat(target)
                && InventoryUtils.hasHealingFood(this.playerNpc)
                && this.playerNpc.getRandom().nextFloat() < 0.45F) {
            return false;
        }

        Vec3 pos = this.findFleePos(target);
        if (pos == null) {
            return false;
        }

        this.threat = target;
        this.fleePos = pos;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.fleeTicks > 0
                && this.threat != null
                && this.threat.isAlive()
                && this.canMoveForFlee()
                && this.getHealthRatio() < this.getStopHealthRatio(this.threat)
                && this.playerNpc.distanceToSqr(this.threat) < STOP_FLEE_DISTANCE_SQR;
    }

    @Override
    public void start() {
        this.fleeTicks = MIN_FLEE_TICKS + this.playerNpc.getRandom().nextInt(MAX_FLEE_TICKS - MIN_FLEE_TICKS + 1);
        this.pathRecalculateTicks = 0;
        this.jumpCooldownTicks = this.nextJumpCooldown();
        this.playerNpc.clearUpwardEscapeTarget();
        this.playerNpc.setHoleEscapeCooldown(POST_FLEE_ESCAPE_COOLDOWN_TICKS);
        this.playerNpc.setSprinting(true);
        this.playerNpc.setTarget(null);
        this.playerNpc.setCurrentAiState("ai.player_npc.fleeing_low_health");
        this.moveToFleePos();
    }

    @Override
    public void stop() {
        this.playerNpc.setSprinting(false);
        this.threat = null;
        this.fleePos = null;
        this.fleeTicks = 0;
        this.pathRecalculateTicks = 0;
        this.jumpCooldownTicks = 0;
        this.playerNpc.clearUpwardEscapeTarget();
        this.playerNpc.setHoleEscapeCooldown(POST_FLEE_ESCAPE_COOLDOWN_TICKS);
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    @Override
    public void tick() {
        this.fleeTicks--;
        this.playerNpc.setTarget(null);

        if (this.threat == null) {
            return;
        }

        if (this.pathRecalculateTicks-- <= 0 || this.playerNpc.getNavigation().isDone()) {
            Vec3 nextPos = this.findFleePos(this.threat);
            if (nextPos != null) {
                this.fleePos = nextPos;
                this.moveToFleePos();
            }
            this.pathRecalculateTicks = PATH_RECALCULATE_TICKS;
        }

        if (this.fleePos != null) {
            this.playerNpc.getLookControl().setLookAt(this.fleePos.x, this.fleePos.y, this.fleePos.z, 60.0F, 60.0F);
        }

        this.tryJump();
    }

    private boolean canFleeFrom(LivingEntity threat) {
        return this.canMoveForFlee()
                && this.getHealthRatio() <= this.getStartHealthRatio(threat);
    }

    private boolean canMoveForFlee() {
        return this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isInWaterOrBubble()
                && !this.playerNpc.isInLava();
    }

    private float getHealthRatio() {
        return this.playerNpc.getHealth() / this.playerNpc.getMaxHealth();
    }

    private float getStartHealthRatio(LivingEntity threat) {
        return this.playerNpc.getSmartNpcFleeHealthRatio(threat, START_HEALTH_RATIO);
    }

    private float getStopHealthRatio(LivingEntity threat) {
        float startHealthRatio = this.getStartHealthRatio(threat);
        return Math.max(STOP_HEALTH_RATIO, Math.min(0.95F, startHealthRatio + 0.15F));
    }

    private boolean isHighDangerThreat(LivingEntity threat) {
        return threat != null && this.playerNpc.isSmartNpcCompatHighDangerThreat(threat);
    }

    private Vec3 findFleePos(LivingEntity threat) {
        Vec3 pos = DefaultRandomPos.getPosAway(this.playerNpc, 18, 7, threat.position());
        if (pos != null) {
            return pos;
        }

        Vec3 away = this.playerNpc.position().subtract(threat.position());
        if (away.lengthSqr() < 1.0E-4D) {
            away = Vec3.directionFromRotation(0.0F, this.playerNpc.getYRot());
        }
        return this.playerNpc.position().add(away.normalize().scale(16.0D));
    }

    private void moveToFleePos() {
        if (this.fleePos != null) {
            this.playerNpc.getNavigation().moveTo(this.fleePos.x, this.fleePos.y, this.fleePos.z, RUN_SPEED);
        }
    }

    private void tryJump() {
        if (this.jumpCooldownTicks > 0) {
            this.jumpCooldownTicks--;
            return;
        }

        if (this.playerNpc.onGround()
                && !this.playerNpc.isInWaterOrBubble()
                && !this.playerNpc.isInLava()) {
            this.playerNpc.jump();
        }
        this.jumpCooldownTicks = this.nextJumpCooldown();
    }

    private int nextJumpCooldown() {
        return MIN_JUMP_COOLDOWN_TICKS
                + this.playerNpc.getRandom().nextInt(MAX_JUMP_COOLDOWN_TICKS - MIN_JUMP_COOLDOWN_TICKS + 1);
    }
}
