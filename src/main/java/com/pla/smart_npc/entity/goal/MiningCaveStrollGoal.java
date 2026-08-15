package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public final class MiningCaveStrollGoal extends Goal {
    private static final int HORIZONTAL_RADIUS = 8;
    private static final int VERTICAL_RADIUS = 3;
    private static final int MIN_HORIZONTAL_DISTANCE = 2;
    private static final int TARGET_ATTEMPTS = 48;
    private static final int RANDOM_TARGET_POOL = 16;
    private static final int MAX_PATH_CHECKS = 24;
    private static final int MAX_SAFE_DROP_BLOCKS = 3;
    private static final int MAX_STROLL_TICKS = 20 * 12;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MIN_RETRY_TICKS = 20 * 2;
    private static final int RANDOM_RETRY_TICKS = 20 * 3;
    private static final double ARRIVAL_DISTANCE_SQR = 1.5D * 1.5D;

    private final PlayerNpcEntity playerNpc;
    private final PathNavigationAi pathNavigationAi;
    private final double speed;
    private BlockPos strollCenter;
    private BlockPos targetPos;
    private int strollTicks;
    private int repathTicks;
    private int nextAttemptTick;

    public MiningCaveStrollGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.speed = Math.min(speed, 1.0D);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.canStrollInCurrentCave(serverLevel)
                || !this.playerNpc.onGround()
                || this.playerNpc.tickCount < this.nextAttemptTick) {
            return false;
        }

        this.strollCenter = this.playerNpc.blockPosition().immutable();
        this.targetPos = this.findStrollTarget(serverLevel, this.strollCenter);
        if (this.targetPos == null) {
            this.scheduleRetry();
            this.strollCenter = null;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.targetPos != null
                && this.strollCenter != null
                && this.strollTicks < MAX_STROLL_TICKS
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.canStrollInCurrentCave(serverLevel)
                && this.distanceToTargetSqr() > ARRIVAL_DISTANCE_SQR;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.strollTicks = 0;
        this.repathTicks = 0;
        this.playerNpc.setCurrentAiState("ai.player_npc.exploring_cave");
        this.updateDetail();
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.moveToTarget(serverLevel);
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.targetPos == null) {
            return;
        }

        this.strollTicks++;
        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY(),
                this.targetPos.getZ() + 0.5D,
                30.0F,
                30.0F
        );
        if (this.repathTicks-- <= 0
                || this.playerNpc.getNavigation().isDone()
                || this.playerNpc.getNavigation().isStuck()) {
            if (!this.moveToTarget(serverLevel)) {
                this.targetPos = null;
                return;
            }
            this.repathTicks = REPATH_INTERVAL_TICKS;
        }
        this.updateDetail();
    }

    @Override
    public void stop() {
        this.playerNpc.getNavigation().stop();
        this.strollCenter = null;
        this.targetPos = null;
        this.strollTicks = 0;
        this.repathTicks = 0;
        this.scheduleRetry();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private boolean canStrollInCurrentCave(ServerLevel serverLevel) {
        return this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
                && GatherStoneGoal.isMiningJobActive(this.playerNpc)
                && ExploreCaveOreGoal.isOreInventoryBlocked(this.playerNpc)
                && !MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)
                && !serverLevel.canSeeSky(this.playerNpc.blockPosition().above());
    }

    private BlockPos findStrollTarget(ServerLevel serverLevel, BlockPos center) {
        List<BlockPos> candidates = new ArrayList<>();
        int minDistanceSqr = MIN_HORIZONTAL_DISTANCE * MIN_HORIZONTAL_DISTANCE;
        int maxDistanceSqr = HORIZONTAL_RADIUS * HORIZONTAL_RADIUS;
        for (int attempt = 0; attempt < TARGET_ATTEMPTS; attempt++) {
            int dx = this.playerNpc.getRandom().nextInt(HORIZONTAL_RADIUS * 2 + 1) - HORIZONTAL_RADIUS;
            int dz = this.playerNpc.getRandom().nextInt(HORIZONTAL_RADIUS * 2 + 1) - HORIZONTAL_RADIUS;
            int horizontalDistanceSqr = dx * dx + dz * dz;
            if (horizontalDistanceSqr < minDistanceSqr || horizontalDistanceSqr > maxDistanceSqr) {
                continue;
            }

            int dy = this.playerNpc.getRandom().nextInt(VERTICAL_RADIUS * 2 + 1) - VERTICAL_RADIUS;
            BlockPos candidate = center.offset(dx, dy, dz).immutable();
            if (serverLevel.canSeeSky(candidate.above())
                    || !PathNavigationAi.canStandAt(serverLevel, candidate)
                    || candidates.contains(candidate)) {
                continue;
            }
            candidates.add(candidate);
        }

        return this.pathNavigationAi.findReachableRandomizedCandidate(
                serverLevel,
                candidates,
                RANDOM_TARGET_POOL,
                MAX_PATH_CHECKS,
                MAX_SAFE_DROP_BLOCKS
        ).orElse(null);
    }

    private boolean moveToTarget(ServerLevel serverLevel) {
        return this.targetPos != null
                && this.pathNavigationAi.moveTo(serverLevel, this.targetPos, this.speed, MAX_SAFE_DROP_BLOCKS);
    }

    private double distanceToTargetSqr() {
        return this.targetPos == null
                ? Double.MAX_VALUE
                : this.playerNpc.distanceToSqr(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY(),
                this.targetPos.getZ() + 0.5D
        );
    }

    private void updateDetail() {
        if (this.targetPos == null) {
            this.playerNpc.setCurrentAiDetail("strolling around mining cave");
            return;
        }
        this.playerNpc.setCurrentAiDetail("strolling around mining cave @ "
                + this.targetPos.getX() + " "
                + this.targetPos.getY() + " "
                + this.targetPos.getZ());
    }

    private void scheduleRetry() {
        this.nextAttemptTick = this.playerNpc.tickCount
                + MIN_RETRY_TICKS
                + this.playerNpc.getRandom().nextInt(RANDOM_RETRY_TICKS + 1);
    }
}
