package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public final class PathStuckFallbackAi {
    private static final int DEFAULT_STUCK_TICKS = 20 * 5;
    private static final int DEFAULT_ACTIVE_TICKS = 20 * 2;
    private static final int DEFAULT_RECHECK_TICKS = 20 * 3;
    private static final int DEFAULT_SEARCH_RADIUS = 5;
    private static final int DEFAULT_MAX_FALL = 16;
    private static final double DEFAULT_STEP_OFF_SPEED = 0.28D;

    private final PlayerNpcEntity playerNpc;
    private BlockPos watchFeetPos;
    private BlockPos watchTargetPos;
    private int watchStartTick;
    private int recheckTicks;
    private BlockPos stepOffStartPos;
    private BlockPos stepOffTargetPos;
    private int stepOffTicks;
    private String detail = "";

    public PathStuckFallbackAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
    }

    public boolean isRunning() {
        return this.stepOffStartPos != null && this.stepOffTargetPos != null && this.stepOffTicks > 0;
    }

    public boolean tick(ServerLevel serverLevel, String detailPrefix) {
        if (!this.isRunning()) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (!feet.equals(this.stepOffStartPos) && this.playerNpc.onGround()) {
            this.clearStepOff();
            return false;
        }

        this.stepOffTicks--;
        if (this.stepOffTicks <= 0) {
            this.clearStepOff();
            return false;
        }

        this.forceHorizontalStepOff(detailPrefix);
        return true;
    }

    public boolean watchAndStart(ServerLevel serverLevel, BlockPos routeTarget, String detailPrefix) {
        return this.watchAndStart(serverLevel, routeTarget, routeTarget, detailPrefix, pos -> false);
    }

    public boolean start(ServerLevel serverLevel, BlockPos directionTarget, String detailPrefix) {
        return this.start(serverLevel, directionTarget, detailPrefix, pos -> false);
    }

    public boolean start(
            ServerLevel serverLevel,
            BlockPos directionTarget,
            String detailPrefix,
            Predicate<BlockPos> avoidedStand
    ) {
        if (this.isRunning()) {
            return this.tick(serverLevel, detailPrefix);
        }
        if (!this.playerNpc.onGround()) {
            this.resetWatch();
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos stepOffTarget = this.findStepOffTarget(serverLevel, feet, directionTarget, avoidedStand);
        if (stepOffTarget == null) {
            this.detail = detailPrefix + " path fallback blocked: no step off @ " + posText(feet);
            this.recheckTicks = DEFAULT_RECHECK_TICKS;
            this.resetWatch();
            return false;
        }

        this.stepOffStartPos = feet.immutable();
        this.stepOffTargetPos = stepOffTarget.immutable();
        this.stepOffTicks = DEFAULT_ACTIVE_TICKS;
        this.resetWatch();
        this.playerNpc.getNavigation().stop();
        this.forceHorizontalStepOff(detailPrefix);
        return true;
    }

    public boolean watchAndStart(
            ServerLevel serverLevel,
            BlockPos routeTarget,
            BlockPos directionTarget,
            String detailPrefix,
            Predicate<BlockPos> avoidedStand
    ) {
        if (this.tick(serverLevel, detailPrefix)) {
            return true;
        }
        if (routeTarget == null || !this.playerNpc.onGround()) {
            this.resetWatch();
            return false;
        }
        if (!this.playerNpc.getNavigation().isDone() && !this.playerNpc.getNavigation().isStuck()) {
            this.resetWatch();
            return false;
        }
        if (this.recheckTicks > 0) {
            this.recheckTicks--;
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.watchFeetPos == null
                || !this.watchFeetPos.equals(feet)
                || this.watchTargetPos == null
                || !this.watchTargetPos.equals(routeTarget)) {
            this.watchFeetPos = feet.immutable();
            this.watchTargetPos = routeTarget.immutable();
            this.watchStartTick = this.playerNpc.tickCount;
            return false;
        }

        if (this.playerNpc.tickCount - this.watchStartTick < DEFAULT_STUCK_TICKS) {
            return false;
        }

        BlockPos stepOffTarget = this.findStepOffTarget(serverLevel, feet, directionTarget, avoidedStand);
        if (stepOffTarget == null) {
            this.detail = detailPrefix + " path fallback blocked: no step off @ " + posText(feet);
            this.watchStartTick = this.playerNpc.tickCount;
            this.recheckTicks = DEFAULT_RECHECK_TICKS;
            return false;
        }

        this.stepOffStartPos = feet.immutable();
        this.stepOffTargetPos = stepOffTarget.immutable();
        this.stepOffTicks = DEFAULT_ACTIVE_TICKS;
        this.playerNpc.getNavigation().stop();
        this.forceHorizontalStepOff(detailPrefix);
        return true;
    }

    public String detail(String fallback) {
        return this.detail == null || this.detail.isBlank() ? fallback : this.detail;
    }

    public void stop() {
        this.clearStepOff();
        this.resetWatch();
        this.recheckTicks = 0;
        this.detail = "";
    }

    private BlockPos findStepOffTarget(
            ServerLevel serverLevel,
            BlockPos feet,
            BlockPos directionTarget,
            Predicate<BlockPos> avoidedStand
    ) {
        List<BlockPos> candidates = new ArrayList<>();
        List<BlockPos> relaxedCandidates = new ArrayList<>();
        int radiusSqr = DEFAULT_SEARCH_RADIUS * DEFAULT_SEARCH_RADIUS;
        for (int dx = -DEFAULT_SEARCH_RADIUS; dx <= DEFAULT_SEARCH_RADIUS; dx++) {
            for (int dz = -DEFAULT_SEARCH_RADIUS; dz <= DEFAULT_SEARCH_RADIUS; dz++) {
                if (dx == 0 && dz == 0 || dx * dx + dz * dz > radiusSqr) {
                    continue;
                }

                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int fall = feet.getY() - y;
                if (fall < 0 || fall > DEFAULT_MAX_FALL) {
                    continue;
                }

                BlockPos candidate = new BlockPos(x, y, z);
                if (!PathNavigationAi.canStandAt(serverLevel, candidate)
                        || !this.canStepOffToward(serverLevel, feet, candidate)) {
                    continue;
                }

                if (avoidedStand.test(candidate)) {
                    relaxedCandidates.add(candidate.immutable());
                } else {
                    candidates.add(candidate.immutable());
                }
            }
        }

        BlockPos selected = this.selectStepOffTarget(serverLevel, candidates, feet, directionTarget);
        if (selected != null) {
            return selected;
        }
        selected = this.selectStepOffTarget(serverLevel, relaxedCandidates, feet, directionTarget);
        return selected == null ? this.findOpenPushTarget(serverLevel, feet, directionTarget) : selected;
    }

    private BlockPos selectStepOffTarget(ServerLevel serverLevel, List<BlockPos> candidates, BlockPos feet, BlockPos directionTarget) {
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> this.stepOffScore(serverLevel, feet, directionTarget, pos))
                .thenComparingDouble(pos -> horizontalDistanceSqr(feet, pos))
                .thenComparingInt(pos -> Math.abs(feet.getY() - pos.getY())));
        return candidates.isEmpty() ? null : candidates.get(0).immutable();
    }

    private double stepOffScore(ServerLevel serverLevel, BlockPos feet, BlockPos directionTarget, BlockPos candidate) {
        double score = horizontalDistanceSqr(feet, candidate) * 1.5D;
        if (directionTarget != null) {
            score += candidate.distSqr(directionTarget) * 0.12D;
        }
        if (candidate.getY() < feet.getY()) {
            score -= Math.min(12.0D, (feet.getY() - candidate.getY()) * 2.0D);
        }
        if (serverLevel.canSeeSky(candidate.above())) {
            score -= 8.0D;
        }
        return score;
    }

    private BlockPos findOpenPushTarget(ServerLevel serverLevel, BlockPos feet, BlockPos directionTarget) {
        for (Direction direction : this.directionsToward(feet, directionTarget)) {
            BlockPos adjacent = feet.relative(direction);
            if (serverLevel.isInWorldBounds(adjacent)
                    && serverLevel.getWorldBorder().isWithinBounds(adjacent)
                    && this.hasOpenBodySpace(serverLevel, adjacent)) {
                return feet.relative(direction, DEFAULT_SEARCH_RADIUS).immutable();
            }
        }
        return null;
    }

    private boolean canStepOffToward(ServerLevel serverLevel, BlockPos feet, BlockPos target) {
        Direction direction = this.firstStepDirection(feet, target);
        if (direction == null) {
            return false;
        }
        BlockPos adjacent = feet.relative(direction);
        return serverLevel.isInWorldBounds(adjacent)
                && serverLevel.getWorldBorder().isWithinBounds(adjacent)
                && this.hasOpenBodySpace(serverLevel, adjacent);
    }

    private boolean hasOpenBodySpace(ServerLevel serverLevel, BlockPos pos) {
        BlockState feetState = serverLevel.getBlockState(pos);
        BlockState headState = serverLevel.getBlockState(pos.above());
        return feetState.getCollisionShape(serverLevel, pos).isEmpty()
                && headState.getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
    }

    private Direction firstStepDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 0 && dz == 0) {
            return null;
        }
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private List<Direction> directionsToward(BlockPos from, BlockPos to) {
        List<Direction> directions = new ArrayList<>();
        if (to != null) {
            for (Direction direction : this.primaryDirectionsToward(from, to)) {
                if (!directions.contains(direction)) {
                    directions.add(direction);
                }
            }
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!directions.contains(direction)) {
                directions.add(direction);
            }
        }
        return directions;
    }

    private List<Direction> primaryDirectionsToward(BlockPos from, BlockPos to) {
        List<Direction> directions = new ArrayList<>();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        Direction xDirection = dx > 0 ? Direction.EAST : dx < 0 ? Direction.WEST : null;
        Direction zDirection = dz > 0 ? Direction.SOUTH : dz < 0 ? Direction.NORTH : null;
        if (Math.abs(dx) >= Math.abs(dz)) {
            if (xDirection != null) {
                directions.add(xDirection);
            }
            if (zDirection != null) {
                directions.add(zDirection);
            }
        } else {
            if (zDirection != null) {
                directions.add(zDirection);
            }
            if (xDirection != null) {
                directions.add(xDirection);
            }
        }
        return directions;
    }

    private void forceHorizontalStepOff(String detailPrefix) {
        if (this.stepOffStartPos == null || this.stepOffTargetPos == null) {
            return;
        }

        double targetX = this.stepOffTargetPos.getX() + 0.5D;
        double targetZ = this.stepOffTargetPos.getZ() + 0.5D;
        double dx = targetX - this.playerNpc.getX();
        double dz = targetZ - this.playerNpc.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) {
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(targetX, this.playerNpc.getY(), targetZ, 30.0F, 30.0F);
        this.playerNpc.getMoveControl().setWantedPosition(targetX, this.playerNpc.getY(), targetZ, 1.15D);
        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.setDeltaMovement(
                dx / length * DEFAULT_STEP_OFF_SPEED,
                motion.y,
                dz / length * DEFAULT_STEP_OFF_SPEED
        );
        this.playerNpc.hasImpulse = true;
        this.detail = detailPrefix + " path fallback forced step off @ "
                + posText(this.stepOffStartPos)
                + " -> "
                + posText(this.stepOffTargetPos);
    }

    private void clearStepOff() {
        this.stepOffStartPos = null;
        this.stepOffTargetPos = null;
        this.stepOffTicks = 0;
    }

    private void resetWatch() {
        this.watchFeetPos = null;
        this.watchTargetPos = null;
        this.watchStartTick = 0;
    }

    private static double horizontalDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
