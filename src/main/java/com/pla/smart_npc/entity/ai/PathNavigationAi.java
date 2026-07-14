package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class PathNavigationAi {
    private static final double PATH_END_DISTANCE_SQR = 3.0D * 3.0D;
    private static final int PATH_END_VERTICAL_TOLERANCE = 2;
    private static final double DIRECT_STEP_REACHED_SQR = 0.75D * 0.75D;
    private static final int MAX_LOCAL_ROUTE_PATH_CHECKS = 16;
    private static final double LOCAL_ROUTE_MAX_BACKTRACK_SQR = 8.0D * 8.0D;

    private final PlayerNpcEntity playerNpc;
    private final WaterEscapeAi waterEscapeAi;
    private BlockPos lastLocalRouteTarget;
    private String lastMoveFailureDetail = "";
    private int lastLocalCandidateCount;
    private int lastLocalPathChecks;

    public PathNavigationAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.waterEscapeAi = new WaterEscapeAi(playerNpc);
    }

    public boolean moveTo(ServerLevel serverLevel, BlockPos target, double speed, int maxSafeDrop) {
        if (this.escapeWaterIfNeeded(serverLevel, speed)) {
            return true;
        }

        if (this.isAlreadyAtTarget(target)) {
            this.lastMoveFailureDetail = "";
            this.playerNpc.getNavigation().stop();
            return true;
        }

        if (this.shouldStepDownBeforePath(target, maxSafeDrop)
                && this.moveToSafeDropStep(serverLevel, target, speed, maxSafeDrop)) {
            this.lastMoveFailureDetail = "";
            return true;
        }

        Path path = this.playerNpc.getNavigation().createPath(target, 0);
        if (this.isValidPathTo(target, path)) {
            this.lastMoveFailureDetail = "";
            return this.playerNpc.getNavigation().moveTo(path, speed);
        }

        boolean movedToSafeDrop = this.moveToSafeDropStep(serverLevel, target, speed, maxSafeDrop);
        if (movedToSafeDrop) {
            this.lastMoveFailureDetail = "";
            return true;
        }

        this.lastMoveFailureDetail = this.pathDebug(target, path) + " safeDrop=none";
        return false;
    }

    public boolean moveToExact(ServerLevel serverLevel, BlockPos target, double speed, int maxSafeDrop) {
        if (this.escapeWaterIfNeeded(serverLevel, speed)) {
            return true;
        }

        if (this.isAlreadyAtTarget(target)) {
            this.lastMoveFailureDetail = "";
            this.playerNpc.getNavigation().stop();
            return true;
        }

        if (this.shouldStepDownBeforePath(target, maxSafeDrop)
                && this.moveToSafeDropStep(serverLevel, target, speed, maxSafeDrop)) {
            this.lastMoveFailureDetail = "";
            return true;
        }

        Path path = this.playerNpc.getNavigation().createPath(target, 0);
        if (this.isExactPathTo(target, path)) {
            this.lastMoveFailureDetail = "";
            return this.playerNpc.getNavigation().moveTo(path, speed);
        }

        boolean movedToSafeDrop = this.moveToSafeDropStep(serverLevel, target, speed, maxSafeDrop);
        if (movedToSafeDrop) {
            this.lastMoveFailureDetail = "";
            return true;
        }

        this.lastMoveFailureDetail = this.pathDebug(target, path) + " exact=false safeDrop=none";
        return false;
    }

    public boolean moveToWithLocalFallback(
            ServerLevel serverLevel,
            BlockPos target,
            double speed,
            int maxSafeDrop,
            int horizontalRadius,
            int verticalDown,
            int verticalUp) {
        this.lastLocalRouteTarget = null;
        if (this.moveTo(serverLevel, target, speed, maxSafeDrop)) {
            return true;
        }

        RouteStep routeStep = this.findLocalRouteStep(serverLevel, target, horizontalRadius, verticalDown, verticalUp);
        if (routeStep == null) {
            this.lastMoveFailureDetail = this.lastMoveFailureDetail
                    + " localCandidates=" + this.lastLocalCandidateCount
                    + " localChecks=" + this.lastLocalPathChecks
                    + " localRoute=none";
            return false;
        }

        this.lastLocalRouteTarget = routeStep.pos();
        this.lastMoveFailureDetail = "";
        return this.playerNpc.getNavigation().moveTo(routeStep.path(), speed);
    }

    public BlockPos lastLocalRouteTarget() {
        return this.lastLocalRouteTarget == null ? null : this.lastLocalRouteTarget.immutable();
    }

    public String lastMoveFailureDetail() {
        return this.lastMoveFailureDetail;
    }

    private boolean escapeWaterIfNeeded(ServerLevel serverLevel, double speed) {
        WaterEscapeAi.TickResult result = this.waterEscapeAi.tick(serverLevel, Math.max(1.0D, speed));
        if (result == WaterEscapeAi.TickResult.RUNNING || result == WaterEscapeAi.TickResult.DONE) {
            this.lastMoveFailureDetail = "";
            if (result == WaterEscapeAi.TickResult.RUNNING && !this.waterEscapeAi.detail().isBlank()) {
                this.playerNpc.setCurrentAiDetail(this.waterEscapeAi.detail());
            }
            return true;
        }
        return false;
    }

    public boolean canReachOrSafelyDropTo(ServerLevel serverLevel, BlockPos target, int maxSafeDrop) {
        Path path = this.playerNpc.getNavigation().createPath(target, 0);
        return this.isValidPathTo(target, path)
                || this.findSafeDropStep(serverLevel, this.playerNpc.blockPosition(), target, maxSafeDrop) != null;
    }

    public Optional<BlockPos> findReachableRandomizedCandidate(
            ServerLevel serverLevel,
            List<BlockPos> candidates,
            int preferredPoolSize,
            int maxChecks,
            int maxSafeDrop
    ) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        List<BlockPos> ordered = new ArrayList<>(candidates);
        int preferredCount = Math.min(Math.max(1, preferredPoolSize), ordered.size());
        if (preferredCount > 1) {
            Collections.rotate(
                    ordered.subList(0, preferredCount),
                    this.playerNpc.getRandom().nextInt(preferredCount)
            );
        }

        int checks = 0;
        int checkLimit = Math.max(1, maxChecks);
        for (BlockPos candidate : ordered) {
            if (checks++ >= checkLimit) {
                break;
            }
            if (canStandAt(serverLevel, candidate)
                    && this.canReachOrSafelyDropTo(serverLevel, candidate, maxSafeDrop)) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }

    public boolean hasValidPathTo(BlockPos target) {
        Path path = this.playerNpc.getNavigation().createPath(target, 0);
        return this.isValidPathTo(target, path);
    }

    public boolean hasExactPathTo(BlockPos target) {
        Path path = this.playerNpc.getNavigation().createPath(target, 0);
        return this.isExactPathTo(target, path);
    }

    public boolean isValidPathTo(BlockPos target, Path path) {
        if (path == null || !path.canReach()) {
            return false;
        }

        Node endNode = path.getEndNode();
        if (endNode == null) {
            return false;
        }

        BlockPos endPos = endNode.asBlockPos();
        return Math.abs(endPos.getY() - target.getY()) <= PATH_END_VERTICAL_TOLERANCE
                && blockDistanceSqr(endPos, target) <= PATH_END_DISTANCE_SQR;
    }

    private boolean isExactPathTo(BlockPos target, Path path) {
        if (path == null || !path.canReach()) {
            return false;
        }

        Node endNode = path.getEndNode();
        return endNode != null && endNode.asBlockPos().equals(target);
    }

    private boolean shouldStepDownBeforePath(BlockPos target, int maxSafeDrop) {
        return target != null
                && maxSafeDrop > 0
                && this.playerNpc.onGround()
                && this.playerNpc.blockPosition().getY() - target.getY() > PATH_END_VERTICAL_TOLERANCE;
    }

    private boolean isAlreadyAtTarget(BlockPos target) {
        return target != null && this.playerNpc.blockPosition().equals(target);
    }

    private boolean moveToSafeDropStep(ServerLevel serverLevel, BlockPos target, double speed, int maxSafeDrop) {
        BlockPos step = this.findSafeDropStep(serverLevel, this.playerNpc.blockPosition(), target, maxSafeDrop);
        if (step == null) {
            return false;
        }

        this.playerNpc.getLookControl().setLookAt(
                target.getX() + 0.5D,
                target.getY(),
                target.getZ() + 0.5D,
                30.0F,
                30.0F
        );

        if (this.playerNpc.distanceToSqr(step.getX() + 0.5D, this.playerNpc.getY(), step.getZ() + 0.5D) <= DIRECT_STEP_REACHED_SQR) {
            this.playerNpc.getNavigation().stop();
            this.playerNpc.getMoveControl().setWantedPosition(
                    target.getX() + 0.5D,
                    target.getY(),
                    target.getZ() + 0.5D,
                    speed
            );
            return true;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getMoveControl().setWantedPosition(
                step.getX() + 0.5D,
                this.playerNpc.getY(),
                step.getZ() + 0.5D,
                speed
        );
        return true;
    }

    private BlockPos findSafeDropStep(ServerLevel serverLevel, BlockPos feet, BlockPos target, int maxSafeDrop) {
        if (target == null || maxSafeDrop <= 0 || target.getY() >= feet.getY()) {
            return null;
        }

        List<Direction> directions = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            directions.add(direction);
        }
        directions.sort(Comparator.comparingDouble(direction ->
                horizontalDistanceSqr(feet.relative(direction), target)));

        for (Direction direction : directions) {
            BlockPos step = feet.relative(direction);
            if (this.hasBlockingCollision(serverLevel, step)
                    || this.hasBlockingCollision(serverLevel, step.above())
                    || !serverLevel.getFluidState(step).isEmpty()
                    || !serverLevel.getFluidState(step.above()).isEmpty()) {
                continue;
            }

            for (int drop = 1; drop <= maxSafeDrop; drop++) {
                BlockPos landing = step.below(drop);
                if (canStandAt(serverLevel, landing)) {
                    return step.immutable();
                }
            }
        }
        return null;
    }

    private RouteStep findLocalRouteStep(
            ServerLevel serverLevel,
            BlockPos target,
            int horizontalRadius,
            int verticalDown,
            int verticalUp) {
        BlockPos feet = this.playerNpc.blockPosition();
        double currentTargetDistance = blockDistanceSqr(feet, target);
        List<BlockPos> candidates = new ArrayList<>();

        int radius = Math.max(2, horizontalRadius);
        int radiusSqr = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dz == 0 || dx * dx + dz * dz > radiusSqr) {
                    continue;
                }
                for (int dy = -Math.max(0, verticalDown); dy <= Math.max(0, verticalUp); dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (!canStandAt(serverLevel, candidate)) {
                        continue;
                    }
                    if (!this.isUsefulLocalRouteCandidate(feet, candidate, target, currentTargetDistance)) {
                        continue;
                    }
                    candidates.add(candidate.immutable());
                }
            }
        }
        this.lastLocalCandidateCount = candidates.size();
        this.lastLocalPathChecks = 0;

        candidates.sort(Comparator.comparingDouble(candidate -> this.localRouteScore(serverLevel, feet, candidate, target)));
        int checks = 0;
        for (BlockPos candidate : candidates) {
            if (checks++ >= MAX_LOCAL_ROUTE_PATH_CHECKS) {
                break;
            }
            this.lastLocalPathChecks = checks;
            Path path = this.playerNpc.getNavigation().createPath(candidate, 0);
            if (this.isValidPathTo(candidate, path)) {
                return new RouteStep(candidate, path);
            }
        }
        return null;
    }

    private boolean isUsefulLocalRouteCandidate(BlockPos feet, BlockPos candidate, BlockPos target, double currentTargetDistance) {
        double candidateTargetDistance = blockDistanceSqr(candidate, target);
        if (candidateTargetDistance < currentTargetDistance) {
            return true;
        }

        double currentToCandidateX = candidate.getX() - feet.getX();
        double currentToCandidateZ = candidate.getZ() - feet.getZ();
        double currentToTargetX = target.getX() - feet.getX();
        double currentToTargetZ = target.getZ() - feet.getZ();
        double directionDot = currentToCandidateX * currentToTargetX + currentToCandidateZ * currentToTargetZ;
        return directionDot > 0.0D && candidateTargetDistance <= currentTargetDistance + LOCAL_ROUTE_MAX_BACKTRACK_SQR;
    }

    private double localRouteScore(ServerLevel serverLevel, BlockPos feet, BlockPos candidate, BlockPos target) {
        double targetDistance = blockDistanceSqr(candidate, target);
        double stepDistance = blockDistanceSqr(feet, candidate);
        double score = targetDistance + stepDistance * 0.35D;
        if (serverLevel.canSeeSky(candidate.above())) {
            score -= 32.0D;
        }
        if (candidate.getY() > feet.getY()) {
            score -= Math.min(12.0D, (candidate.getY() - feet.getY()) * 3.0D);
        }
        return score;
    }

    private boolean hasBlockingCollision(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        return !state.getCollisionShape(serverLevel, pos).isEmpty();
    }

    public static boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
        return serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below())
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
    }

    private static double blockDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dy = first.getY() - second.getY();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double horizontalDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private String pathDebug(BlockPos target, Path path) {
        if (path == null) {
            return "directPath=null";
        }

        Node endNode = path.getEndNode();
        if (endNode == null) {
            return "directPath canReach=" + path.canReach() + " end=null";
        }

        BlockPos endPos = endNode.asBlockPos();
        return "directPath canReach=" + path.canReach()
                + " end=" + posText(endPos)
                + " endDist=" + Math.round(blockDistanceSqr(endPos, target));
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private record RouteStep(BlockPos pos, Path path) {
    }
}
