package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ResourceAi;
import com.pla.smart_npc.entity.ai.StoneAi;
import com.pla.smart_npc.entity.ai.StoneAi.StoneCluster;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

public class GatherStoneGoal extends Goal {
    private static final int SEARCH_RADIUS = 24;
    private static final int MAX_GATHER_TICKS = 20 * 120;
    private static final int REQUIRED_BREAK_TICKS = 70;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final double BREAK_DISTANCE_SQR = 4.0D * 4.0D;
    private static final int MAX_STONE_PATH_CHECKS = 12;
    private static final int MAX_TARGET_SELECTION_CHECKS = 12;
    private static final int PHASE_RECHECK_INTERVAL_TICKS = 20;
    private static final int MAX_STAND_SAFE_DROP_BLOCKS = 3;
    private static final int DIG_SITE_STONE_SCAN_BELOW = 4;
    private static final int CLEAR_OBSTRUCTION_TICKS = 24;
    private static final double CLEAR_OBSTRUCTION_DISTANCE_SQR = 5.0D * 5.0D;
    private static final double STAND_REACHED_HORIZONTAL_SQR = 0.9D * 0.9D;
    private static final int MAX_STAND_ROUTE_ATTEMPTS_BEFORE_CLEAR = 2;
    private static final int PROTECTED_BASE_SUPPORT_DEPTH = 3;
    private static final int MAX_DESCENDING_ACCESS_STEPS = 12;
    private static final int ACCESS_RETRY_TICKS = 20;
    private static final int MAX_FAILED_ACCESS_ATTEMPTS_BEFORE_RESELECT = 8;
    private static final int FORCED_ACCESS_RADIUS = 4;
    private static final int FORCED_ACCESS_DOWN = 5;
    private static final int FORCED_ACCESS_UP = 2;
    private static final double FORCED_CLEAR_DISTANCE_SQR = 6.0D * 6.0D;
    private static final int FAILED_TARGET_SKIP_TICKS = 20 * 12;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private BlockPos targetPos;
    private BlockPos standPos;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PathNavigationAi pathNavigationAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private final Queue<BlockPos> stoneQueue = new ArrayDeque<>();
    private int gatherTicks;
    private int repathTicks;
    private int standRouteAttempts;
    private int failedAccessAttempts;
    private int phaseRecheckTicks;
    private boolean phaseStillActive = true;
    private BlockPos temporarilyBlockedTarget;
    private long temporarilyBlockedTargetUntilTick;

