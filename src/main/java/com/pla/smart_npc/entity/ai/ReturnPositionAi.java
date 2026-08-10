package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Reusable return movement helper for goals that need to get an NPC back to a known position.
 */
public final class ReturnPositionAi {
    private static final int REPATH_DELAY_TICKS = 20;
    private static final int REPATH_JITTER_TICKS = 10;
    private static final int CLEAR_ROUTE_TICKS = 28;
    private static final int UPWARD_ESCAPE_REQUEST_TICKS = 80;
    private static final int MAX_SAFE_DROP_BLOCKS = 5;
    private static final int MAX_RETURN_PILLAR_BLOCKS = 10;
    private static final int MAX_DIRECT_RETURN_PILLAR_BLOCKS = 10;
    private static final int LOCAL_ROUTE_HORIZONTAL_RADIUS = 8;
    private static final int LOCAL_ROUTE_VERTICAL_DOWN = 5;
    private static final int LOCAL_ROUTE_VERTICAL_UP = 6;
    private static final int FAILED_LOCAL_ROUTE_COOLDOWN_TICKS = 20 * 3;
    private static final int CLEAR_ATTEMPTS_BEFORE_BREAKING = 2;
    private static final int CLEAR_ATTEMPTS_BEFORE_ESCAPE = 4;
    private static final int FAILED_ROUTE_RELOCATE_RADIUS = 6;
    private static final int FAILED_ROUTE_RELOCATE_VERTICAL_DOWN = 6;
    private static final int FAILED_ROUTE_RELOCATE_VERTICAL_UP = 2;
    private static final int FAILED_ROUTE_RELOCATE_RANDOM_POOL = 12;
    private static final int FAILED_ROUTE_RELOCATE_PATH_CHECKS = 24;
    private static final int FAILED_ROUTE_RELOCATE_COOLDOWN_TICKS = 20 * 4;
    private static final double CLEAR_ROUTE_DISTANCE_SQR = 6.25D;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PillarUpAi pillarUpAi;
    private final PathNavigationAi pathNavigationAi;

    private BlockPos target;
    private int repathTicks;
    private int routeAttempts;
    private int directPillarsPlaced;
    private int failedLocalRouteCooldownTicks;
    private int failedRouteRelocateCooldownTicks;
    private String detail = "";
    private String lastRouteDebug = "";
    private String lastClearDebug = "";
    private String lastPillarDebug = "";

    public ReturnPositionAi(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.pillarUpAi = new PillarUpAi(playerNpc, this.toolAi, Items.DIRT, Blocks.DIRT.defaultBlockState());
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
    }

    public void start(BlockPos target) {
        this.target = target.immutable();
        this.repathTicks = 0;
        this.routeAttempts = 0;
        this.directPillarsPlaced = 0;
        this.failedLocalRouteCooldownTicks = 0;
        this.failedRouteRelocateCooldownTicks = 0;
        this.detail = "";
        this.lastRouteDebug = "";
        this.lastClearDebug = "";
        this.lastPillarDebug = "";
        this.clearBlockAi.stop();
        this.pillarUpAi.clear();
    }

