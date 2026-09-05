package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.pathfinder.Path;

import java.util.EnumSet;

/** Non-worker movement used only after a TEAMUP invitation has been accepted. */
public final class FollowTeamLeaderGoal extends Goal {
    private static final double START_DISTANCE_SQR = 5.0D * 5.0D;
    private static final double STOP_DISTANCE_SQR = 2.5D * 2.5D;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final float PATH_NODE_MULTIPLIER = 0.05F;
    private static final int STUCK_RECOVERY_TICKS = 20 * 3;
    private static final int RECOVERY_REQUEST_TICKS = 20 * 20;
    private static final int RECOVERY_RETRY_TICKS = 20 * 10;
    private static final int FOLLOW_WAYPOINT_HORIZONTAL_DISTANCE = 24;
    private static final int RECOVERY_WAYPOINT_HORIZONTAL_DISTANCE = 8;
    private static final int MAX_RECOVERY_CLIMB_BLOCKS = 12;

    private final PlayerNpcEntity follower;
    private final double speed;
    private LivingEntity leader;
    private int repathTicks;
    private BlockPos noPathFeet;
    private int noPathSinceTick;
    private int nextRecoveryTick;
    private boolean followMoveIssued;

    public FollowTeamLeaderGoal(PlayerNpcEntity follower, double speed) {
        this.follower = follower;
        this.speed = Math.min(1.1D, Math.max(0.1D, speed));
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        this.leader = PlayerNpcTeamUpManager.resolveLeader(this.follower);
        return this.canFollowLeader()
                && this.follower.distanceToSqr(this.leader) > START_DISTANCE_SQR;
    }

    @Override
    public boolean canContinueToUse() {
        return this.canFollowLeader()
                && this.follower.distanceToSqr(this.leader) > STOP_DISTANCE_SQR;
    }