    public GatherStoneGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean hasNearbyStoneTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!hasPreparedBaseForStone(playerNpc, serverLevel)) {
            return false;
        }
        return findStoneTarget(playerNpc, serverLevel, SEARCH_RADIUS).isPresent();
    }

    public static boolean isStoneSupplyPhaseActive(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return hasPreparedBaseForStone(playerNpc, serverLevel)
                && (playerNpc.shouldPrioritizeCobblestoneGathering()
                || PlayerNpcBuildMaterialUtil.needsStoneForCurrentBuild(serverLevel, playerNpc));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getGatherCooldown() > 0) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (this.shouldStayHomeForWeather(serverLevel)
                || !isStoneSupplyPhaseActive(this.playerNpc, serverLevel)
                || BuildHouseGoal.hasReadyHomeBuildWork(this.playerNpc, serverLevel)) {
            return false;
        }

        return this.selectTarget(serverLevel);
    }

    @Override
    public boolean canContinueToUse() {
        return this.targetPos != null
                && this.gatherTicks < MAX_GATHER_TICKS
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.canContinueStoneWork(serverLevel);
    }

    @Override
    public void start() {
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.standRouteAttempts = 0;
        this.failedAccessAttempts = 0;
        this.phaseRecheckTicks = PHASE_RECHECK_INTERVAL_TICKS;
        this.phaseStillActive = true;
        this.playerNpc.setCurrentAiState("ai.player_npc.gathering_stone");
        this.toolAi.equipTool(PickaxeItem.class);
        this.updateDetail();
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.moveToStandPos(serverLevel);
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.gatherTicks++;
        if (this.tickClearBlock(serverLevel)) {
            this.updateDetail();
            return;
        }

        if (this.targetPos == null || !this.isValidTarget(serverLevel, this.targetPos)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.breakingBlockAi.stop();
            if (!this.selectTarget(serverLevel)) {
                this.targetPos = null;
            }
            return;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                30.0F,
                30.0F
        );

        if (!this.isAtMiningStand(serverLevel)) {
            this.breakingBlockAi.stop();
            if (this.shouldRetryPathWork()) {
                boolean navigationEnded = this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck();
                if (navigationEnded
                        && this.standRouteAttempts >= MAX_STAND_ROUTE_ATTEMPTS_BEFORE_CLEAR
                        && this.startClearingRoute(serverLevel)) {
                    this.standRouteAttempts = 0;
                    this.failedAccessAttempts = 0;
                    this.repathTicks = REPATH_INTERVAL_TICKS;
                    this.updateDetail();
                    return;
                }

                boolean moved = this.moveToStandPos(serverLevel);
                if (!moved) {
                    if (this.startClearingRoute(serverLevel)) {
                        this.standRouteAttempts = 0;
                        this.failedAccessAttempts = 0;
                        this.repathTicks = REPATH_INTERVAL_TICKS;
                        this.updateDetail();
                        return;
                    }
                    if (this.recordAccessFailureAndShouldReselect(serverLevel)) {
                        this.playerNpc.clearBlockBreakProgress(this.targetPos);
                        if (!this.selectTarget(serverLevel)) {
                            this.targetPos = null;
                        }
                    }
                } else if (moved && navigationEnded) {
                    this.standRouteAttempts++;
                    if (this.standRouteAttempts >= MAX_STAND_ROUTE_ATTEMPTS_BEFORE_CLEAR
                            && this.startClearingRoute(serverLevel)) {
                        this.standRouteAttempts = 0;
                        this.failedAccessAttempts = 0;
                        this.repathTicks = REPATH_INTERVAL_TICKS;
                        this.updateDetail();
                        return;
                    }
                } else if (moved) {
                    this.standRouteAttempts = 0;
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            this.updateDetail();
            return;
        }

        if (this.distanceToTargetSqr() > BREAK_DISTANCE_SQR) {
            this.breakingBlockAi.stop();
            if (this.shouldRetryPathWork()) {
                boolean moved = this.moveToStandPos(serverLevel);
                if (!moved) {
                    if (this.startClearingRoute(serverLevel)) {
                        this.standRouteAttempts = 0;
                        this.failedAccessAttempts = 0;
                        this.repathTicks = REPATH_INTERVAL_TICKS;
                        this.updateDetail();
                        return;
                    }
                    if (this.recordAccessFailureAndShouldReselect(serverLevel)) {
                        if (!this.selectTarget(serverLevel)) {
                            this.targetPos = null;
                        }
                    }
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            this.updateDetail();
            return;
        }

        this.mineTarget(serverLevel);
        this.updateDetail();
    }

    @Override
    public void stop() {
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.toolAi.restoreMainHand();
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setGatherCooldown(20);
        }
        this.targetPos = null;
        this.standPos = null;
        this.stoneQueue.clear();
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.standRouteAttempts = 0;
        this.failedAccessAttempts = 0;
        this.phaseRecheckTicks = 0;
        this.phaseStillActive = true;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private boolean selectTarget(ServerLevel serverLevel) {
        if (this.selectQueuedTarget(serverLevel)) {
            return true;
        }

        this.stoneQueue.clear();
        BlockPos center = this.playerNpc.blockPosition();
        for (StoneCluster cluster : StoneAi.findNearby(
                serverLevel,
                center,
                SEARCH_RADIUS,
                pos -> isAllowedStoneSearchPos(this.playerNpc, center, pos))) {
            this.stoneQueue.addAll(cluster.stonesNearestFirst(center));
            if (this.selectQueuedTarget(serverLevel)) {
                return true;
            }
            this.stoneQueue.clear();
        }

        return false;
    }

    private boolean selectQueuedTarget(ServerLevel serverLevel) {
        int checks = 0;
        while (!this.stoneQueue.isEmpty()) {
            BlockPos candidate = this.stoneQueue.poll();
            if (this.isTemporarilyBlocked(serverLevel, candidate)) {
                continue;
            }
            if (checks++ >= MAX_TARGET_SELECTION_CHECKS) {
                break;
            }
            Optional<BlockPos> stand = findStandPos(this.playerNpc, serverLevel, candidate, this.pathNavigationAi);
            if (stand.isEmpty()) {
                continue;
            }

            this.targetPos = candidate.immutable();
            this.standPos = stand.get();
            this.standRouteAttempts = 0;
            this.failedAccessAttempts = 0;
            this.clearBlockAi.stop();
            this.breakingBlockAi.stop();
            this.toolAi.equipTool(PickaxeItem.class);
            return true;
        }

        this.targetPos = null;
        this.standPos = null;
        this.failedAccessAttempts = 0;
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        return false;
    }

    private boolean shouldStayHomeForWeather(ServerLevel serverLevel) {
        return PlayerNpcHomeUtil.getHome(this.playerNpc).isPresent()
                && (serverLevel.isNight() || serverLevel.isThundering());
    }

    private static Optional<BlockPos> findStoneTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel, int radius) {
        BlockPos center = playerNpc.blockPosition();
        PathNavigationAi pathNavigationAi = new PathNavigationAi(playerNpc);
        int checks = 0;
        for (StoneCluster cluster : StoneAi.findNearby(
                serverLevel,
                center,
                radius,
                pos -> isAllowedStoneSearchPos(playerNpc, center, pos))) {
            for (BlockPos candidate : cluster.stonesNearestFirst(center)) {
                if (checks++ >= MAX_STONE_PATH_CHECKS) {
                    return Optional.empty();
                }
                if (StoneAi.isStone(serverLevel.getBlockState(candidate))
                        && !isInsideHomeArea(playerNpc, candidate)
                        && findStandPos(playerNpc, serverLevel, candidate, pathNavigationAi).isPresent()) {
                    return Optional.of(candidate.immutable());
                }
            }
        }
        return Optional.empty();
    }

    private void mineTarget(ServerLevel serverLevel) {
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                this::isMineableTargetState,
                REQUIRED_BREAK_TICKS,
                "mining stone"
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }

        if (!this.selectTarget(serverLevel)) {
            this.targetPos = null;
        }
    }

    private static Optional<BlockPos> findStandPos(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos target,
            PathNavigationAi pathNavigationAi
    ) {
        return Direction.Plane.HORIZONTAL.stream()
                .map(direction -> target.relative(direction))
                .filter(pos -> canStandAtOrCanClearStandAt(serverLevel, pos))
                .min(Comparator.comparingDouble(pos -> pos.distSqr(playerNpc.blockPosition())))
                .flatMap(nearest -> {
                    Optional<BlockPos> reachable = Direction.Plane.HORIZONTAL.stream()
                            .map(direction -> target.relative(direction))
                            .filter(pos -> canStandAt(serverLevel, pos))
                            .filter(pos -> pathNavigationAi.canReachOrSafelyDropTo(serverLevel, pos, MAX_STAND_SAFE_DROP_BLOCKS))
                            .min(Comparator.comparingDouble(pos -> pos.distSqr(playerNpc.blockPosition())));
                    return reachable.or(() -> Optional.of(nearest));
                })
                .map(BlockPos::immutable);
    }

    private boolean isValidTarget(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        return StoneAi.isStone(state) && !isInsideHomeArea(this.playerNpc, pos);
    }

    private boolean isMineableTargetState(BlockState state) {
        return StoneAi.isStone(state);
    }

    private static boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos);
    }

    private static boolean canStandAtOrCanClearStandAt(ServerLevel serverLevel, BlockPos pos) {
        return canStandAt(serverLevel, pos) || canClearStandAt(serverLevel, pos);
    }

    private static boolean canClearStandAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos)
                || !serverLevel.isInWorldBounds(pos.above())
                || !serverLevel.getWorldBorder().isWithinBounds(pos)
                || !serverLevel.getWorldBorder().isWithinBounds(pos.above())) {
            return false;
        }

        return serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below())
                && canClearBodySpace(serverLevel, pos)
                && canClearBodySpace(serverLevel, pos.above());
    }

    private static boolean canClearBodySpace(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        if (state.getCollisionShape(serverLevel, pos).isEmpty()) {
            return serverLevel.getFluidState(pos).isEmpty();
        }

        return ClearBlockAi.isPhysicalObstructionState(state)
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && serverLevel.getBlockEntity(pos) == null;
    }

    private static boolean isActionableStoneTarget(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos pos,
            PathNavigationAi pathNavigationAi
    ) {
        Optional<BlockPos> stand = findStandPos(playerNpc, serverLevel, pos, pathNavigationAi);
        return StoneAi.isStone(serverLevel.getBlockState(pos))
                && !isInsideHomeArea(playerNpc, pos)
                && stand.isPresent()
                && (canReachStand(playerNpc, serverLevel, stand.get(), pathNavigationAi)
                || hasImmediateAccessClearCandidate(playerNpc, serverLevel, pos, stand.get()));
    }

    private static boolean isAllowedStoneSearchPos(PlayerNpcEntity playerNpc, BlockPos center, BlockPos pos) {
        return pos.getY() >= center.getY() - DIG_SITE_STONE_SCAN_BELOW && !isInsideHomeArea(playerNpc, pos);
    }

    private static boolean hasPreparedBaseForStone(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return PlayerNpcHomeUtil.getHome(playerNpc).isPresent()
                && !TerraformBuildSiteGoal.hasActionablePrepWork(playerNpc, serverLevel);
    }

    private static boolean isInsideHomeArea(PlayerNpcEntity playerNpc, BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        return home.isPresent()
                && (PlayerNpcHomeUtil.isInside(home.get(), pos) || isInsideHomeSupport(home.get(), pos));
    }

    private static boolean isInsideHomeSupport(PlayerNpcHomeUtil.HomeArea homeArea, BlockPos pos) {
        return pos.getX() >= homeArea.origin().getX()
                && pos.getX() < homeArea.origin().getX() + homeArea.width()
                && pos.getZ() >= homeArea.origin().getZ()
                && pos.getZ() < homeArea.origin().getZ() + homeArea.depth()
                && pos.getY() >= homeArea.origin().getY() - PROTECTED_BASE_SUPPORT_DEPTH
                && pos.getY() < homeArea.origin().getY();
    }

    private boolean moveToStandPos(ServerLevel serverLevel) {
        if (serverLevel == null || this.targetPos == null) {
            return false;
        }
        if (this.standPos == null || !canStandAt(serverLevel, this.standPos)) {
            this.standPos = findStandPos(this.playerNpc, serverLevel, this.targetPos, this.pathNavigationAi).orElse(null);
        }
        if (this.standPos == null) {
            return false;
        }

        return this.pathNavigationAi.moveTo(serverLevel, this.standPos, this.speed, MAX_STAND_SAFE_DROP_BLOCKS);
    }

    private boolean isAtMiningStand(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (isStandAdjacentToTarget(serverLevel, feet, this.targetPos)) {
            this.standPos = feet.immutable();
            this.standRouteAttempts = 0;
            return true;
        }

        if (this.standPos == null || feet.getY() != this.standPos.getY()) {
            return false;
        }

        double dx = this.playerNpc.getX() - (this.standPos.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (this.standPos.getZ() + 0.5D);
        return dx * dx + dz * dz <= STAND_REACHED_HORIZONTAL_SQR
                && isStandAdjacentToTarget(serverLevel, this.standPos, this.targetPos);
    }

    private static boolean isStandAdjacentToTarget(ServerLevel serverLevel, BlockPos stand, BlockPos target) {
        if (stand == null || target == null || stand.getY() != target.getY() || !canStandAt(serverLevel, stand)) {
            return false;
        }

        int dx = Math.abs(stand.getX() - target.getX());
        int dz = Math.abs(stand.getZ() - target.getZ());
        return dx + dz == 1;
    }

    private double distanceToTargetSqr() {
        return this.targetPos == null
                ? Double.MAX_VALUE
                : this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D);
    }

    private void updateDetail() {
        if (this.clearBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.clearBlockAi.detail()
                    + " " + ResourceAi.countStone(this.playerNpc) + "/" + this.playerNpc.getStoneSupplyGoal());
            return;
        }
        if (this.breakingBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.breakingBlockAi.detail()
                    + " " + ResourceAi.countStone(this.playerNpc) + "/" + this.playerNpc.getStoneSupplyGoal());
            return;
        }
        if (this.targetPos == null) {
            this.playerNpc.setCurrentAiDetail("searching for stone");
            return;
        }
        this.playerNpc.setCurrentAiDetail("stone @ "
                + this.targetPos.getX() + " "
                + this.targetPos.getY() + " "
                + this.targetPos.getZ()
                + " " + ResourceAi.countStone(this.playerNpc) + "/" + this.playerNpc.getStoneSupplyGoal());
    }

    private boolean tickClearBlock(ServerLevel serverLevel) {
        if (!this.clearBlockAi.isRunning()) {
            return false;
        }

        ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            return true;
        }

        if (result == ClearBlockAi.TickResult.DONE) {
            this.failedAccessAttempts = 0;
        } else if (result == ClearBlockAi.TickResult.FAILED) {
            this.failedAccessAttempts++;
        }
        this.repathTicks = 0;
        return false;
    }

    private boolean startClearingRoute(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return false;
        }

        List<BlockPos> candidates = new ArrayList<>(ClearBlockAi.gatherObstructionCandidates(
                this.playerNpc.blockPosition(),
                this.standPos,
                this.targetPos
        ));
        addLocalRouteCandidates(candidates, this.playerNpc.blockPosition(), this.standPos, this.targetPos);
        addDescendingAccessCandidates(candidates, this.playerNpc.blockPosition(), this.standPos, this.targetPos);
        addForcedNearbyAccessCandidates(candidates, this.playerNpc.blockPosition(), this.standPos, this.targetPos);
        candidates.removeIf(pos -> pos.equals(this.targetPos) || this.isProtectedHomeBlock(pos));
        return this.clearBlockAi.startNearest(
                serverLevel,
                candidates,
                this::isClearablePathState,
                "clearing stone path",
                CLEAR_OBSTRUCTION_TICKS,
                FORCED_CLEAR_DISTANCE_SQR,
                true
        );
    }

    private boolean isClearablePathState(BlockState state) {
        return isClearablePathStateStatic(state);
    }

    private boolean canContinueStoneWork(ServerLevel serverLevel) {
        if (this.phaseRecheckTicks > 0) {
            this.phaseRecheckTicks--;
            return this.phaseStillActive;
        }

        this.phaseRecheckTicks = PHASE_RECHECK_INTERVAL_TICKS;
        this.phaseStillActive = isStoneSupplyPhaseActive(this.playerNpc, serverLevel)
                && !this.shouldStayHomeForWeather(serverLevel)
                && hasPreparedBaseForStone(this.playerNpc, serverLevel);
        return this.phaseStillActive;
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        return home.isPresent()
                && (PlayerNpcHomeUtil.isInside(home.get(), pos) || isInsideHomeSupport(home.get(), pos));
    }

    private static void addLocalRouteCandidates(List<BlockPos> candidates, BlockPos feet, BlockPos stand, BlockPos target) {
        if (feet == null) {
            return;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            addBodyColumn(candidates, feet.relative(direction));
        }

        BlockPos routeTarget = stand == null ? target : stand;
        if (routeTarget == null) {
            return;
        }

        int stepX = Integer.compare(routeTarget.getX(), feet.getX());
        int stepZ = Integer.compare(routeTarget.getZ(), feet.getZ());
        if (stepX != 0) {
            addBodyColumn(candidates, feet.offset(stepX, 0, 0));
        }
        if (stepZ != 0) {
            addBodyColumn(candidates, feet.offset(0, 0, stepZ));
        }
        if (stepX != 0 && stepZ != 0) {
            addBodyColumn(candidates, feet.offset(stepX, 0, stepZ));
        }
    }

    private static void addDescendingAccessCandidates(List<BlockPos> candidates, BlockPos feet, BlockPos stand, BlockPos target) {
        if (feet == null || target == null) {
            return;
        }

        BlockPos routeTarget = stand == null ? nearestSideTarget(feet, target) : stand;
        BlockPos cursor = feet;
        for (int step = 0; step < MAX_DESCENDING_ACCESS_STEPS; step++) {
            if (cursor.equals(routeTarget)) {
                break;
            }

            int stepX = Integer.compare(routeTarget.getX(), cursor.getX());
            int stepZ = Integer.compare(routeTarget.getZ(), cursor.getZ());
            int nextY = cursor.getY();
            if (cursor.getY() > routeTarget.getY()) {
                nextY--;
                candidates.add(cursor.below());
                candidates.add(cursor.below(2));
            } else if (cursor.getY() < routeTarget.getY()) {
                nextY++;
            }

            BlockPos nextFeet = new BlockPos(cursor.getX() + stepX, nextY, cursor.getZ() + stepZ);
            addBodyColumn(candidates, nextFeet);
            cursor = nextFeet;
        }

        addBodyColumn(candidates, routeTarget);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            addBodyColumn(candidates, target.relative(direction));
        }
    }

    private static void addForcedNearbyAccessCandidates(List<BlockPos> candidates, BlockPos feet, BlockPos stand, BlockPos target) {
        if (feet == null || target == null) {
            return;
        }

        BlockPos routeTarget = stand == null ? nearestSideTarget(feet, target) : stand;
        int targetDx = routeTarget.getX() - feet.getX();
        int targetDz = routeTarget.getZ() - feet.getZ();
        int currentHorizontalDistance = horizontalDistanceSqr(feet, routeTarget);
        for (int dx = -FORCED_ACCESS_RADIUS; dx <= FORCED_ACCESS_RADIUS; dx++) {
            for (int dz = -FORCED_ACCESS_RADIUS; dz <= FORCED_ACCESS_RADIUS; dz++) {
                if (dx * dx + dz * dz > FORCED_ACCESS_RADIUS * FORCED_ACCESS_RADIUS) {
                    continue;
                }
                if ((targetDx != 0 || targetDz != 0) && dx * targetDx + dz * targetDz < 0) {
                    continue;
                }

                BlockPos column = feet.offset(dx, 0, dz);
                if (!column.equals(feet)
                        && horizontalDistanceSqr(column, routeTarget) > currentHorizontalDistance + 4) {
                    continue;
                }

                for (int yOffset = FORCED_ACCESS_UP; yOffset >= -FORCED_ACCESS_DOWN; yOffset--) {
                    candidates.add(column.offset(0, yOffset, 0));
                }
            }
        }
    }

    private static boolean hasImmediateAccessClearCandidate(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos target, BlockPos stand) {
        List<BlockPos> candidates = new ArrayList<>(ClearBlockAi.gatherObstructionCandidates(
                playerNpc.blockPosition(),
                stand,
                target
        ));
        addLocalRouteCandidates(candidates, playerNpc.blockPosition(), stand, target);
        addDescendingAccessCandidates(candidates, playerNpc.blockPosition(), stand, target);
        addForcedNearbyAccessCandidates(candidates, playerNpc.blockPosition(), stand, target);
        candidates.removeIf(pos -> pos.equals(target) || isInsideHomeArea(playerNpc, pos));
        return ClearBlockAi.findNearestAccessibleClearable(
                serverLevel,
                playerNpc,
                candidates,
                GatherStoneGoal::isClearablePathStateStatic,
                FORCED_CLEAR_DISTANCE_SQR
        ).isPresent();
    }

    private static boolean canReachStand(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos stand,
            PathNavigationAi pathNavigationAi
    ) {
        return canStandAt(serverLevel, stand)
                && pathNavigationAi.canReachOrSafelyDropTo(serverLevel, stand, MAX_STAND_SAFE_DROP_BLOCKS);
    }

    private static boolean isClearablePathStateStatic(BlockState state) {
        return ClearBlockAi.isPhysicalObstructionState(state);
    }

    private static BlockPos nearestSideTarget(BlockPos feet, BlockPos target) {
        return Direction.Plane.HORIZONTAL.stream()
                .map(target::relative)
                .min(Comparator.comparingDouble(pos -> pos.distSqr(feet)))
                .orElse(target);
    }

    private boolean shouldRetryPathWork() {
        if (this.repathTicks > 0 && !this.playerNpc.getNavigation().isStuck()) {
            this.repathTicks--;
            return false;
        }
        return true;
    }

    private boolean recordAccessFailureAndShouldReselect(ServerLevel serverLevel) {
        this.failedAccessAttempts++;
        this.repathTicks = ACCESS_RETRY_TICKS;
        if (this.failedAccessAttempts < MAX_FAILED_ACCESS_ATTEMPTS_BEFORE_RESELECT) {
            return false;
        }

        this.markTargetTemporarilyBlocked(serverLevel);
        return true;
    }

    private boolean isTemporarilyBlocked(ServerLevel serverLevel, BlockPos pos) {
        if (this.temporarilyBlockedTarget == null || pos == null) {
            return false;
        }
        if (serverLevel.getGameTime() >= this.temporarilyBlockedTargetUntilTick) {
            this.temporarilyBlockedTarget = null;
            this.temporarilyBlockedTargetUntilTick = 0L;
            return false;
        }
        return this.temporarilyBlockedTarget.equals(pos);
    }

    private void markTargetTemporarilyBlocked(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return;
        }
        this.temporarilyBlockedTarget = this.targetPos.immutable();
        this.temporarilyBlockedTargetUntilTick = serverLevel.getGameTime() + FAILED_TARGET_SKIP_TICKS;
    }

    private static int horizontalDistanceSqr(BlockPos first, BlockPos second) {
        int dx = first.getX() - second.getX();
        int dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static void addBodyColumn(List<BlockPos> candidates, BlockPos feet) {
        if (feet == null) {
            return;
        }
        candidates.add(feet);
        candidates.add(feet.above());
        candidates.add(feet.above(2));
    }
}