    public void tick(ServerLevel serverLevel, BlockPos target, Predicate<BlockPos> protectedBlock, String moveDetail, String clearDetail) {
        if (target == null) {
            return;
        }
        if (this.target == null || !this.target.equals(target)) {
            this.start(target);
        }
        if (this.failedLocalRouteCooldownTicks > 0) {
            this.failedLocalRouteCooldownTicks--;
        }
        if (this.failedRouteRelocateCooldownTicks > 0) {
            this.failedRouteRelocateCooldownTicks--;
        }

        if (this.clearBlockAi.isRunning()) {
            this.clearBlockAi.tick(serverLevel);
            this.detail = this.clearBlockAi.detail();
            if (!this.clearBlockAi.isRunning()) {
                this.repathTicks = 0;
                this.routeAttempts = 0;
                this.failedLocalRouteCooldownTicks = 0;
            }
            return;
        }

        if (this.pillarUpAi.isRunning()) {
            PillarUpAi.TickResult result = this.pillarUpAi.tick(serverLevel);
            this.detail = this.pillarUpAi.detail();
            if (result == PillarUpAi.TickResult.PLACED) {
                this.directPillarsPlaced++;
                this.repathTicks = 0;
                this.routeAttempts = 0;
                this.failedLocalRouteCooldownTicks = 0;
                this.detail = moveDetail + " (pillared upward)";
            } else if (result == PillarUpAi.TickResult.FAILED) {
                this.tryClearPillarBlocker(serverLevel, protectedBlock);
                this.pillarUpAi.clear();
                this.repathTicks = 0;
            }
            return;
        }

        boolean navigationDone = this.playerNpc.getNavigation().isDone();
        boolean stuck = this.playerNpc.getNavigation().isStuck();
        if (!navigationDone && !stuck && this.repathTicks-- > 0) {
            this.detail = moveDetail;
            return;
        }

        if (navigationDone || stuck) {
            this.routeAttempts++;
        }

        if (this.routeAttempts >= CLEAR_ATTEMPTS_BEFORE_BREAKING
                && this.startClearingRoute(serverLevel, protectedBlock, clearDetail)) {
            this.detail = this.clearBlockAi.detail();
            return;
        }

        boolean allowLocalFallback = this.failedLocalRouteCooldownTicks <= 0;
        boolean moved;
        if (allowLocalFallback) {
            moved = this.pathNavigationAi.moveToWithLocalFallback(
                    serverLevel,
                    target,
                    this.speed,
                    MAX_SAFE_DROP_BLOCKS,
                    LOCAL_ROUTE_HORIZONTAL_RADIUS,
                    LOCAL_ROUTE_VERTICAL_DOWN,
                    LOCAL_ROUTE_VERTICAL_UP);
        } else {
            moved = this.pathNavigationAi.moveTo(serverLevel, target, this.speed, MAX_SAFE_DROP_BLOCKS);
        }
        this.repathTicks = REPATH_DELAY_TICKS + this.playerNpc.getRandom().nextInt(REPATH_JITTER_TICKS + 1);
        this.lastRouteDebug = this.pathNavigationAi.lastMoveFailureDetail();
        if (moved) {
            this.failedLocalRouteCooldownTicks = 0;
            BlockPos localRoute = allowLocalFallback ? this.pathNavigationAi.lastLocalRouteTarget() : null;
            this.detail = localRoute == null ? moveDetail : moveDetail + " (local route @ " + posText(localRoute) + ")";
            return;
        }
        if (allowLocalFallback) {
            this.failedLocalRouteCooldownTicks = FAILED_LOCAL_ROUTE_COOLDOWN_TICKS
                    + this.playerNpc.getRandom().nextInt(FAILED_LOCAL_ROUTE_COOLDOWN_TICKS / 2 + 1);
        } else if (this.lastRouteDebug != null && !this.lastRouteDebug.isBlank()) {
            this.lastRouteDebug = this.lastRouteDebug + " localRoute=cooling";
        }

        boolean verticalEscapeNeeded = this.needsVerticalEscape(serverLevel, target);
        if (verticalEscapeNeeded
                && this.tryStartDirectPillar(
                serverLevel,
                target,
                protectedBlock,
                "clearing return pillar space",
                true)) {
            this.detail = this.pillarUpAi.detail();
            return;
        }

        if (!verticalEscapeNeeded && this.startClearingRoute(serverLevel, protectedBlock, clearDetail)) {
            this.detail = this.clearBlockAi.detail();
            return;
        }

        if (verticalEscapeNeeded && this.startClearingRoute(serverLevel, protectedBlock, clearDetail)) {
            this.detail = this.clearBlockAi.detail();
            return;
        }

        if (verticalEscapeNeeded && this.routeAttempts >= CLEAR_ATTEMPTS_BEFORE_ESCAPE) {
            this.playerNpc.requestForcedUpwardEscapeTo(target, UPWARD_ESCAPE_REQUEST_TICKS, MAX_RETURN_PILLAR_BLOCKS);
            this.detail = moveDetail + " (escaping upward)";
            this.routeAttempts = 0;
            return;
        }

        if (!verticalEscapeNeeded && this.routeAttempts >= CLEAR_ATTEMPTS_BEFORE_ESCAPE) {
            if (this.tryMoveToFailedRouteRelocation(serverLevel, target, moveDetail)) {
                return;
            }
            this.detail = moveDetail + " (moving around after blocked return; " + this.debugText("searching route") + ")";
            this.repathTicks = REPATH_DELAY_TICKS;
            this.routeAttempts = CLEAR_ATTEMPTS_BEFORE_BREAKING;
            return;
        }

        this.detail = moveDetail + " (" + this.debugText("searching route") + ")";
    }

