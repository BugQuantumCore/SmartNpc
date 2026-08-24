package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public final class ClearBlockAi {
    public enum TickResult {
        IDLE,
        RUNNING,
        DONE,
        FAILED
    }

    private static final double DEFAULT_CLEAR_DISTANCE_SQR = 4.5D * 4.5D;
    private static final double BREAK_REACH_DISTANCE_SQR = 4.0D * 4.0D;
    private static final double BREAK_STAND_REACHED_SQR = 0.95D * 0.95D;
    private static final double BREAK_STAND_CENTERED_SQR = 0.35D * 0.35D;
    private static final double STAND_EYE_HEIGHT = 1.5D;
    private static final int BREAK_STAND_RADIUS = 2;
    private static final int BREAK_STAND_VERTICAL_RANGE = 2;
    private static final int MAX_BREAK_STAND_PATH_CHECKS = 12;
    private static final int APPROACH_REPATH_INTERVAL_TICKS = 10;
    private static final int MAX_APPROACH_TICKS = 20 * 8;
    private static final int MAX_CLEAR_TARGET_TICKS = 20 * 15;
    private static final int MAX_CENTERED_BLOCKED_STAND_TICKS = 20;

    private final PlayerNpcEntity playerNpc;
    private final BreakingBlockAi breakingBlockAi;
    private BlockPos requestedTargetPos;
    private BlockPos targetPos;
    private BlockPos standPos;
    private Predicate<BlockState> targetPredicate;
    private String detail = "clearing block";
    private int requiredTicks;
    private double clearDistanceSqr = DEFAULT_CLEAR_DISTANCE_SQR;
    private boolean allowSoftCover;
    private boolean allowOwnedFarmDestruction;
    private int approachRepathTicks;
    private int approachTicks;
    private int clearTargetTicks;
    private int centeredBlockedStandTicks;

    public ClearBlockAi(PlayerNpcEntity playerNpc, BreakingBlockAi breakingBlockAi) {
        this.playerNpc = playerNpc;
        this.breakingBlockAi = breakingBlockAi;
    }

    public boolean isRunning() {
        return this.targetPos != null;
    }

    public BlockPos targetPos() {
        return this.targetPos;
    }

    public boolean start(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks
    ) {
        return this.start(serverLevel, targetPos, targetPredicate, detail, requiredTicks, DEFAULT_CLEAR_DISTANCE_SQR);
    }

    public boolean start(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr
    ) {
        return this.start(serverLevel, targetPos, targetPredicate, detail, requiredTicks, clearDistanceSqr, false);
    }

    public boolean start(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr,
            boolean allowSoftCover
    ) {
        return this.start(serverLevel, targetPos, targetPredicate, detail, requiredTicks,
                clearDistanceSqr, allowSoftCover, false);
    }

    public boolean start(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr,
            boolean allowSoftCover,
            boolean allowOwnedFarmDestruction
    ) {
        if (!allowOwnedFarmDestruction
                && FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, targetPos)
                || !isClearable(serverLevel, targetPos, targetPredicate, allowSoftCover)
                || this.playerNpc.distanceToSqr(centerX(targetPos), centerY(targetPos), centerZ(targetPos)) > clearDistanceSqr) {
            return false;
        }

        this.stop();
        this.requestedTargetPos = targetPos.immutable();
        this.targetPos = this.requestedTargetPos;
        this.targetPredicate = targetPredicate;
        this.detail = detail;
        this.requiredTicks = Math.max(1, requiredTicks);
        this.clearDistanceSqr = clearDistanceSqr;
        this.allowSoftCover = allowSoftCover;
        this.allowOwnedFarmDestruction = allowOwnedFarmDestruction;
        this.clearTargetTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.updateDetail();
        return true;
    }

    public boolean startNearest(
            ServerLevel serverLevel,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks
    ) {
        return this.startNearest(serverLevel, candidates, targetPredicate, detail, requiredTicks, DEFAULT_CLEAR_DISTANCE_SQR);
    }

    public boolean startNearest(
            ServerLevel serverLevel,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr
    ) {
        return this.startNearest(serverLevel, candidates, targetPredicate, detail, requiredTicks, clearDistanceSqr, false);
    }

    public boolean startNearest(
            ServerLevel serverLevel,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr,
            boolean allowSoftColumnCover
    ) {
        return this.startNearest(serverLevel, candidates, targetPredicate, detail, requiredTicks,
                clearDistanceSqr, allowSoftColumnCover, false);
    }

    public boolean startNearest(
            ServerLevel serverLevel,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr,
            boolean allowSoftColumnCover,
            boolean allowOwnedFarmDestruction
    ) {
        Collection<BlockPos> effectiveCandidates = allowOwnedFarmDestruction
                ? candidates
                : candidates.stream()
                .filter(pos -> !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos))
                .toList();
        Optional<BlockPos> target = findNearestAccessibleClearable(
                serverLevel,
                this.playerNpc,
                effectiveCandidates,
                targetPredicate,
                clearDistanceSqr,
                allowSoftColumnCover
        );
        if (target.isEmpty()) {
            return false;
        }

        Predicate<BlockState> effectivePredicate = targetPredicate;
        BlockState state = serverLevel.getBlockState(target.get());
        if (allowSoftColumnCover && !targetPredicate.test(state) && isSoftCoverState(state)) {
            effectivePredicate = blockState -> targetPredicate.test(blockState) || isSoftCoverState(blockState);
        }
        return this.start(serverLevel, target.get(), effectivePredicate, detail, requiredTicks,
                clearDistanceSqr, allowSoftColumnCover, allowOwnedFarmDestruction);
    }

    public TickResult tick(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return TickResult.IDLE;
        }

        if (this.targetPredicate == null) {
            this.stop();
            return TickResult.FAILED;
        }

        if (!this.allowOwnedFarmDestruction
                && FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, this.targetPos)) {
            this.playerNpc.setIdleTraceDetail("clear target protected by owned farm @ "
                    + this.targetPos.getX() + " " + this.targetPos.getY() + " " + this.targetPos.getZ(), 40);
            this.stop();
            return TickResult.FAILED;
        }

        if (!isClearable(serverLevel, this.targetPos, this.targetPredicate, this.allowSoftCover)) {
            if (this.resumeRequestedTarget(serverLevel)) {
                this.updateDetail();
                return TickResult.RUNNING;
            }
            this.stop();
            return TickResult.DONE;
        }

        if (++this.clearTargetTicks > MAX_CLEAR_TARGET_TICKS) {
            this.stop();
            return TickResult.FAILED;
        }

        if (this.playerNpc.distanceToSqr(centerX(this.targetPos), centerY(this.targetPos), centerZ(this.targetPos)) > this.clearDistanceSqr) {
            this.stop();
            return TickResult.FAILED;
        }

        Optional<BlockPos> blocker = findBreakRayBlocker(
                serverLevel,
                this.playerNpc,
                this.targetPos,
                this.targetPredicate,
                this.clearDistanceSqr,
                this.allowSoftCover
        );
        if (blocker.isPresent() && !blocker.get().equals(this.targetPos)) {
            if (!this.allowOwnedFarmDestruction
                    && FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, blocker.get())) {
                this.playerNpc.setIdleTraceDetail("clear blocker protected by owned farm @ "
                        + blocker.get().getX() + " " + blocker.get().getY() + " " + blocker.get().getZ(), 40);
                this.stop();
                return TickResult.FAILED;
            }
            this.breakingBlockAi.stop();
            this.retargetBlocker(blocker.get());
            this.updateDetail();
            return TickResult.RUNNING;
        }

        if (!canBreakFromCurrentPosition(serverLevel, this.playerNpc, this.targetPos)) {
            this.breakingBlockAi.stop();
            if (!this.moveNearTarget(serverLevel)) {
                this.stop();
                return TickResult.FAILED;
            }
            this.updateDetail();
            return TickResult.RUNNING;
        }

        this.playerNpc.getNavigation().stop();
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                this.targetPredicate,
                this.requiredTicks,
                this.detail + (this.isClearingBlocker() ? " blocker" : ""),
                false,
                this.allowOwnedFarmDestruction
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            this.updateDetail();
            return TickResult.RUNNING;
        }
        if (result == BreakingBlockAi.TickResult.DONE) {
            if (this.resumeRequestedTarget(serverLevel)) {
                this.updateDetail();
                return TickResult.RUNNING;
            }
            this.stop();
            return TickResult.DONE;
        }

        this.stop();
        return TickResult.FAILED;
    }

    public void stop() {
        if (this.targetPos != null) {
            this.breakingBlockAi.stop();
        }
        this.requestedTargetPos = null;
        this.targetPos = null;
        this.standPos = null;
        this.targetPredicate = null;
        this.requiredTicks = 0;
        this.clearDistanceSqr = DEFAULT_CLEAR_DISTANCE_SQR;
        this.allowSoftCover = false;
        this.allowOwnedFarmDestruction = false;
        this.approachRepathTicks = 0;
        this.approachTicks = 0;
        this.clearTargetTicks = 0;
        this.centeredBlockedStandTicks = 0;
    }

    /**
     * Keeps the selected clear request, but makes its next tick choose an approach from the
     * NPC's new position. The overall clear timeout is deliberately preserved.
     */
    public void retryFromCurrentPosition() {
        if (this.targetPos == null) {
            return;
        }
        this.breakingBlockAi.stop();
        this.standPos = null;
        this.approachRepathTicks = 0;
        this.approachTicks = 0;
        this.centeredBlockedStandTicks = 0;
    }

    public String detail() {
        if (this.breakingBlockAi.isRunning()) {
            return this.breakingBlockAi.detail();
        }
        if (this.targetPos == null) {
            return "";
        }
        return this.detail
                + (this.isClearingBlocker() ? " blocker" : "")
                + " @ "
                + this.targetPos.getX() + " "
                + this.targetPos.getY() + " "
                + this.targetPos.getZ();
    }

    private void updateDetail() {
        String currentDetail = this.detail();
        if (!currentDetail.isBlank()) {
            this.playerNpc.setCurrentAiDetail(currentDetail);
        }
    }

    public static List<BlockPos> gatherObstructionCandidates(BlockPos feet, BlockPos standPos, BlockPos targetPos) {
        ArrayList<BlockPos> candidates = new ArrayList<>();
        addBodyColumn(candidates, feet);
        if (feet != null
                && ((standPos != null && standPos.getY() < feet.getY())
                || (targetPos != null && targetPos.getY() < feet.getY()))) {
            candidates.add(feet.below());
        }
        if (standPos != null) {
            addBodyColumn(candidates, standPos);
            addLineCandidates(candidates, feet, standPos, 8);
            addLineCandidates(candidates, feet.above(), standPos.above(), 8);
        }
        if (targetPos != null) {
            addLineCandidates(candidates, feet.above(), targetPos, 8);
            candidates.add(targetPos.below());
            candidates.add(targetPos.above());
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos side = targetPos.relative(direction);
                candidates.add(side);
                candidates.add(side.below());
            }
        }
        return candidates;
    }

    public static Optional<BlockPos> findNearestClearable(
            ServerLevel serverLevel,
            BlockPos origin,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr
    ) {
        return candidates.stream()
                .map(BlockPos::immutable)
                .distinct()
                .filter(pos -> pos.distSqr(origin) <= maxDistanceSqr)
                .filter(pos -> isClearable(serverLevel, pos, targetPredicate))
                .min(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
    }

    public static Optional<BlockPos> findNearestAccessibleClearable(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr
    ) {
        return findNearestAccessibleClearable(serverLevel, playerNpc, candidates, targetPredicate, maxDistanceSqr, false);
    }

    public static Optional<BlockPos> findNearestAccessibleClearable(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            Collection<BlockPos> candidates,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr,
            boolean allowSoftColumnCover
    ) {
        BlockPos origin = playerNpc.blockPosition();
        Set<BlockPos> seen = new HashSet<>();
        ArrayList<BlockPos> clearable = new ArrayList<>();
        for (BlockPos candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            BlockPos immutable = candidate.immutable();
            if (!seen.add(immutable)
                    || immutable.distSqr(origin) > maxDistanceSqr
                    || !isClearable(serverLevel, immutable, targetPredicate, allowSoftColumnCover)) {
                continue;
            }
            clearable.add(immutable);
        }

        clearable.sort(Comparator.comparingDouble(pos -> pos.distSqr(origin)));
        int pathChecks = 0;
        for (BlockPos candidate : clearable) {
            if (findBreakRayBlocker(
                    serverLevel,
                    playerNpc,
                    candidate,
                    targetPredicate,
                    maxDistanceSqr,
                    allowSoftColumnCover
            ).isPresent()) {
                return Optional.of(candidate);
            }
            if (canBreakFromCurrentPosition(serverLevel, playerNpc, candidate)) {
                return Optional.of(candidate);
            }
            Optional<BlockPos> cover = findColumnCover(serverLevel, playerNpc, candidate, targetPredicate, maxDistanceSqr, allowSoftColumnCover);
            if (cover.isPresent()) {
                return cover;
            }
            if (pathChecks++ >= MAX_BREAK_STAND_PATH_CHECKS) {
                break;
            }
            if (findReachableBreakStand(playerNpc, serverLevel, candidate).isPresent()) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the block a clear request would initially hit from the NPC's current eye
     * position. Clear requests deliberately remember their requested target so they can
     * resume it after a ray blocker is removed; callers that protect owned structures can
     * use this view to validate that blocker before starting the request.
     */
    public static Optional<BlockPos> resolveInitialClearTarget(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockPos requestedTarget,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr,
            boolean allowSoftColumnCover
    ) {
        if (serverLevel == null
                || playerNpc == null
                || requestedTarget == null
                || targetPredicate == null
                || playerNpc.distanceToSqr(centerX(requestedTarget), centerY(requestedTarget), centerZ(requestedTarget))
                > maxDistanceSqr
                || !isClearable(serverLevel, requestedTarget, targetPredicate, allowSoftColumnCover)) {
            return Optional.empty();
        }
        return findBreakRayBlocker(
                serverLevel,
                playerNpc,
                requestedTarget,
                targetPredicate,
                maxDistanceSqr,
                allowSoftColumnCover
        ).or(() -> Optional.of(requestedTarget.immutable()));
    }

    private static Optional<BlockPos> findColumnCover(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr,
            boolean allowSoftColumnCover
    ) {
        BlockPos origin = playerNpc.blockPosition();
        if (targetPos == null || targetPos.getY() >= origin.getY()) {
            return Optional.empty();
        }

        int topY = Math.min(origin.getY(), serverLevel.getMaxBuildHeight() - 1);
        for (int y = topY; y > targetPos.getY(); y--) {
            BlockPos cover = new BlockPos(targetPos.getX(), y, targetPos.getZ());
            BlockState state = serverLevel.getBlockState(cover);
            boolean targetMatch = targetPredicate.test(state);
            boolean softCover = allowSoftColumnCover && isSoftCoverState(state);
            if (playerNpc.distanceToSqr(centerX(cover), centerY(cover), centerZ(cover)) > maxDistanceSqr
                    || !targetMatch && !softCover
                    || !isBreakablePathObstruction(serverLevel, cover, state, softCover)) {
                continue;
            }
            return Optional.of(cover.immutable());
        }
        return Optional.empty();
    }

    public static boolean isBreakablePathObstruction(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return isBreakablePathObstruction(serverLevel, pos, state, false);
    }

    public static boolean isBreakablePathObstruction(ServerLevel serverLevel, BlockPos pos, BlockState state, boolean allowSoftCover) {
        if (serverLevel == null
                || pos == null
                || state == null
                || !serverLevel.isInWorldBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || state.isAir()
                || state.getDestroySpeed(serverLevel, pos) < 0.0F
                || !state.getFluidState().isEmpty()
                || serverLevel.getBlockEntity(pos) != null) {
            return false;
        }

        return !state.getCollisionShape(serverLevel, pos).isEmpty()
                || allowSoftCover && isSoftPathObstructionState(state);
    }

    private static boolean isSoftCoverState(BlockState state) {
        return state != null
                && !state.isAir()
                && state.canBeReplaced();
    }

    private static boolean isSoftPathObstructionState(BlockState state) {
        return isSoftCoverState(state)
                || state.is(Blocks.TORCH)
                || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_TORCH)
                || state.is(Blocks.SOUL_WALL_TORCH)
                || state.is(Blocks.REDSTONE_TORCH)
                || state.is(Blocks.REDSTONE_WALL_TORCH)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS);
    }

    private static boolean isClearable(ServerLevel serverLevel, BlockPos pos, Predicate<BlockState> targetPredicate) {
        return isClearable(serverLevel, pos, targetPredicate, false);
    }

    private static boolean isClearable(ServerLevel serverLevel, BlockPos pos, Predicate<BlockState> targetPredicate, boolean allowSoftCover) {
        if (pos == null || targetPredicate == null || !serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        return targetPredicate.test(state)
                && isBreakablePathObstruction(serverLevel, pos, state, allowSoftCover);
    }

    private boolean moveNearTarget(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return false;
        }

        if (canBreakFromCurrentPosition(serverLevel, this.playerNpc, this.targetPos)) {
            this.approachTicks = 0;
            this.centeredBlockedStandTicks = 0;
            return true;
        }
        if (++this.approachTicks > MAX_APPROACH_TICKS) {
            return false;
        }

        if (this.standPos == null || !canUseBreakStand(serverLevel, this.standPos, this.targetPos)) {
            this.standPos = findReachableBreakStand(this.playerNpc, serverLevel, this.targetPos).orElse(null);
            this.approachRepathTicks = 0;
        }
        if (this.standPos == null) {
            return false;
        }

        this.playerNpc.getLookControl().setLookAt(
                centerX(this.targetPos),
                centerY(this.targetPos),
                centerZ(this.targetPos),
                35.0F,
                35.0F
        );

        if (isAtBreakStand(this.playerNpc, this.standPos)) {
            this.playerNpc.getNavigation().stop();
            if (this.isCenteredOnBreakStand() && ++this.centeredBlockedStandTicks > MAX_CENTERED_BLOCKED_STAND_TICKS) {
                this.standPos = null;
                this.centeredBlockedStandTicks = 0;
                return false;
            }
            this.nudgeTowardBreakStandCenter();
            return true;
        }
        this.centeredBlockedStandTicks = 0;
        if (this.approachRepathTicks > 0
                && !this.playerNpc.getNavigation().isDone()
                && !this.playerNpc.getNavigation().isStuck()) {
            this.approachRepathTicks--;
            return true;
        }

        Path path = this.playerNpc.getNavigation().createPath(this.standPos, 0);
        if (!isUsablePathToStand(path, this.standPos)) {
            this.standPos = null;
            return false;
        }
        this.approachRepathTicks = APPROACH_REPATH_INTERVAL_TICKS;
        return this.playerNpc.getNavigation().moveTo(path, 1.0D);
    }

    private void retargetBlocker(BlockPos blockerPos) {
        this.targetPos = blockerPos.immutable();
        this.standPos = null;
        this.approachRepathTicks = 0;
        this.approachTicks = 0;
        this.centeredBlockedStandTicks = 0;
        this.playerNpc.getNavigation().stop();
    }

    private boolean resumeRequestedTarget(ServerLevel serverLevel) {
        if (!this.isClearingBlocker()
                || !isClearable(serverLevel, this.requestedTargetPos, this.targetPredicate, this.allowSoftCover)) {
            return false;
        }

        this.targetPos = this.requestedTargetPos;
        this.standPos = null;
        this.approachRepathTicks = 0;
        this.approachTicks = 0;
        this.centeredBlockedStandTicks = 0;
        this.playerNpc.getNavigation().stop();
        return true;
    }

    private boolean isClearingBlocker() {
        return this.requestedTargetPos != null
                && this.targetPos != null
                && !this.requestedTargetPos.equals(this.targetPos);
    }

    /**
     * Finds a bounded, exactly reachable stand whose centered eye ray first hits the
     * requested block. At most {@value #MAX_BREAK_STAND_PATH_CHECKS} paths are probed.
     */
    public static Optional<BlockPos> findReachableBreakStand(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos targetPos
    ) {
        if (playerNpc == null || serverLevel == null || targetPos == null) {
            return Optional.empty();
        }
        ArrayList<BlockPos> candidates = new ArrayList<>();
        BlockPos playerFeet = playerNpc.blockPosition();
        if (canUseBreakStand(serverLevel, playerFeet, targetPos)) {
            candidates.add(playerFeet.immutable());
        }

        for (int dx = -BREAK_STAND_RADIUS; dx <= BREAK_STAND_RADIUS; dx++) {
            for (int dy = -BREAK_STAND_VERTICAL_RANGE; dy <= BREAK_STAND_VERTICAL_RANGE; dy++) {
                for (int dz = -BREAK_STAND_RADIUS; dz <= BREAK_STAND_RADIUS; dz++) {
                    BlockPos candidate = targetPos.offset(dx, dy, dz);
                    if (candidate.equals(targetPos) || !canUseBreakStand(serverLevel, candidate, targetPos)) {
                        continue;
                    }
                    candidates.add(candidate.immutable());
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(pos -> playerNpc.distanceToSqr(
                pos.getX() + 0.5D,
                pos.getY(),
                pos.getZ() + 0.5D
        )));

        int pathChecks = 0;
        for (BlockPos candidate : candidates) {
            if (isAtBreakStand(playerNpc, candidate)) {
                return Optional.of(candidate);
            }
            if (pathChecks++ >= MAX_BREAK_STAND_PATH_CHECKS) {
                break;
            }
            Path path = playerNpc.getNavigation().createPath(candidate, 0);
            if (isUsablePathToStand(path, candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean canUseBreakStand(ServerLevel serverLevel, BlockPos standPos, BlockPos targetPos) {
        return PathNavigationAi.canStandAt(serverLevel, standPos)
                && distanceFromStandToTargetSqr(standPos, targetPos) <= BREAK_REACH_DISTANCE_SQR
                && hasClearBreakRay(serverLevel, standPos, targetPos);
    }

    private static boolean isWithinBreakReach(PlayerNpcEntity playerNpc, BlockPos targetPos) {
        double dx = playerNpc.getX() - centerX(targetPos);
        double dy = playerNpc.getEyeY() - centerY(targetPos);
        double dz = playerNpc.getZ() - centerZ(targetPos);
        return dx * dx + dy * dy + dz * dz <= BREAK_REACH_DISTANCE_SQR;
    }

    private static boolean canBreakFromCurrentPosition(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos targetPos) {
        return isWithinBreakReach(playerNpc, targetPos)
                && (hasClearBreakRay(serverLevel, playerNpc, targetPos)
                || isImmediateBodyObstruction(serverLevel, playerNpc, targetPos));
    }

    private static boolean isImmediateBodyObstruction(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos targetPos) {
        BlockPos feet = playerNpc.blockPosition();
        int dx = Math.abs(targetPos.getX() - feet.getX());
        int dy = targetPos.getY() - feet.getY();
        int dz = Math.abs(targetPos.getZ() - feet.getZ());
        boolean supportBelow = dx == 0 && dz == 0 && dy == -1;
        if (!supportBelow && (dy < 0 || dy > 2 || dx + dz > 1)) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(targetPos);
        return isBreakablePathObstruction(serverLevel, targetPos, state, true);
    }

    private static boolean hasClearBreakRay(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos targetPos) {
        return hasClearBreakRay(
                serverLevel,
                new Vec3(playerNpc.getX(), playerNpc.getEyeY(), playerNpc.getZ()),
                targetPos
        );
    }

    private static boolean hasClearBreakRay(ServerLevel serverLevel, BlockPos standPos, BlockPos targetPos) {
        return hasClearBreakRay(
                serverLevel,
                new Vec3(standPos.getX() + 0.5D, standPos.getY() + STAND_EYE_HEIGHT, standPos.getZ() + 0.5D),
                targetPos
        );
    }

    private static boolean hasClearBreakRay(ServerLevel serverLevel, Vec3 eye, BlockPos targetPos) {
        BlockHitResult hit = clipBreakRay(serverLevel, eye, targetPos);
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(targetPos);
    }

    private static Optional<BlockPos> findBreakRayBlocker(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            double maxDistanceSqr,
            boolean allowSoftCover
    ) {
        if (targetPos == null || targetPredicate == null) {
            return Optional.empty();
        }

        Vec3 eye = new Vec3(playerNpc.getX(), playerNpc.getEyeY(), playerNpc.getZ());
        BlockHitResult hit = clipBreakRay(serverLevel, eye, targetPos);
        if (hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(targetPos)) {
            return Optional.empty();
        }

        BlockPos blockerPos = hit.getBlockPos();
        BlockState blockerState = serverLevel.getBlockState(blockerPos);
        if (!targetPredicate.test(blockerState)
                || playerNpc.distanceToSqr(centerX(blockerPos), centerY(blockerPos), centerZ(blockerPos)) > maxDistanceSqr
                || !isBreakablePathObstruction(serverLevel, blockerPos, blockerState, allowSoftCover)) {
            return Optional.empty();
        }
        return Optional.of(blockerPos.immutable());
    }

    private static BlockHitResult clipBreakRay(ServerLevel serverLevel, Vec3 eye, BlockPos targetPos) {
        return serverLevel.clip(new ClipContext(
                eye,
                Vec3.atCenterOf(targetPos),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                null
        ));
    }

    private static boolean isAtBreakStand(PlayerNpcEntity playerNpc, BlockPos standPos) {
        return playerNpc.blockPosition().getY() == standPos.getY()
                && playerNpc.distanceToSqr(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D)
                <= BREAK_STAND_REACHED_SQR;
    }

    private void nudgeTowardBreakStandCenter() {
        if (this.standPos == null || this.isCenteredOnBreakStand()) {
            return;
        }

        this.playerNpc.getMoveControl().setWantedPosition(
                this.standPos.getX() + 0.5D,
                this.playerNpc.getY(),
                this.standPos.getZ() + 0.5D,
                1.0D
        );
    }

    private boolean isCenteredOnBreakStand() {
        double dx = this.playerNpc.getX() - (this.standPos.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (this.standPos.getZ() + 0.5D);
        return dx * dx + dz * dz <= BREAK_STAND_CENTERED_SQR;
    }

    private static double distanceFromStandToTargetSqr(BlockPos standPos, BlockPos targetPos) {
        double dx = standPos.getX() + 0.5D - centerX(targetPos);
        double dy = standPos.getY() + STAND_EYE_HEIGHT - centerY(targetPos);
        double dz = standPos.getZ() + 0.5D - centerZ(targetPos);
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isUsablePathToStand(Path path, BlockPos standPos) {
        if (path == null || !path.canReach()) {
            return false;
        }
        Node endNode = path.getEndNode();
        return endNode != null && endNode.asBlockPos().equals(standPos);
    }

    private static void addBodyColumn(List<BlockPos> candidates, BlockPos feet) {
        if (feet == null) {
            return;
        }
        candidates.add(feet);
        candidates.add(feet.above());
        candidates.add(feet.above(2));
    }

    private static void addLineCandidates(List<BlockPos> candidates, BlockPos start, BlockPos target, int maxSteps) {
        double dx = target.getX() - start.getX();
        double dy = target.getY() - start.getY();
        double dz = target.getZ() - start.getZ();
        int steps = Math.max(1, Math.min(maxSteps, (int) Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))))));
        for (int i = 1; i <= steps; i++) {
            double progress = i / (double) steps;
            int x = start.getX() + (int) Math.round(dx * progress);
            int y = start.getY() + (int) Math.round(dy * progress);
            int z = start.getZ() + (int) Math.round(dz * progress);
            candidates.add(new BlockPos(x, y, z));
        }
    }

    private static double centerX(BlockPos pos) {
        return pos.getX() + 0.5D;
    }

    private static double centerY(BlockPos pos) {
        return pos.getY() + 0.5D;
    }

    private static double centerZ(BlockPos pos) {
        return pos.getZ() + 0.5D;
    }

}
