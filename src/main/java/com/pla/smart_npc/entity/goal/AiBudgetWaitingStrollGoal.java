package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

/**
 * Very small local movement fallback for an NPC waiting for a scheduled routine-work turn.
 * It intentionally uses MoveControl instead of creating a navigation path.
 */
public final class AiBudgetWaitingStrollGoal extends Goal {
    private static final int MAX_STROLL_TICKS = 20 * 3;
    private static final int RETRY_COOLDOWN_TICKS = 20;
    private static final int TARGET_ATTEMPTS = 6;
    private static final double ARRIVAL_DISTANCE_SQR = 0.8D * 0.8D;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private BlockPos target;
    private int strollTicks;
    private int retryCooldownTicks;

    public AiBudgetWaitingStrollGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.retryCooldownTicks > 0) {
            this.retryCooldownTicks--;
            return false;
        }
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.isInWaterOrBubble()
                || !this.playerNpc.onGround()
                || !PlayerNpcAiWorkBudget.isWaitingForTurn(this.playerNpc)) {
            return false;
        }

        this.target = this.findTarget(serverLevel);
        if (this.target == null) {
            this.retryCooldownTicks = RETRY_COOLDOWN_TICKS;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.target != null
                && this.strollTicks < MAX_STROLL_TICKS
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && !this.playerNpc.isInWaterOrBubble()
                && PlayerNpcAiWorkBudget.isWaitingForTurn(this.playerNpc)
                && this.playerNpc.distanceToSqr(
                this.target.getX() + 0.5D,
                this.target.getY(),
                this.target.getZ() + 0.5D
        ) > ARRIVAL_DISTANCE_SQR;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.strollTicks = 0;
        this.playerNpc.setIdleTraceDetail("waiting for AI worker turn; strolling nearby", MAX_STROLL_TICKS);
        this.moveTowardTarget();
    }

    @Override
    public void tick() {
        this.strollTicks++;
        this.moveTowardTarget();
    }

    @Override
    public void stop() {
        this.playerNpc.getMoveControl().setWantedPosition(
                this.playerNpc.getX(),
                this.playerNpc.getY(),
                this.playerNpc.getZ(),
                0.0D
        );
        this.target = null;
        this.retryCooldownTicks = RETRY_COOLDOWN_TICKS;
    }

    private void moveTowardTarget() {
        if (this.target == null) {
            return;
        }
        this.playerNpc.getLookControl().setLookAt(
                this.target.getX() + 0.5D,
                this.target.getY() + 0.5D,
                this.target.getZ() + 0.5D,
                20.0F,
                20.0F
        );
        this.playerNpc.getMoveControl().setWantedPosition(
                this.target.getX() + 0.5D,
                this.target.getY(),
                this.target.getZ() + 0.5D,
                this.speed
        );
    }

    private BlockPos findTarget(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        for (int attempt = 0; attempt < TARGET_ATTEMPTS; attempt++) {
            int dx = this.playerNpc.getRandom().nextInt(5) - 2;
            int dz = this.playerNpc.getRandom().nextInt(5) - 2;
            if (dx == 0 && dz == 0) {
                continue;
            }
            BlockPos candidate = feet.offset(dx, 0, dz);
            if (!serverLevel.hasChunkAt(candidate)
                    || !this.isSafeShortStep(serverLevel, feet, dx, dz)) {
                continue;
            }
            return candidate.immutable();
        }
        return null;
    }

    private boolean isSafeShortStep(ServerLevel serverLevel, BlockPos origin, int dx, int dz) {
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        for (int step = 1; step <= steps; step++) {
            BlockPos stepPos = origin.offset(
                    Math.round((float) dx * step / steps),
                    0,
                    Math.round((float) dz * step / steps)
            );
            if (!serverLevel.hasChunkAt(stepPos) || !this.isSafeStand(serverLevel, stepPos)) {
                return false;
            }
        }
        return true;
    }

    private boolean isSafeStand(ServerLevel serverLevel, BlockPos feet) {
        BlockState feetState = serverLevel.getBlockState(feet);
        BlockState headState = serverLevel.getBlockState(feet.above());
        BlockPos floor = feet.below();
        return feetState.canBeReplaced()
                && feetState.getFluidState().isEmpty()
                && headState.canBeReplaced()
                && headState.getFluidState().isEmpty()
                && serverLevel.getBlockState(floor).isSolidRender(serverLevel, floor);
    }
}
