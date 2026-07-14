package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
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
    private static final double STAND_EYE_HEIGHT = 1.5D;
    private static final int BREAK_STAND_RADIUS = 2;
    private static final int BREAK_STAND_VERTICAL_RANGE = 2;
    private static final int MAX_BREAK_STAND_PATH_CHECKS = 12;
    private static final int APPROACH_REPATH_INTERVAL_TICKS = 10;
    private static final int MAX_APPROACH_TICKS = 20 * 8;
    private static final int MAX_CLEAR_TARGET_TICKS = 20 * 15;

    private final PlayerNpcEntity playerNpc;
    private final BreakingBlockAi breakingBlockAi;
    private BlockPos targetPos;
    private BlockPos standPos;
    private Predicate<BlockState> targetPredicate;
    private String detail = "clearing block";
    private int requiredTicks;
    private double clearDistanceSqr = DEFAULT_CLEAR_DISTANCE_SQR;
    private boolean allowSoftCover;
    private int approachRepathTicks;
    private int approachTicks;
    private int clearTargetTicks;

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

    private boolean start(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            String detail,
            int requiredTicks,
            double clearDistanceSqr,
            boolean allowSoftCover
    ) {
        if (!isClearable(serverLevel, targetPos, targetPredicate, allowSoftCover)
                || this.playerNpc.distanceToSqr(centerX(targetPos), centerY(targetPos), centerZ(targetPos)) > clearDistanceSqr) {
            return false;
        }

        this.stop();
        this.targetPos = targetPos.immutable();
        this.targetPredicate = targetPredicate;
        this.detail = detail;
        this.requiredTicks = Math.max(1, requiredTicks);
        this.clearDistanceSqr = clearDistanceSqr;
        this.allowSoftCover = allowSoftCover;
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
        Optional<BlockPos> target = findNearestAccessibleClearable(
                serverLevel,
                this.playerNpc,
                candidates,
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
        return this.start(serverLevel, target.get(), effectivePredicate, detail, requiredTicks, clearDistanceSqr, allowSoftColumnCover);
    }

    public TickResult tick(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return TickResult.IDLE;
        }

        if (this.targetPredicate == null || !isClearable(serverLevel, this.targetPos, this.targetPredicate, this.allowSoftCover)) {
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
                this.detail
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            this.updateDetail();
            return TickResult.RUNNING;
        }
        if (result == BreakingBlockAi.TickResult.DONE) {
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
        this.targetPos = null;
        this.standPos = null;
        this.targetPredicate = null;
        this.requiredTicks = 0;
        this.clearDistanceSqr = DEFAULT_CLEAR_DISTANCE_SQR;
        this.allowSoftCover = false;
        this.approachRepathTicks = 0;
        this.approachTicks = 0;
        this.clearTargetTicks = 0;
    }

    public String detail() {
        if (this.targetPos == null) {
            return "";
        }
        return this.detail + " @ "
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
            Optional<BlockPos> cover = findColumnCover(serverLevel, playerNpc, candidate, targetPredicate, maxDistanceSqr, allowSoftColumnCover);
            if (cover.isPresent()) {
                return cover;
            }
            if (isWithinBreakReach(playerNpc, candidate)
                    && hasClearBreakRay(serverLevel, playerNpc, candidate)) {
                return Optional.of(candidate);
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
                || allowSoftCover && isSoftCoverState(state);
    }

    private static boolean isSoftCoverState(BlockState state) {
        return state != null
                && !state.isAir()
                && state.canBeReplaced();
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
            return true;
        }
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

    private static Optional<BlockPos> findReachableBreakStand(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos targetPos) {
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
                && hasClearBreakRay(serverLevel, playerNpc, targetPos);
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
        BlockHitResult hit = serverLevel.clip(new ClipContext(
                eye,
                Vec3.atCenterOf(targetPos),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                null
        ));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(targetPos);
    }

    private static boolean isAtBreakStand(PlayerNpcEntity playerNpc, BlockPos standPos) {
        return playerNpc.blockPosition().getY() == standPos.getY()
                && playerNpc.distanceToSqr(standPos.getX() + 0.5D, standPos.getY(), standPos.getZ() + 0.5D)
                <= BREAK_STAND_REACHED_SQR;
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