    public String detail(String fallback) {
        return this.detail == null || this.detail.isBlank() ? fallback : this.detail;
    }

    public void stop() {
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.pillarUpAi.clear();
        this.target = null;
        this.repathTicks = 0;
        this.routeAttempts = 0;
        this.directPillarsPlaced = 0;
        this.failedLocalRouteCooldownTicks = 0;
        this.failedRouteRelocateCooldownTicks = 0;
        this.detail = "";
        this.lastRouteDebug = "";
        this.lastClearDebug = "";
        this.lastPillarDebug = "";
    }

    private boolean tryStartDirectPillar(
            ServerLevel serverLevel,
            BlockPos target,
            Predicate<BlockPos> protectedBlock,
            String clearDetail,
            boolean forceAfterFailedRoute) {
        BlockPos feet = this.playerNpc.blockPosition();
        boolean targetAbove = target.getY() > feet.getY() + 1;
        if (!targetAbove && !forceAfterFailedRoute) {
            this.lastPillarDebug = "pillar skipped targetY=" + target.getY() + " feetY=" + feet.getY();
            return false;
        }
        if (this.directPillarsPlaced >= MAX_DIRECT_RETURN_PILLAR_BLOCKS) {
            this.lastPillarDebug = "pillar skipped maxDirect=" + MAX_DIRECT_RETURN_PILLAR_BLOCKS;
            return false;
        }

        String blockerText = this.pillarUpAi.startBlocker(serverLevel, feet);
        if (blockerText.isBlank() && this.pillarUpAi.start(serverLevel, feet)) {
            this.lastPillarDebug = "pillar started @ " + posText(feet);
            return true;
        }

        BlockPos blocker = this.pillarUpAi.startBlockerPos(serverLevel, feet);
        if (blocker == null || protectedBlock.test(blocker)) {
            this.lastPillarDebug = blocker == null
                    ? "pillar blocked: " + blockerText
                    : "pillar blocker protected @ " + posText(blocker) + " reason=" + blockerText;
            return false;
        }
        boolean clearing = this.clearBlockAi.start(
                serverLevel,
                blocker,
                this::isClearable,
                clearDetail,
                CLEAR_ROUTE_TICKS,
                CLEAR_ROUTE_DISTANCE_SQR);
        this.lastPillarDebug = clearing
                ? "pillar clearing blocker @ " + posText(blocker) + " reason=" + blockerText
                : "pillar blocker uncleared @ " + posText(blocker) + " reason=" + blockerText;
        return clearing;
    }

    private void tryClearPillarBlocker(ServerLevel serverLevel, Predicate<BlockPos> protectedBlock) {
        BlockPos blocker = this.pillarUpAi.consumeLastFailureBlockerPos();
        if (blocker == null || protectedBlock.test(blocker)) {
            this.lastPillarDebug = blocker == null ? "pillar failed without blocker" : "pillar failed protected blocker @ " + posText(blocker);
            return;
        }
        boolean clearing = this.clearBlockAi.start(
                serverLevel,
                blocker,
                this::isClearable,
                "clearing return pillar space",
                CLEAR_ROUTE_TICKS,
                CLEAR_ROUTE_DISTANCE_SQR);
        this.lastPillarDebug = clearing
                ? "pillar failed; clearing blocker @ " + posText(blocker)
                : "pillar failed; blocker not clearable @ " + posText(blocker);
    }

    private boolean startClearingRoute(ServerLevel serverLevel, Predicate<BlockPos> protectedBlock, String clearDetail) {
        if (this.target == null) {
            this.lastClearDebug = "clear skipped target=null";
            return false;
        }
        List<BlockPos> candidates = ClearBlockAi.gatherObstructionCandidates(
                this.playerNpc.blockPosition(),
                this.target,
                this.target);
        candidates.removeIf(pos -> pos.equals(this.target) || protectedBlock.test(pos));
        if (candidates.isEmpty()) {
            this.lastClearDebug = "clear skipped candidates=0";
            return false;
        }
        Optional<BlockPos> clearTarget = ClearBlockAi.findNearestClearable(
                serverLevel,
                this.playerNpc.blockPosition(),
                candidates,
                state -> this.isClearable(state),
                CLEAR_ROUTE_DISTANCE_SQR);
        if (clearTarget.isEmpty()) {
            this.lastClearDebug = "clear none candidates=" + candidates.size();
            return false;
        }

        boolean started = this.clearBlockAi.start(
                serverLevel,
                clearTarget.get(),
                this::isClearable,
                clearDetail,
                CLEAR_ROUTE_TICKS,
                CLEAR_ROUTE_DISTANCE_SQR);
        this.lastClearDebug = started
                ? "clear started @ " + posText(clearTarget.get())
                : "clear start failed @ " + posText(clearTarget.get());
        return started;
    }

