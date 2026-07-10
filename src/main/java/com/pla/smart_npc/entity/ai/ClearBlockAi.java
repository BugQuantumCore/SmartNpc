package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class ClearBlockAi {
    public enum TickResult {
        IDLE,
        RUNNING,
        DONE,
        FAILED
    }

    private static final double DEFAULT_CLEAR_DISTANCE_SQR = 4.5D * 4.5D;

    private final PlayerNpcEntity playerNpc;
    private final BreakingBlockAi breakingBlockAi;
    private BlockPos targetPos;
    private Predicate<BlockState> targetPredicate;
    private String detail = "clearing block";
    private int requiredTicks;
    private double clearDistanceSqr = DEFAULT_CLEAR_DISTANCE_SQR;

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
        if (!isClearable(serverLevel, targetPos, targetPredicate)
                || this.playerNpc.distanceToSqr(centerX(targetPos), centerY(targetPos), centerZ(targetPos)) > clearDistanceSqr) {
            return false;
        }

        this.stop();
        this.targetPos = targetPos.immutable();
        this.targetPredicate = targetPredicate;
        this.detail = detail;
        this.requiredTicks = Math.max(1, requiredTicks);
        this.clearDistanceSqr = clearDistanceSqr;
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
        Optional<BlockPos> target = findNearestClearable(serverLevel, this.playerNpc.blockPosition(), candidates, targetPredicate, clearDistanceSqr);
        return target.isPresent() && this.start(serverLevel, target.get(), targetPredicate, detail, requiredTicks, clearDistanceSqr);
    }

    public TickResult tick(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return TickResult.IDLE;
        }

        if (this.targetPredicate == null || !isClearable(serverLevel, this.targetPos, this.targetPredicate)) {
            this.stop();
            return TickResult.DONE;
        }

        if (this.playerNpc.distanceToSqr(centerX(this.targetPos), centerY(this.targetPos), centerZ(this.targetPos)) > this.clearDistanceSqr) {
            this.stop();
            return TickResult.FAILED;
        }

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
        this.targetPredicate = null;
        this.requiredTicks = 0;
        this.clearDistanceSqr = DEFAULT_CLEAR_DISTANCE_SQR;
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

    private static boolean isClearable(ServerLevel serverLevel, BlockPos pos, Predicate<BlockState> targetPredicate) {
        if (pos == null || targetPredicate == null || !serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        return targetPredicate.test(state)
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && serverLevel.getBlockEntity(pos) == null;
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