    @Override
    public void start() {
        this.repathTicks = Math.floorMod(this.follower.getUUID().hashCode(), REPATH_INTERVAL_TICKS + 1);
        this.followMoveIssued = false;
        this.resetNoPathWatch();
        this.follower.setCurrentAiState("ai.player_npc.following_team_leader");
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (!this.canFollowLeader()) {
            return;
        }
        this.follower.getLookControl().setLookAt(this.leader, 35.0F, 35.0F);
        this.watchNoProgressAndRequestRecovery();
        if (this.repathTicks-- > 0) {
            return;
        }
        this.repathTicks = REPATH_INTERVAL_TICKS;
        if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.follower)) {
            return;
        }
        BlockPos target = this.followWaypoint(this.follower.blockPosition(), this.leader.blockPosition());
        Path path = PathNavigationAi.createBoundedPath(this.follower, target, PATH_NODE_MULTIPLIER);
        this.followMoveIssued = true;
        if (path != null && path.canReach()) {
            this.follower.getNavigation().moveTo(path, this.speed);
        }
        this.updateDetail();
    }

    @Override
    public void stop() {
        this.follower.getNavigation().stop();
        this.follower.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.follower.setCurrentAiDetail("");
        if (!this.follower.isTeamFollower()
                || this.follower.getTarget() != null
                || this.leader == null
                || !this.leader.isAlive()
                || this.leader.isRemoved()) {
            this.follower.clearTeamFollowUpwardEscapeTarget();
        }
        this.resetNoPathWatch();
        this.followMoveIssued = false;
        this.leader = null;
    }

    private boolean canFollowLeader() {
        return this.follower.isTeamFollower()
                && this.leader != null
                && this.leader.isAlive()
                && !this.leader.isRemoved()
                && this.leader.level() == this.follower.level()
                && this.follower.isAlive()
                && !this.follower.isNoAi()
                && !this.follower.isPassenger()
                && !this.follower.isHealing()
                && this.follower.getTarget() == null;
    }

    private void updateDetail() {
        if (this.leader != null) {
            this.follower.setCurrentAiDetail("following " + this.leader.getDisplayName().getString()
                    + " (" + this.follower.getTeamName() + ")");
        }
    }

    private void watchNoProgressAndRequestRecovery() {
        if (!this.followMoveIssued) {
            return;
        }
        BlockPos feet = this.follower.blockPosition();
        if (this.noPathFeet == null || !this.noPathFeet.equals(feet)) {
            this.noPathFeet = feet.immutable();
            this.noPathSinceTick = this.follower.tickCount;
            return;
        }
        if (this.follower.tickCount - this.noPathSinceTick < STUCK_RECOVERY_TICKS
                || this.follower.tickCount < this.nextRecoveryTick
                || this.follower.isTeamFollowUpwardEscapeRequested()) {
            return;
        }

        BlockPos leaderPos = this.leader.blockPosition();
        BlockPos horizontalWaypoint = this.clampedHorizontalWaypoint(
                feet, leaderPos, RECOVERY_WAYPOINT_HORIZONTAL_DISTANCE
        );
        int desiredY = Math.max(feet.getY() + 2, Math.min(leaderPos.getY() + 1,
                feet.getY() + MAX_RECOVERY_CLIMB_BLOCKS - 2));
        desiredY = Math.min(desiredY, this.follower.level().getMaxBuildHeight() - 2);
        if (desiredY <= feet.getY() + 1) {
            this.nextRecoveryTick = this.follower.tickCount + RECOVERY_RETRY_TICKS;
            return;
        }
        BlockPos recoveryTarget = new BlockPos(horizontalWaypoint.getX(), desiredY, horizontalWaypoint.getZ());
        if (this.follower.level() instanceof ServerLevel serverLevel && !serverLevel.hasChunkAt(recoveryTarget)) {
            recoveryTarget = new BlockPos(feet.getX(), desiredY, feet.getZ());
        }
        int maxPillarBlocks = Math.min(MAX_RECOVERY_CLIMB_BLOCKS,
                Math.max(2, desiredY - feet.getY() + 2));
        this.follower.requestTeamFollowUpwardEscapeTo(
                recoveryTarget, RECOVERY_REQUEST_TICKS, maxPillarBlocks
        );
        this.follower.setIdleTraceDetail("team follow recovery requested after stalled movement @ "
                + feet.getX() + " " + feet.getY() + " " + feet.getZ()
                + " -> " + recoveryTarget.getX() + " " + recoveryTarget.getY() + " "
                + recoveryTarget.getZ(), 20 * 4);
        this.nextRecoveryTick = this.follower.tickCount + RECOVERY_RETRY_TICKS;
        this.noPathSinceTick = this.follower.tickCount;
    }

    private BlockPos followWaypoint(BlockPos feet, BlockPos leaderPos) {
        BlockPos horizontal = this.clampedHorizontalWaypoint(
                feet, leaderPos, FOLLOW_WAYPOINT_HORIZONTAL_DISTANCE
        );
        int dy = Math.max(-8, Math.min(8, leaderPos.getY() - feet.getY()));
        return new BlockPos(horizontal.getX(), feet.getY() + dy, horizontal.getZ());
    }

    private BlockPos clampedHorizontalWaypoint(BlockPos feet, BlockPos target, int maxDistance) {
        int dx = target.getX() - feet.getX();
        int dz = target.getZ() - feet.getZ();
        double length = Math.sqrt((double) dx * dx + (double) dz * dz);
        if (length <= maxDistance || length <= 0.0D) {
            return new BlockPos(target.getX(), feet.getY(), target.getZ());
        }
        int x = feet.getX() + (int) Math.round(dx * maxDistance / length);
        int z = feet.getZ() + (int) Math.round(dz * maxDistance / length);
        return new BlockPos(x, feet.getY(), z);
    }

    private void resetNoPathWatch() {
        this.noPathFeet = null;
        this.noPathSinceTick = 0;
    }
}