    private boolean isClearable(BlockState state) {
        return state != null && !state.isAir();
    }

    private boolean needsVerticalEscape(ServerLevel serverLevel, BlockPos target) {
        BlockPos current = this.playerNpc.blockPosition();
        return target.getY() > current.getY() + 1 && !serverLevel.canSeeSky(current.above());
    }

    private boolean tryMoveToFailedRouteRelocation(ServerLevel serverLevel, BlockPos target, String moveDetail) {
        if (this.failedRouteRelocateCooldownTicks > 0) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        int radius = FAILED_ROUTE_RELOCATE_RADIUS;
        int radiusSqr = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int horizontalSqr = dx * dx + dz * dz;
                if (horizontalSqr < 4 || horizontalSqr > radiusSqr) {
                    continue;
                }
                for (int dy = -FAILED_ROUTE_RELOCATE_VERTICAL_DOWN; dy <= FAILED_ROUTE_RELOCATE_VERTICAL_UP; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (PathNavigationAi.canStandAt(serverLevel, candidate)) {
                        candidates.add(candidate.immutable());
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            this.lastRouteDebug = this.lastRouteDebug + " relocateCandidates=0";
            return false;
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> this.relocationScore(serverLevel, feet, target, pos))
                .thenComparingDouble(pos -> pos.distSqr(feet)));
        Optional<BlockPos> relocation = this.pathNavigationAi.findReachableRandomizedCandidate(
                serverLevel,
                candidates,
                FAILED_ROUTE_RELOCATE_RANDOM_POOL,
                FAILED_ROUTE_RELOCATE_PATH_CHECKS,
                MAX_SAFE_DROP_BLOCKS
        );
        if (relocation.isEmpty()) {
            this.lastRouteDebug = this.lastRouteDebug + " relocate=none";
            return false;
        }

        BlockPos stand = relocation.get();
        if (!this.pathNavigationAi.moveTo(serverLevel, stand, this.speed, MAX_SAFE_DROP_BLOCKS)) {
            this.lastRouteDebug = this.lastRouteDebug + " relocateMoveFailed @ " + posText(stand);
            return false;
        }

        this.failedRouteRelocateCooldownTicks = FAILED_ROUTE_RELOCATE_COOLDOWN_TICKS;
        this.repathTicks = REPATH_DELAY_TICKS;
        this.routeAttempts = 0;
        this.detail = moveDetail + " (moving around @ " + posText(stand) + ")";
        return true;
    }

    private double relocationScore(ServerLevel serverLevel, BlockPos feet, BlockPos target, BlockPos candidate) {
        double score = candidate.distSqr(target) * 0.35D + candidate.distSqr(feet);
        if (serverLevel.canSeeSky(candidate.above())) {
            score -= 24.0D;
        }
        if (candidate.getY() < feet.getY()) {
            score -= Math.min(18.0D, (feet.getY() - candidate.getY()) * 3.0D);
        }
        Direction directionToTarget = directionToward(feet, target);
        if (directionToTarget != null) {
            BlockPos forward = feet.relative(directionToTarget);
            score += Math.max(0.0D, 8.0D - candidate.distSqr(forward));
        }
        return score;
    }

    private static Direction directionToward(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        if (dz != 0) {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return null;
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private String debugText(String prefix) {
        String route = this.lastRouteDebug == null || this.lastRouteDebug.isBlank() ? "route=unknown" : this.lastRouteDebug;
        String clear = this.lastClearDebug == null || this.lastClearDebug.isBlank() ? "clear=unknown" : this.lastClearDebug;
        String pillar = this.lastPillarDebug == null || this.lastPillarDebug.isBlank() ? "pillar=unknown" : this.lastPillarDebug;
        return prefix
                + "; attempts=" + this.routeAttempts
                + "; " + route
                + "; " + clear
                + "; " + pillar;
    }
}
