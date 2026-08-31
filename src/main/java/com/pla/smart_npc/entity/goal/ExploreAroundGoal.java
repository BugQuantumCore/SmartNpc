package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PathStuckFallbackAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

public class ExploreAroundGoal extends Goal {
    private static final String LOG_EXPLORATION_DETAIL = "exploring for logs";
    private static final String STONE_EXPLORATION_DETAIL = "exploring for stone";
    private static final int[][] SEARCH_DISTANCE_BANDS = {
            {12, 18},
            {9, 12},
            {6, 9},
            {3, 6},
            {0, 3}
    };
    private static final int ATTEMPTS_PER_RADIUS = 1;
    private static final int MAX_EXPLORE_TICKS = 20 * 45;
    private static final int REPATH_INTERVAL_TICKS = 20 * 2;
    private static final int RADIUS_RETRY_COOLDOWN_TICKS = 20;
    private static final int WATER_ESCAPE_RETRY_MIN_TICKS = 20 * 8;
    private static final int WATER_ESCAPE_RETRY_RANDOM_TICKS = 20 * 8;
    private static final int UPWARD_ESCAPE_REQUEST_TICKS = 20 * 8;
    private static final int MAX_EXPLORE_PILLAR_BLOCKS = 10;
    private static final int MAX_EXPLORE_SAFE_DROP_BLOCKS = 5;
    private static final int EXPLORE_CAN_USE_INTERVAL_TICKS = 20;
    private static final int CONTINUE_PREDICATE_INTERVAL_TICKS = 20;
    private static final int ESCAPE_REQUEST_COOLDOWN_TICKS = 20 * 5;
    private static final int FAILED_CLIMB_FALLBACK_REQUEST_TICKS = 20 * 15;
    private static final int EXPLORATION_CLIMB_OWNER_TICKS = 20 * 60;
    private static final int FAILED_CLIMB_FALLBACK_RETRY_TICKS = 20;
    private static final int FAILED_CLIMB_FALLBACK_RADIUS = 8;
    private static final int FAILED_CLIMB_FALLBACK_MIN_DISTANCE = 4;
    private static final int FAILED_CLIMB_FALLBACK_VERTICAL_RANGE = 2;
    private static final int FAILED_CLIMB_FALLBACK_PATH_CHECKS = 1;
    private static final int BUILDING_LOG_LOCAL_SURFACE_RADIUS = 18;
    private static final int BUILDING_LOG_LOCAL_SURFACE_MIN_RADIUS = 4;
    private static final int BUILDING_LOG_LOCAL_SURFACE_COLUMN_CHECKS = 64;
    private static final int BUILDING_LOG_LOCAL_SURFACE_SCAN_STRIDE = 67;
    private static final int BUILDING_LOG_LOCAL_SURFACE_RANDOM_POOL = 10;
    private static final int BUILDING_LOG_LOCAL_SURFACE_PATH_CHECKS = 1;
    private static final float EXPLORE_SELECTION_PATH_NODE_MULTIPLIER = 0.03F;
    private static final float ACTIVE_EXPLORATION_PATH_NODE_MULTIPLIER = 0.05F;
    private static final double BUILDING_LOG_SCAN_RESET_DISTANCE_SQR = 4.0D * 4.0D;
    private static final int RETURN_HOME_REQUEST_TICKS = 20 * 120;
    private static final int RETURN_HOME_RETRY_COOLDOWN_TICKS = 20 * 15;
    private static final int MAX_LOCAL_ESCAPE_PATH_CHECKS = 1;
    private static final int LOCAL_SURFACE_ESCAPE_RADIUS = 6;
    private static final int MIN_LOCAL_SURFACE_NEIGHBORS = 2;
    private static final int RECENT_ROUTE_MEMORY_TICKS = 20;
    private static final int ROUTE_FOLIAGE_NODE_LOOKAHEAD = 3;
    private static final int MAX_ROUTE_FOLIAGE_CLEAR_ATTEMPTS = 4;
    private static final double ROUTE_FOLIAGE_CLEAR_DISTANCE_SQR = 4.5D * 4.5D;
    private static final int MINING_LOG_COLUMN_DROP_RADIUS = 8;
    private static final int MINING_LOG_COLUMN_DROP_MAX_FALL = 6;
    private static final int MINING_LOG_COLUMN_DROP_TICKS = 20 * 2;
    private static final int MINING_LOG_COLUMN_DROP_MAX_SOLID_SIDE_SUPPORTS = 1;
    private static final float INITIAL_SPRINT_CHANCE = 0.35F;
    private static final float WALK_TO_SPRINT_CHANCE = 0.55F;
    private static final int MIN_WALK_PACE_TICKS = 20 * 3;
    private static final int MAX_WALK_PACE_TICKS = 20 * 8;
    private static final int MIN_SPRINT_PACE_TICKS = 20 * 2;
    private static final int MAX_SPRINT_PACE_TICKS = 20 * 5;
    private static final double MIN_SPRINT_DISTANCE_SQR = 10.0D * 10.0D;
    private static final double ARRIVAL_DISTANCE_SQR = 3.0D * 3.0D;
    private static final Map<PlayerNpcEntity, FailedClimbFallbackRequest> FAILED_CLIMB_FALLBACK_REQUESTS = new WeakHashMap<>();
    private static final Map<PlayerNpcEntity, ExplorationClimbOwner> EXPLORATION_CLIMB_OWNERS = new WeakHashMap<>();
    private static final Set<PlayerNpcEntity> ACTIVE_SUPPLY_EXPLORERS = Collections.newSetFromMap(new WeakHashMap<>());

    private final PlayerNpcEntity playerNpc;
    private final PathNavigationAi pathNavigationAi;
    private final PathStuckFallbackAi pathStuckFallbackAi;
    private final ToolAi routeFoliageToolAi;
    private final BreakingBlockAi routeFoliageBreakingBlockAi;
    private final ClearBlockAi routeFoliageClearBlockAi;
    private final double speed;
    private final String detail;
    private final Predicate<ServerLevel> shouldExplore;
    private final Predicate<ServerLevel> shouldYieldToSubGoal;
    private final boolean allowUpwardEscapeRequest;
    private final boolean stopForHomeNow;
    private final boolean continueAcrossReachedTargets;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(EXPLORE_CAN_USE_INTERVAL_TICKS);
    private BlockPos targetPos;
    private Path plannedTargetPath;
    private int exploreTicks;
    private int repathTicks;
    private int searchRadiusIndex;
    private int retryWaitTicks;
    private int nextSearchTick;
    private int nextContinuePredicateCheckTick;
    private int nextEscapeRequestTick;
    private int subGoalProbeReadyTick = Integer.MIN_VALUE;
    private boolean initialRoutePending;
    private boolean continuePredicatesAllowed = true;
    private boolean waitingForRetry;
    private BlockPos forcedDropTargetPos;
    private BlockPos forcedDropStartPos;
    private int forcedDropTicks;
    private int movementPaceTicks;
    private boolean explorationSprinting;
    private boolean failedClimbFallbackWalk;
    private boolean localWaterEscape;
    private final Set<BlockPos> skippedRouteFoliage = new HashSet<>();
    private List<BlockPos> recentRouteNodes = List.of();
    private BlockPos recentRouteTarget;
    private Path recentRouteSourcePath;
    private int recentRouteSourceNextNode = -1;
    private BlockPos requestedRouteFoliageClear;
    private BlockPos buildingLogSurfaceScanOrigin;
    private int recentRouteUntilTick;
    private int routeFoliageClearAttempts;
    private int buildingLogSurfaceScanCursor = -1;

    public ExploreAroundGoal(
            PlayerNpcEntity playerNpc,
            double speed,
            String detail,
            Predicate<ServerLevel> shouldExplore,
            Predicate<ServerLevel> shouldYieldToSubGoal
    ) {
        this(playerNpc, speed, detail, shouldExplore, shouldYieldToSubGoal, true, true, false);
    }

    public ExploreAroundGoal(
            PlayerNpcEntity playerNpc,
            double speed,
            String detail,
            Predicate<ServerLevel> shouldExplore,
            Predicate<ServerLevel> shouldYieldToSubGoal,
            boolean allowUpwardEscapeRequest
    ) {
        this(playerNpc, speed, detail, shouldExplore, shouldYieldToSubGoal, allowUpwardEscapeRequest, true, false);
    }

    public ExploreAroundGoal(
            PlayerNpcEntity playerNpc,
            double speed,
            String detail,
            Predicate<ServerLevel> shouldExplore,
            Predicate<ServerLevel> shouldYieldToSubGoal,
            boolean allowUpwardEscapeRequest,
            boolean stopForHomeNow
    ) {
        this(
                playerNpc,
                speed,
                detail,
                shouldExplore,
                shouldYieldToSubGoal,
                allowUpwardEscapeRequest,
                stopForHomeNow,
                false
        );
    }

    public ExploreAroundGoal(
            PlayerNpcEntity playerNpc,
            double speed,
            String detail,
            Predicate<ServerLevel> shouldExplore,
            Predicate<ServerLevel> shouldYieldToSubGoal,
            boolean allowUpwardEscapeRequest,
            boolean stopForHomeNow,
            boolean continueAcrossReachedTargets
    ) {
        this.playerNpc = playerNpc;
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.pathStuckFallbackAi = new PathStuckFallbackAi(playerNpc);
        this.routeFoliageToolAi = new ToolAi(playerNpc);
        this.routeFoliageBreakingBlockAi = new BreakingBlockAi(playerNpc, this.routeFoliageToolAi);
        this.routeFoliageClearBlockAi = new ClearBlockAi(playerNpc, this.routeFoliageBreakingBlockAi);
        this.speed = speed;
        this.detail = detail;
        this.shouldExplore = shouldExplore;
        this.shouldYieldToSubGoal = shouldYieldToSubGoal;
        this.allowUpwardEscapeRequest = allowUpwardEscapeRequest;
        this.stopForHomeNow = stopForHomeNow;
        this.continueAcrossReachedTargets = continueAcrossReachedTargets;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean isSupplyExplorationActive(PlayerNpcEntity playerNpc) {
        return playerNpc != null && ACTIVE_SUPPLY_EXPLORERS.contains(playerNpc);
    }

    public static void requestSafeWalkAfterFailedClimb(PlayerNpcEntity playerNpc, BlockPos failedTarget) {
        if (playerNpc == null || failedTarget == null || playerNpc.level().isClientSide) {
            return;
        }
        ExplorationClimbOwner owner = EXPLORATION_CLIMB_OWNERS.get(playerNpc);
        if (owner == null
                || playerNpc.tickCount >= owner.expiresAtTick()
                || !owner.target().equals(failedTarget)) {
            EXPLORATION_CLIMB_OWNERS.remove(playerNpc);
            return;
        }
        EXPLORATION_CLIMB_OWNERS.remove(playerNpc);
        FAILED_CLIMB_FALLBACK_REQUESTS.put(playerNpc, new FailedClimbFallbackRequest(
                failedTarget.immutable(),
                owner.detail(),
                playerNpc.tickCount + FAILED_CLIMB_FALLBACK_REQUEST_TICKS,
                playerNpc.tickCount
        ));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null) {
            return false;
        }
        if (this.shouldStopForHomeNow(serverLevel)) {
            return false;
        }
        if (this.playerNpc.getUpwardEscapeTarget() != null) {
            return false;
        }
        if (this.tryUseFailedClimbFallback(serverLevel)) {
            return true;
        }
        if (this.playerNpc.getHoleEscapeCooldown() > 0) {
            return false;
        }

        if (this.playerNpc.tickCount < this.nextSearchTick) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (!this.shouldExplore.test(serverLevel)) {
            return false;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.nextSearchTick = this.playerNpc.tickCount + 1 + this.playerNpc.getRandom().nextInt(4);
            return false;
        }
        // Survival escape must not run the resource predicate/tree scan first. A wet NPC has no
        // usable land work route, and the local escape state below owns this admitted batch.
        if (!this.isInWater(serverLevel)) {
            if (this.shouldYieldToSubGoal.test(serverLevel)) {
                this.subGoalProbeReadyTick = Integer.MIN_VALUE;
                return false;
            }
            if (this.subGoalProbeReadyTick == Integer.MIN_VALUE) {
                // Separate the bounded local supply probe from exploration target selection.
                this.subGoalProbeReadyTick = this.playerNpc.tickCount + 1;
                this.nextSearchTick = this.playerNpc.tickCount + 1;
                this.canUseThrottle.retryIn(this.playerNpc, 1);
                return false;
            }
            if (this.playerNpc.tickCount < this.subGoalProbeReadyTick) {
                return false;
            }
            // GoalSelector may next evaluate several ticks after the deadline. Readiness is a
            // lower bound, never an exact-tick rendezvous that can be missed forever.
            this.subGoalProbeReadyTick = Integer.MIN_VALUE;
        } else {
            this.subGoalProbeReadyTick = Integer.MIN_VALUE;
        }

        this.clearForcedDrop();
        this.waitingForRetry = false;
        this.retryWaitTicks = 0;
        this.targetPos = this.findReachableSurfaceTarget(serverLevel);
        if (this.targetPos == null) {
            if (this.isInWater(serverLevel)) {
                this.scheduleWaterEscapeRetry();
                return false;
            }
            if (this.tryStartMiningLogColumnDrop(serverLevel)) {
                return true;
            }
            if (this.allowUpwardEscapeRequest && this.tryRequestShortUpwardEscape(serverLevel, null)) {
                this.waitingForRetry = false;
                this.retryWaitTicks = 0;
                this.nextSearchTick = this.playerNpc.tickCount + RADIUS_RETRY_COOLDOWN_TICKS;
                return false;
            }
            this.scheduleRetry(serverLevel);
            return false;
        }
        this.initialRoutePending = true;
        return this.targetPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.playerNpc.isAlive()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || !this.failedClimbFallbackWalk && this.playerNpc.getHoleEscapeCooldown() > 0
                || !(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (this.shouldStopForHomeNow(serverLevel)) {
            return false;
        }

        if (this.waitingForRetry) {
            return false;
        }

        if (this.localWaterEscape) {
            return this.targetPos != null
                    && this.exploreTicks < MAX_EXPLORE_TICKS
                    && this.isInWater(serverLevel);
        }

        if (this.forcedDropTargetPos != null) {
            return this.pathStuckFallbackAi.isRunning()
                    || (this.forcedDropTicks > 0
                    && this.forcedDropStartPos != null
                    && (this.playerNpc.blockPosition().equals(this.forcedDropStartPos)
                    || !this.playerNpc.onGround()));
        }

        if (this.playerNpc.tickCount >= this.nextContinuePredicateCheckTick) {
            if (PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
                this.nextContinuePredicateCheckTick = this.playerNpc.tickCount
                        + CONTINUE_PREDICATE_INTERVAL_TICKS
                        + this.playerNpc.getRandom().nextInt(5);
                this.continuePredicatesAllowed = this.shouldExplore.test(serverLevel)
                        && !this.shouldYieldToSubGoal.test(serverLevel);
            } else {
                this.nextContinuePredicateCheckTick = this.playerNpc.tickCount
                        + 1
                        + this.playerNpc.getRandom().nextInt(4);
            }
        }
        if (!this.continuePredicatesAllowed) {
            return false;
        }

        if (this.routeFoliageClearBlockAi.isRunning()) {
            return this.targetPos != null && this.exploreTicks < MAX_EXPLORE_TICKS;
        }

        return this.targetPos != null
                && this.exploreTicks < MAX_EXPLORE_TICKS
                && (this.continueAcrossReachedTargets
                || this.distanceToTargetSqr() > ARRIVAL_DISTANCE_SQR);
    }

    @Override
    public void start() {
        try {
            if (LOG_EXPLORATION_DETAIL.equals(this.detail) || STONE_EXPLORATION_DETAIL.equals(this.detail)) {
                ACTIVE_SUPPLY_EXPLORERS.add(this.playerNpc);
            }
            this.exploreTicks = 0;
            this.repathTicks = 0;
            this.routeFoliageClearBlockAi.stop();
            this.routeFoliageToolAi.restoreMainHand();
            this.skippedRouteFoliage.clear();
            this.recentRouteNodes = List.of();
            this.recentRouteTarget = null;
            this.recentRouteSourcePath = null;
            this.recentRouteSourceNextNode = -1;
            this.requestedRouteFoliageClear = null;
            this.recentRouteUntilTick = 0;
            this.routeFoliageClearAttempts = 0;
            this.stopExplorationSprint();
            this.continuePredicatesAllowed = true;
            this.nextContinuePredicateCheckTick = this.playerNpc.tickCount
                    + 1
                    + Math.floorMod(this.playerNpc.getUUID().hashCode(), CONTINUE_PREDICATE_INTERVAL_TICKS);
            this.playerNpc.setCurrentAiState("ai.player_npc.exploring");
            if (this.waitingForRetry) {
                this.playerNpc.setCurrentAiDetail(this.retryDetail());
                return;
            }
            if (this.forcedDropTargetPos != null && this.playerNpc.level() instanceof ServerLevel serverLevel) {
                this.forcedDropTicks = Math.max(this.forcedDropTicks, MINING_LOG_COLUMN_DROP_TICKS);
                this.tickMiningLogColumnDrop(serverLevel);
                return;
            }
            this.startRandomMovementPace();
            this.playerNpc.setCurrentAiDetail(this.failedClimbFallbackWalk
                    ? "walking after blocked exploration climb"
                    : this.detail);
            if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
                if (this.localWaterEscape) {
                    this.pathNavigationAi.tickLocalWaterEscape(serverLevel, this.speed);
                    return;
                }
                if (!this.initialRoutePending) {
                    this.moveToTarget(serverLevel);
                }
            }
        } finally {
            this.applyActiveNavigationBudget();
        }
    }

    @Override
    public void tick() {
        try {
            this.exploreTicks++;
            if (this.targetPos == null) {
                if (this.waitingForRetry) {
                    this.tickRetryWait();
                }
                return;
            }
            if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
                this.targetPos = null;
                return;
            }

            if (this.localWaterEscape) {
                if (this.pathNavigationAi.tickLocalWaterEscape(serverLevel, this.speed)) {
                    return;
                }
                this.targetPos = null;
                this.localWaterEscape = false;
                this.scheduleWaterEscapeRetry();
                this.playerNpc.setCurrentAiDetail("water escape unavailable; backing off");
                return;
            }

            if (this.initialRoutePending) {
                this.initialRoutePending = false;
                this.moveToTarget(serverLevel);
                this.repathTicks = REPATH_INTERVAL_TICKS;
                return;
            }

            if (this.pathNavigationAi.tickWaterTravel(serverLevel, this.targetPos, this.speed)) {
                return;
            }

            if (this.forcedDropTargetPos != null) {
                this.tickMiningLogColumnDrop(serverLevel);
                return;
            }

            if (this.routeFoliageClearBlockAi.isRunning()) {
                this.tickRouteFoliageClear(serverLevel);
                return;
            }

            if (this.continueAcrossReachedTargets && this.distanceToTargetSqr() <= ARRIVAL_DISTANCE_SQR) {
                this.continueFromReachedTarget(serverLevel);
                return;
            }

            this.rememberCurrentNavigationRoute();
            boolean shouldRepath = this.repathTicks-- <= 0;
            boolean navigationEnded = this.playerNpc.getNavigation().isDone()
                    || this.playerNpc.getNavigation().isStuck();
            if (shouldRepath
                    && navigationEnded
                    && this.tryStartRouteFoliageClear(serverLevel, true)) {
                this.repathTicks = REPATH_INTERVAL_TICKS;
                return;
            }
            if (shouldRepath
                    && navigationEnded
                    && (this.playerNpc.getNavigation().getPath() == null || this.playerNpc.getNavigation().isStuck())
                    && !this.isGenuinelyLocalDescent(this.playerNpc.blockPosition(), this.targetPos)) {
                this.playerNpc.setCurrentAiDetail("exploration route ended; choosing another target");
                this.targetPos = null;
                this.scheduleRetry(serverLevel);
                return;
            }

            this.tickRandomMovementPace();
            this.playerNpc.getLookControl().setLookAt(
                    this.targetPos.getX() + 0.5D,
                    this.targetPos.getY(),
                    this.targetPos.getZ() + 0.5D,
                    30.0F,
                    30.0F
            );
            if (shouldRepath && navigationEnded) {
                this.moveToTarget(serverLevel);
                this.repathTicks = REPATH_INTERVAL_TICKS;
            } else if (shouldRepath) {
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
        } finally {
            // createBoundedPath deliberately restores the navigation default. Exploration keeps a
            // smaller budget installed while it owns MOVE so vanilla delayed recomputation during
            // PathfinderMob.super.tick cannot expand into a full 48-block synchronous search.
            this.applyActiveNavigationBudget();
        }
    }

    @Override
    public void stop() {
        ACTIVE_SUPPLY_EXPLORERS.remove(this.playerNpc);
        this.targetPos = null;
        this.plannedTargetPath = null;
        this.initialRoutePending = false;
        this.exploreTicks = 0;
        this.repathTicks = 0;
        this.retryWaitTicks = 0;
        this.nextContinuePredicateCheckTick = 0;
        this.continuePredicatesAllowed = true;
        this.waitingForRetry = false;
        this.failedClimbFallbackWalk = false;
        this.localWaterEscape = false;
        this.routeFoliageClearBlockAi.stop();
        this.routeFoliageToolAi.restoreMainHand();
        this.skippedRouteFoliage.clear();
        this.recentRouteNodes = List.of();
        this.recentRouteTarget = null;
        this.recentRouteSourcePath = null;
        this.recentRouteSourceNextNode = -1;
        this.requestedRouteFoliageClear = null;
        this.recentRouteUntilTick = 0;
        this.routeFoliageClearAttempts = 0;
        this.stopExplorationSprint();
        this.clearForcedDrop();
        this.pathStuckFallbackAi.stop();
        this.pathNavigationAi.stopWaterTravel();
        // GoalSelector releases MOVE ownership without clearing PathNavigation's retained path.
        // Stop the exploration route before restoring the default node budget, otherwise a later
        // idle super.tick may synchronously recompute that stale long-range path at full cost.
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getNavigation().resetMaxVisitedNodesMultiplier();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private void applyActiveNavigationBudget() {
        this.playerNpc.getNavigation().setMaxVisitedNodesMultiplier(ACTIVE_EXPLORATION_PATH_NODE_MULTIPLIER);
    }

    private BlockPos findReachableSurfaceTarget(ServerLevel serverLevel) {
        this.plannedTargetPath = null;
        BlockPos center = this.playerNpc.blockPosition();
        boolean waterTravel = this.isInWater(serverLevel);
        this.localWaterEscape = false;
        if (waterTravel) {
            if (this.pathNavigationAi.canStartLocalWaterEscape(serverLevel)) {
                this.localWaterEscape = true;
                this.searchRadiusIndex = 0;
                return center.immutable();
            }
            return null;
        }
        int[] band = SEARCH_DISTANCE_BANDS[Math.max(0, Math.min(this.searchRadiusIndex, SEARCH_DISTANCE_BANDS.length - 1))];
        for (int attempt = 0; attempt < ATTEMPTS_PER_RADIUS; attempt++) {
            double angle = this.playerNpc.getRandom().nextDouble() * Math.PI * 2.0D;
            int distance = this.randomDistanceInBand(band[0], band[1]);
            int x = center.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * distance);
            if (!isColumnLoaded(serverLevel, x, z)) {
                continue;
            }
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (!this.isSafeExploreTarget(serverLevel, center, candidate)) {
                continue;
            }

            if (this.shouldUseBuildingSupplyLocalSurfaceFallback()) {
                Path path = PathNavigationAi.createBoundedPath(
                        this.playerNpc,
                        candidate,
                        EXPLORE_SELECTION_PATH_NODE_MULTIPLIER
                );
                if (!this.pathNavigationAi.isExactPathTo(candidate, path)) {
                    continue;
                }
                this.plannedTargetPath = path;
            }

            this.searchRadiusIndex = 0;
            this.nextSearchTick = 0;
            return candidate.immutable();
        }
        if (this.shouldUseBuildingSupplyLocalSurfaceFallback()) {
            return this.findBuildingLogLocalSurfaceTarget(serverLevel, center);
        }
        return null;
    }

    private boolean shouldUseBuildingSupplyLocalSurfaceFallback() {
        if (!this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || !this.playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)) {
            return false;
        }
        return (LOG_EXPLORATION_DETAIL.equals(this.detail)
                && this.playerNpc.shouldPrioritizeLogGathering())
                || (STONE_EXPLORATION_DETAIL.equals(this.detail)
                && this.playerNpc.shouldPrioritizeCobblestoneGathering());
    }

    private BlockPos findBuildingLogLocalSurfaceTarget(ServerLevel serverLevel, BlockPos center) {
        if (this.buildingLogSurfaceScanOrigin == null
                || this.buildingLogSurfaceScanOrigin.distSqr(center) > BUILDING_LOG_SCAN_RESET_DISTANCE_SQR) {
            this.buildingLogSurfaceScanOrigin = center.immutable();
            this.buildingLogSurfaceScanCursor = -1;
        }
        BlockPos scanCenter = this.buildingLogSurfaceScanOrigin;
        List<BlockPos> candidates = new ArrayList<>();
        int radius = BUILDING_LOG_LOCAL_SURFACE_RADIUS;
        int minRadiusSqr = BUILDING_LOG_LOCAL_SURFACE_MIN_RADIUS * BUILDING_LOG_LOCAL_SURFACE_MIN_RADIUS;
        int maxRadiusSqr = radius * radius;
        int diameter = radius * 2 + 1;
        int totalOffsets = diameter * diameter;
        int startIndex = this.buildingLogSurfaceScanCursor < 0
                ? this.playerNpc.getRandom().nextInt(totalOffsets)
                : this.buildingLogSurfaceScanCursor;
        int checkedColumns = 0;
        int attemptedOffsets = 0;
        for (;
             attemptedOffsets < totalOffsets && checkedColumns < BUILDING_LOG_LOCAL_SURFACE_COLUMN_CHECKS;
             attemptedOffsets++) {
            // The stride is coprime with the 37x37 search grid, so a bounded pass samples the
            // whole area instead of repeatedly favoring one edge or one narrow distance band.
            int index = (startIndex + attemptedOffsets * BUILDING_LOG_LOCAL_SURFACE_SCAN_STRIDE) % totalOffsets;
            int dx = index / diameter - radius;
            int dz = index % diameter - radius;
            int distanceSqr = dx * dx + dz * dz;
            if (distanceSqr < minRadiusSqr || distanceSqr > maxRadiusSqr) {
                continue;
            }

            int x = scanCenter.getX() + dx;
            int z = scanCenter.getZ() + dz;
            if (!isColumnLoaded(serverLevel, x, z)) {
                continue;
            }
            checkedColumns++;
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, candidate)
                    || !this.isSafeExploreTarget(serverLevel, center, candidate)) {
                continue;
            }
            candidates.add(candidate.immutable());
        }
        this.buildingLogSurfaceScanCursor = Math.floorMod(
                startIndex + attemptedOffsets * BUILDING_LOG_LOCAL_SURFACE_SCAN_STRIDE,
                totalOffsets
        );
        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(scanCenter))
                .thenComparingInt(BlockPos::getY));
        int preferredCount = Math.min(BUILDING_LOG_LOCAL_SURFACE_RANDOM_POOL, candidates.size());
        int start = preferredCount > 1 ? this.playerNpc.getRandom().nextInt(preferredCount) : 0;
        int pathChecks = Math.min(BUILDING_LOG_LOCAL_SURFACE_PATH_CHECKS, preferredCount);
        for (int offset = 0; offset < pathChecks; offset++) {
            BlockPos candidate = candidates.get((start + offset) % preferredCount);
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    candidate,
                    EXPLORE_SELECTION_PATH_NODE_MULTIPLIER
            );
            if (this.pathNavigationAi.isExactPathTo(candidate, path)) {
                this.plannedTargetPath = path;
                return candidate.immutable();
            }
        }
        return null;
    }

    private boolean isSafeExploreTarget(ServerLevel serverLevel, BlockPos center, BlockPos pos) {
        if (!this.canStandAt(serverLevel, pos) || !serverLevel.canSeeSky(pos.above())) {
            return false;
        }
        return this.hasLocalSurfaceRoom(serverLevel, pos);
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos);
    }

    private void moveToTarget(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return;
        }

        this.rememberCurrentNavigationRoute();

        Path plannedPath = this.plannedTargetPath;
        this.plannedTargetPath = null;
        boolean moved;
        if (plannedPath != null && this.pathNavigationAi.isValidPathTo(this.targetPos, plannedPath)) {
            moved = this.playerNpc.getNavigation().moveTo(plannedPath, Math.min(this.speed, 1.0D));
        } else if (this.failedClimbFallbackWalk) {
            moved = this.pathNavigationAi.moveToExact(
                    serverLevel,
                    this.targetPos,
                    Math.min(this.speed, 1.0D),
                    0,
                    EXPLORE_SELECTION_PATH_NODE_MULTIPLIER
            );
        } else {
            moved = this.pathNavigationAi.moveTo(
                    serverLevel,
                    this.targetPos,
                    this.speed,
                    this.isGenuinelyLocalDescent(this.playerNpc.blockPosition(), this.targetPos)
                            ? MAX_EXPLORE_SAFE_DROP_BLOCKS
                            : 0,
                    EXPLORE_SELECTION_PATH_NODE_MULTIPLIER
            );
        }
        if (moved) {
            this.rememberCurrentNavigationRoute();
            return;
        }

        if (this.tryStartRouteFoliageClear(serverLevel, true)) {
            return;
        }

        if (this.isInWater(serverLevel)) {
            this.playerNpc.getJumpControl().jump();
            this.targetPos = null;
            this.playerNpc.setCurrentAiDetail("water route stalled; choosing another exploration target");
            this.scheduleRetry(serverLevel);
            return;
        }

        if (this.failedClimbFallbackWalk) {
            this.targetPos = null;
            this.waitingForRetry = false;
            this.nextSearchTick = this.playerNpc.tickCount + RADIUS_RETRY_COOLDOWN_TICKS;
            return;
        }

        if (this.allowUpwardEscapeRequest && this.tryRequestShortUpwardEscape(serverLevel, this.targetPos)) {
            this.targetPos = null;
            return;
        }

        this.targetPos = null;
        this.scheduleRetry(serverLevel);
    }

    private boolean isInWater(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        return this.playerNpc.isInWaterOrBubble()
                || serverLevel.getFluidState(feet).is(FluidTags.WATER)
                || serverLevel.getFluidState(feet.above()).is(FluidTags.WATER);
    }

    private void scheduleWaterEscapeRetry() {
        this.nextSearchTick = this.playerNpc.tickCount
                + WATER_ESCAPE_RETRY_MIN_TICKS
                + this.playerNpc.getRandom().nextInt(WATER_ESCAPE_RETRY_RANDOM_TICKS + 1);
    }

    /**
     * Keep only the next few nodes of the path that belongs to this exploration target. If a
     * repath fails immediately after reaching one of those nodes, the saved corridor lets us
     * identify the physical foliage collision without guessing toward an unrelated block.
     */
    private void rememberCurrentNavigationRoute() {
        if (this.targetPos == null) {
            return;
        }

        Path path = this.playerNpc.getNavigation().getPath();
        if (path == null
                || path.getNodeCount() <= 0
                || path.getEndNode() == null
                || path.getEndNode().asBlockPos().distSqr(this.targetPos) > ARRIVAL_DISTANCE_SQR) {
            return;
        }

        int firstNode = Math.min(Math.max(0, path.getNextNodeIndex()), path.getNodeCount() - 1);
        if (path == this.recentRouteSourcePath
                && firstNode == this.recentRouteSourceNextNode
                && this.targetPos.equals(this.recentRouteTarget)) {
            this.recentRouteUntilTick = this.playerNpc.tickCount + RECENT_ROUTE_MEMORY_TICKS;
            return;
        }
        int endNode = Math.min(path.getNodeCount(), firstNode + ROUTE_FOLIAGE_NODE_LOOKAHEAD);
        List<BlockPos> nodes = new ArrayList<>(endNode - firstNode);
        for (int index = firstNode; index < endNode; index++) {
            nodes.add(path.getNode(index).asBlockPos().immutable());
        }
        if (nodes.isEmpty()) {
            return;
        }

        this.recentRouteNodes = List.copyOf(nodes);
        this.recentRouteTarget = this.targetPos.immutable();
        this.recentRouteSourcePath = path;
        this.recentRouteSourceNextNode = firstNode;
        this.recentRouteUntilTick = this.playerNpc.tickCount + RECENT_ROUTE_MEMORY_TICKS;
    }

    private boolean tryStartRouteFoliageClear(ServerLevel serverLevel, boolean routeFailureConfirmed) {
        if (!routeFailureConfirmed
                || this.routeFoliageClearBlockAi.isRunning()
                || this.targetPos == null
                || this.routeFoliageClearAttempts >= MAX_ROUTE_FOLIAGE_CLEAR_ATTEMPTS) {
            return false;
        }

        BlockPos blocker = this.findConfirmedRouteFoliageBlocker(serverLevel);
        if (blocker == null) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(blocker);
        if (!this.isSafeRouteFoliage(serverLevel, blocker, state)) {
            this.skippedRouteFoliage.add(blocker.immutable());
            return false;
        }

        int requiredTicks = BreakingBlockAi.requiredBreakTicks(serverLevel, blocker, state, this.playerNpc);
        boolean started = this.routeFoliageClearBlockAi.start(
                serverLevel,
                blocker,
                ExploreAroundGoal::isFoliageState,
                this.detail + " clearing foliage",
                requiredTicks,
                ROUTE_FOLIAGE_CLEAR_DISTANCE_SQR,
                true
        );
        if (!started) {
            this.skippedRouteFoliage.add(blocker.immutable());
            return false;
        }

        this.routeFoliageClearAttempts++;
        this.requestedRouteFoliageClear = blocker.immutable();
        this.stopExplorationSprint();
        this.playerNpc.setCurrentAiDetail(this.routeFoliageClearBlockAi.detail());
        return true;
    }

    private void tickRouteFoliageClear(ServerLevel serverLevel) {
        BlockPos activeTarget = this.routeFoliageClearBlockAi.targetPos();
        if (activeTarget == null
                || !this.isSafeRouteFoliage(serverLevel, activeTarget, serverLevel.getBlockState(activeTarget))) {
            if (activeTarget != null) {
                this.skippedRouteFoliage.add(activeTarget.immutable());
            }
            this.finishRouteFoliageClear(serverLevel, false);
            return;
        }

        ClearBlockAi.TickResult result = this.routeFoliageClearBlockAi.tick(serverLevel);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            this.playerNpc.setCurrentAiDetail(this.routeFoliageClearBlockAi.detail());
            return;
        }

        this.finishRouteFoliageClear(serverLevel, result == ClearBlockAi.TickResult.DONE);
    }

    private void finishRouteFoliageClear(ServerLevel serverLevel, boolean cleared) {
        if (!cleared && this.requestedRouteFoliageClear != null) {
            this.skippedRouteFoliage.add(this.requestedRouteFoliageClear.immutable());
        }
        this.routeFoliageClearBlockAi.stop();
        this.routeFoliageToolAi.restoreMainHand();
        this.requestedRouteFoliageClear = null;
        this.playerNpc.getNavigation().stop();
        if (this.targetPos == null) {
            return;
        }

        this.playerNpc.setCurrentAiDetail(this.failedClimbFallbackWalk
                ? "walking after blocked exploration climb"
                : this.detail);
        this.moveToTarget(serverLevel);
        this.repathTicks = REPATH_INTERVAL_TICKS;
    }

    private BlockPos findConfirmedRouteFoliageBlocker(ServerLevel serverLevel) {
        if (this.targetPos == null
                || this.recentRouteTarget == null
                || !this.recentRouteTarget.equals(this.targetPos)
                || this.playerNpc.tickCount > this.recentRouteUntilTick
                || this.recentRouteNodes.isEmpty()) {
            return null;
        }

        Set<BlockPos> routeSupports = new HashSet<>();
        routeSupports.add(this.playerNpc.blockPosition().below().immutable());
        for (BlockPos node : this.recentRouteNodes) {
            routeSupports.add(node.below().immutable());
        }

        AABB segmentStart = this.playerNpc.getBoundingBox();
        for (BlockPos node : this.recentRouteNodes) {
            AABB segmentEnd = this.playerNpc.getBoundingBox().move(
                    node.getX() + 0.5D - this.playerNpc.getX(),
                    node.getY() - this.playerNpc.getY(),
                    node.getZ() + 0.5D - this.playerNpc.getZ()
            );
            BlockPos blocker = this.findSweptRouteFoliageCollision(serverLevel, segmentStart, segmentEnd, routeSupports);
            if (blocker != null) {
                return blocker;
            }
            segmentStart = segmentEnd;
        }
        return null;
    }

    private BlockPos findSweptRouteFoliageCollision(
            ServerLevel serverLevel,
            AABB segmentStart,
            AABB segmentEnd,
            Set<BlockPos> routeSupports
    ) {
        AABB sweptBody = new AABB(
                Math.min(segmentStart.minX, segmentEnd.minX) - 0.04D,
                Math.min(segmentStart.minY, segmentEnd.minY) + 0.02D,
                Math.min(segmentStart.minZ, segmentEnd.minZ) - 0.04D,
                Math.max(segmentStart.maxX, segmentEnd.maxX) + 0.04D,
                Math.max(segmentStart.maxY, segmentEnd.maxY) + 0.04D,
                Math.max(segmentStart.maxZ, segmentEnd.maxZ) + 0.04D
        );
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos mutable : BlockPos.betweenClosed(
                Mth.floor(sweptBody.minX),
                Mth.floor(sweptBody.minY),
                Mth.floor(sweptBody.minZ),
                Mth.floor(sweptBody.maxX),
                Mth.floor(sweptBody.maxY),
                Mth.floor(sweptBody.maxZ))) {
            BlockPos pos = mutable.immutable();
            if (routeSupports.contains(pos) || this.skippedRouteFoliage.contains(pos)) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(pos);
            if (!this.isSafeRouteFoliage(serverLevel, pos, state)
                    || state.getCollisionShape(serverLevel, pos).toAabbs().stream()
                    .map(box -> box.move(pos))
                    .noneMatch(box -> box.intersects(sweptBody))) {
                continue;
            }
            double distance = this.playerNpc.distanceToSqr(
                    pos.getX() + 0.5D,
                    pos.getY() + 0.5D,
                    pos.getZ() + 0.5D
            );
            if (distance <= ROUTE_FOLIAGE_CLEAR_DISTANCE_SQR && distance < bestDistance) {
                bestDistance = distance;
                best = pos;
            }
        }
        return best == null ? null : best.immutable();
    }

    private boolean isSafeRouteFoliage(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return pos != null
                && isFoliageState(state)
                && PlayerNpcHomeUtil.getHome(this.playerNpc)
                .map(home -> !PlayerNpcHomeUtil.isInside(home, pos))
                .orElse(true)
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                && !this.playerNpc.isTemporaryPillarSupport(pos)
                && !CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)
                && ClearBlockAi.isBreakablePathObstruction(serverLevel, pos, state, true);
    }

    private static boolean isFoliageState(BlockState state) {
        return state != null
                && (state.is(BlockTags.LEAVES)
                || state.is(Blocks.VINE)
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(Blocks.WEEPING_VINES)
                || state.is(Blocks.WEEPING_VINES_PLANT)
                || state.is(Blocks.TWISTING_VINES)
                || state.is(Blocks.TWISTING_VINES_PLANT));
    }

    private boolean tryUseFailedClimbFallback(ServerLevel serverLevel) {
        FailedClimbFallbackRequest request = FAILED_CLIMB_FALLBACK_REQUESTS.get(this.playerNpc);
        if (request == null) {
            return false;
        }
        if (this.playerNpc.tickCount >= request.expiresAtTick()) {
            FAILED_CLIMB_FALLBACK_REQUESTS.remove(this.playerNpc);
            return false;
        }
        if (this.playerNpc.tickCount < request.nextAttemptTick()
                || !this.detail.equals(request.ownerDetail())) {
            return false;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            FAILED_CLIMB_FALLBACK_REQUESTS.put(this.playerNpc, new FailedClimbFallbackRequest(
                    request.failedTarget(),
                    request.ownerDetail(),
                    request.expiresAtTick(),
                    this.playerNpc.tickCount + 1 + this.playerNpc.getRandom().nextInt(4)
            ));
            return false;
        }
        if (!this.shouldExplore.test(serverLevel) || this.shouldYieldToSubGoal.test(serverLevel)) {
            FAILED_CLIMB_FALLBACK_REQUESTS.put(this.playerNpc, new FailedClimbFallbackRequest(
                    request.failedTarget(),
                    request.ownerDetail(),
                    request.expiresAtTick(),
                    this.playerNpc.tickCount + FAILED_CLIMB_FALLBACK_RETRY_TICKS
            ));
            return false;
        }

        BlockPos fallback = this.findSafeFailedClimbWalkTarget(serverLevel, request.failedTarget());
        if (fallback == null) {
            FAILED_CLIMB_FALLBACK_REQUESTS.put(this.playerNpc, new FailedClimbFallbackRequest(
                    request.failedTarget(),
                    request.ownerDetail(),
                    request.expiresAtTick(),
                    this.playerNpc.tickCount + FAILED_CLIMB_FALLBACK_RETRY_TICKS
            ));
            return false;
        }

        FAILED_CLIMB_FALLBACK_REQUESTS.remove(this.playerNpc);
        this.clearForcedDrop();
        this.waitingForRetry = false;
        this.retryWaitTicks = 0;
        this.failedClimbFallbackWalk = true;
        this.targetPos = fallback;
        this.nextSearchTick = this.playerNpc.tickCount + RADIUS_RETRY_COOLDOWN_TICKS;
        return true;
    }

    private BlockPos findSafeFailedClimbWalkTarget(ServerLevel serverLevel, BlockPos failedTarget) {
        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        int minDistanceSqr = FAILED_CLIMB_FALLBACK_MIN_DISTANCE * FAILED_CLIMB_FALLBACK_MIN_DISTANCE;
        int maxDistanceSqr = FAILED_CLIMB_FALLBACK_RADIUS * FAILED_CLIMB_FALLBACK_RADIUS;
        for (int dx = -FAILED_CLIMB_FALLBACK_RADIUS; dx <= FAILED_CLIMB_FALLBACK_RADIUS; dx++) {
            for (int dz = -FAILED_CLIMB_FALLBACK_RADIUS; dz <= FAILED_CLIMB_FALLBACK_RADIUS; dz++) {
                int horizontalDistanceSqr = dx * dx + dz * dz;
                if (horizontalDistanceSqr < minDistanceSqr || horizontalDistanceSqr > maxDistanceSqr) {
                    continue;
                }
                for (int dy = -FAILED_CLIMB_FALLBACK_VERTICAL_RANGE; dy <= FAILED_CLIMB_FALLBACK_VERTICAL_RANGE; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz).immutable();
                    if (blockDistanceSqr(candidate, failedTarget) <= minDistanceSqr
                            || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, candidate)
                            || FarmAi.isProtectedFarmBlock(this.playerNpc, candidate)
                            || !this.canStandAt(serverLevel, candidate)) {
                        continue;
                    }
                    candidates.add(candidate);
                }
            }
        }

        int checks = 0;
        while (!candidates.isEmpty() && checks++ < FAILED_CLIMB_FALLBACK_PATH_CHECKS) {
            BlockPos candidate = candidates.remove(this.playerNpc.getRandom().nextInt(candidates.size()));
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    candidate,
                    EXPLORE_SELECTION_PATH_NODE_MULTIPLIER
            );
            if (this.pathNavigationAi.isExactPathTo(candidate, path)) {
                this.plannedTargetPath = path;
                return candidate.immutable();
            }
        }
        return null;
    }

    private void startRandomMovementPace() {
        boolean sprint = this.canSprintTowardTarget()
                && this.playerNpc.getRandom().nextFloat() < INITIAL_SPRINT_CHANCE;
        this.setExplorationSprinting(sprint);
        this.movementPaceTicks = sprint ? this.nextSprintPaceTicks() : this.nextWalkPaceTicks();
    }

    private void tickRandomMovementPace() {
        if (!this.canSprintTowardTarget()) {
            if (this.explorationSprinting) {
                this.setExplorationSprinting(false);
                this.movementPaceTicks = this.nextWalkPaceTicks();
            } else if (this.movementPaceTicks > 0) {
                this.movementPaceTicks--;
            }
            return;
        }

        this.setExplorationSprinting(this.explorationSprinting);
        if (this.movementPaceTicks-- > 0) {
            return;
        }

        if (this.explorationSprinting) {
            this.setExplorationSprinting(false);
            this.movementPaceTicks = this.nextWalkPaceTicks();
            return;
        }

        boolean sprint = this.playerNpc.getRandom().nextFloat() < WALK_TO_SPRINT_CHANCE;
        this.setExplorationSprinting(sprint);
        this.movementPaceTicks = sprint ? this.nextSprintPaceTicks() : this.nextWalkPaceTicks();
    }

    private boolean canSprintTowardTarget() {
        return this.targetPos != null
                && this.forcedDropTargetPos == null
                && !this.playerNpc.isShiftKeyDown()
                && !this.playerNpc.isCrouching()
                && !this.playerNpc.isInWaterOrBubble()
                && !this.playerNpc.isInLava()
                && this.distanceToTargetSqr() >= MIN_SPRINT_DISTANCE_SQR;
    }

    private void setExplorationSprinting(boolean sprinting) {
        this.explorationSprinting = sprinting;
        if (this.playerNpc.isSprinting() != sprinting) {
            this.playerNpc.setSprinting(sprinting);
        }
    }

    private void continueFromReachedTarget(ServerLevel serverLevel) {
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            this.playerNpc.getNavigation().stop();
            this.playerNpc.setCurrentAiDetail("waiting to choose next exploration target");
            return;
        }
        if (!this.shouldExplore.test(serverLevel) || this.shouldYieldToSubGoal.test(serverLevel)) {
            this.continuePredicatesAllowed = false;
            this.targetPos = null;
            this.playerNpc.getNavigation().stop();
            return;
        }

        BlockPos nextTarget = this.findReachableSurfaceTarget(serverLevel);
        if (nextTarget == null) {
            this.targetPos = null;
            this.scheduleRetry(serverLevel);
            return;
        }

        this.targetPos = nextTarget;
        this.exploreTicks = 0;
        this.repathTicks = 0;
        this.skippedRouteFoliage.clear();
        this.recentRouteNodes = List.of();
        this.recentRouteTarget = null;
        this.recentRouteSourcePath = null;
        this.recentRouteSourceNextNode = -1;
        this.requestedRouteFoliageClear = null;
        this.recentRouteUntilTick = 0;
        this.routeFoliageClearAttempts = 0;
        this.startRandomMovementPace();
        this.playerNpc.setCurrentAiDetail(this.detail);
        this.moveToTarget(serverLevel);
    }

    private void stopExplorationSprint() {
        this.setExplorationSprinting(false);
        this.movementPaceTicks = 0;
    }

    private int nextWalkPaceTicks() {
        return this.randomTicksBetween(MIN_WALK_PACE_TICKS, MAX_WALK_PACE_TICKS);
    }

    private int nextSprintPaceTicks() {
        return this.randomTicksBetween(MIN_SPRINT_PACE_TICKS, MAX_SPRINT_PACE_TICKS);
    }

    private int randomTicksBetween(int minInclusive, int maxInclusive) {
        return minInclusive + this.playerNpc.getRandom().nextInt(maxInclusive - minInclusive + 1);
    }

    private void scheduleRetry(ServerLevel serverLevel) {
        boolean completedFullSearch = this.searchRadiusIndex >= SEARCH_DISTANCE_BANDS.length - 1;
        if (completedFullSearch && this.allowUpwardEscapeRequest) {
            this.tryRequestShortUpwardEscape(serverLevel, null);
        }
        if (completedFullSearch && this.requestReturnHomeAfterFailedExploration()) {
            this.searchRadiusIndex = 0;
            this.retryWaitTicks = 0;
            this.nextSearchTick = this.playerNpc.tickCount + RETURN_HOME_RETRY_COOLDOWN_TICKS;
            this.waitingForRetry = false;
            this.playerNpc.getNavigation().stop();
            return;
        }

        this.searchRadiusIndex = completedFullSearch ? 0 : this.searchRadiusIndex + 1;
        this.retryWaitTicks = RADIUS_RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(RADIUS_RETRY_COOLDOWN_TICKS + 1);
        this.nextSearchTick = this.playerNpc.tickCount + this.retryWaitTicks;
        this.waitingForRetry = true;
        this.playerNpc.getNavigation().stop();
    }

    private boolean isGenuinelyLocalDescent(BlockPos from, BlockPos target) {
        if (from == null || target == null || target.getY() >= from.getY()) {
            return false;
        }
        int dx = target.getX() - from.getX();
        int dz = target.getZ() - from.getZ();
        return dx * dx + dz * dz <= 2 * 2;
    }

    private boolean tryStartMiningLogColumnDrop(ServerLevel serverLevel) {
        if (!this.isMiningOnlyLogExploration()) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (!this.isOnNarrowColumnTop(serverLevel, feet)) {
            return false;
        }

        BlockPos dropTarget = this.findMiningLogColumnDropTarget(serverLevel, feet);
        if (dropTarget == null) {
            return false;
        }

        this.targetPos = dropTarget;
        this.forcedDropTargetPos = dropTarget;
        this.forcedDropStartPos = feet.immutable();
        this.forcedDropTicks = MINING_LOG_COLUMN_DROP_TICKS;
        this.waitingForRetry = false;
        this.retryWaitTicks = 0;
        this.nextSearchTick = this.playerNpc.tickCount + RADIUS_RETRY_COOLDOWN_TICKS;
        if (!this.startMiningLogColumnPathFallback(serverLevel)) {
            this.targetPos = null;
            this.clearForcedDrop();
            return false;
        }
        return true;
    }

    private boolean isMiningOnlyLogExploration() {
        return LOG_EXPLORATION_DETAIL.equals(this.detail)
                && this.playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)
                && !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && this.playerNpc.shouldPrioritizeLogGathering();
    }

    private boolean isOnNarrowColumnTop(ServerLevel serverLevel, BlockPos feet) {
        if (!this.playerNpc.onGround()) {
            return false;
        }

        BlockPos floor = feet.below();
        if (!serverLevel.isInWorldBounds(floor)
                || !serverLevel.hasChunkAt(floor)
                || serverLevel.getBlockState(floor).getCollisionShape(serverLevel, floor).isEmpty()) {
            return false;
        }

        int solidSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = floor.relative(direction);
            if (serverLevel.hasChunkAt(side)
                    && serverLevel.getBlockState(side).isSolidRender(serverLevel, side)) {
                solidSides++;
            }
        }
        return solidSides <= MINING_LOG_COLUMN_DROP_MAX_SOLID_SIDE_SUPPORTS;
    }

    private BlockPos findMiningLogColumnDropTarget(ServerLevel serverLevel, BlockPos feet) {
        List<BlockPos> candidates = new ArrayList<>();
        List<BlockPos> relaxedCandidates = new ArrayList<>();
        int radiusSqr = MINING_LOG_COLUMN_DROP_RADIUS * MINING_LOG_COLUMN_DROP_RADIUS;
        for (int dx = -MINING_LOG_COLUMN_DROP_RADIUS; dx <= MINING_LOG_COLUMN_DROP_RADIUS; dx++) {
            for (int dz = -MINING_LOG_COLUMN_DROP_RADIUS; dz <= MINING_LOG_COLUMN_DROP_RADIUS; dz++) {
                if (dx == 0 && dz == 0 || dx * dx + dz * dz > radiusSqr) {
                    continue;
                }

                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                if (!isColumnLoaded(serverLevel, x, z)) {
                    continue;
                }
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int fall = feet.getY() - y;
                if (fall <= 0 || fall > MINING_LOG_COLUMN_DROP_MAX_FALL) {
                    continue;
                }

                BlockPos candidate = new BlockPos(x, y, z);
                if (!this.canStandAt(serverLevel, candidate)
                        || PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, candidate)) {
                    continue;
                }

                if (serverLevel.canSeeSky(candidate.above()) && this.hasLocalSurfaceRoom(serverLevel, candidate)) {
                    candidates.add(candidate.immutable());
                } else {
                    relaxedCandidates.add(candidate.immutable());
                }
            }
        }

        BlockPos selected = this.selectMiningLogColumnDropTarget(candidates, feet);
        if (selected != null) {
            return selected;
        }
        return this.selectMiningLogColumnDropTarget(relaxedCandidates, feet);
    }

    private BlockPos selectMiningLogColumnDropTarget(List<BlockPos> candidates, BlockPos feet) {
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> horizontalDistanceSqr(feet, pos))
                .thenComparingInt(pos -> Math.abs(feet.getY() - pos.getY())));
        return candidates.isEmpty() ? null : candidates.get(0).immutable();
    }

    private void tickMiningLogColumnDrop(ServerLevel serverLevel) {
        if (this.forcedDropStartPos == null || this.forcedDropTargetPos == null) {
            this.targetPos = null;
            this.clearForcedDrop();
            return;
        }

        this.forcedDropTicks--;
        if (this.pathStuckFallbackAi.tick(serverLevel, this.detail)) {
            this.playerNpc.setCurrentAiDetail(this.pathStuckFallbackAi.detail(this.detail));
            return;
        }

        if (!this.playerNpc.blockPosition().equals(this.forcedDropStartPos) && this.playerNpc.onGround()) {
            this.targetPos = null;
            this.clearForcedDrop();
            return;
        }

        if (this.forcedDropTicks <= 0 || !this.startMiningLogColumnPathFallback(serverLevel)) {
            this.targetPos = null;
            this.clearForcedDrop();
        }
    }

    private boolean startMiningLogColumnPathFallback(ServerLevel serverLevel) {
        if (this.forcedDropTargetPos == null) {
            return false;
        }
        boolean started = this.pathStuckFallbackAi.start(
                serverLevel,
                this.forcedDropTargetPos,
                this.detail,
                pos -> PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
        );
        this.playerNpc.setCurrentAiDetail(this.pathStuckFallbackAi.detail(this.detail));
        return started;
    }

    private void clearForcedDrop() {
        this.forcedDropTargetPos = null;
        this.forcedDropStartPos = null;
        this.forcedDropTicks = 0;
        this.pathStuckFallbackAi.stop();
    }

    private boolean requestReturnHomeAfterFailedExploration() {
        return this.playerNpc.requestReturnHomeAfterExplorationFailure(
                this.detail + " failed all distance bands; returning home",
                RETURN_HOME_REQUEST_TICKS
        );
    }

    private void tickRetryWait() {
        this.retryWaitTicks--;
        if (this.retryWaitTicks <= 0) {
            this.waitingForRetry = false;
            return;
        }

        if (this.retryWaitTicks % 20 == 0) {
            float yaw = this.playerNpc.getYRot() + 45.0F + this.playerNpc.getRandom().nextFloat() * 90.0F;
            double x = this.playerNpc.getX() + Math.cos(Math.toRadians(yaw)) * 4.0D;
            double z = this.playerNpc.getZ() + Math.sin(Math.toRadians(yaw)) * 4.0D;
            this.playerNpc.getLookControl().setLookAt(x, this.playerNpc.getEyeY(), z, 20.0F, 20.0F);
        }
        this.playerNpc.setCurrentAiDetail(this.retryDetail());
    }

    private String retryDetail() {
        int[] nextBand = SEARCH_DISTANCE_BANDS[Math.max(0, Math.min(this.searchRadiusIndex, SEARCH_DISTANCE_BANDS.length - 1))];
        int seconds = Math.max(1, (this.retryWaitTicks + 19) / 20);
        return this.detail + " retry range=" + nextBand[0] + "-" + nextBand[1] + " in " + seconds + "s";
    }

    private boolean tryRequestShortUpwardEscape(ServerLevel serverLevel, BlockPos routeHint) {
        if (this.isInWater(serverLevel)) {
            return false;
        }
        if (this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0) {
            return true;
        }
        if (this.playerNpc.tickCount < this.nextEscapeRequestTick) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, feet)
                || (routeHint != null && PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, routeHint))) {
            return false;
        }
        BlockPos escapeTarget = this.findShortSurfaceEscapeTarget(serverLevel, feet, routeHint);
        if (escapeTarget == null) {
            return false;
        }

        this.playerNpc.getNavigation().stop();
        EXPLORATION_CLIMB_OWNERS.put(this.playerNpc, new ExplorationClimbOwner(
                escapeTarget.immutable(),
                this.detail,
                this.playerNpc.tickCount + EXPLORATION_CLIMB_OWNER_TICKS
        ));
        this.playerNpc.requestExplorationUpwardEscapeTo(escapeTarget, UPWARD_ESCAPE_REQUEST_TICKS, MAX_EXPLORE_PILLAR_BLOCKS);
        this.nextEscapeRequestTick = this.playerNpc.tickCount + ESCAPE_REQUEST_COOLDOWN_TICKS;
        this.playerNpc.setCurrentAiDetail("exploration climb request @ "
                + posText(escapeTarget)
                + " max="
                + MAX_EXPLORE_PILLAR_BLOCKS);
        return true;
    }

    private BlockPos findShortSurfaceEscapeTarget(ServerLevel serverLevel, BlockPos feet, BlockPos routeHint) {
        List<BlockPos> candidates = new ArrayList<>();
        List<BlockPos> relaxedCandidates = new ArrayList<>();
        for (int dx = -LOCAL_SURFACE_ESCAPE_RADIUS; dx <= LOCAL_SURFACE_ESCAPE_RADIUS; dx++) {
            for (int dz = -LOCAL_SURFACE_ESCAPE_RADIUS; dz <= LOCAL_SURFACE_ESCAPE_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                if (!isColumnLoaded(serverLevel, x, z)) {
                    continue;
                }
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int climb = y - feet.getY();
                if (climb <= 0 || climb > MAX_EXPLORE_PILLAR_BLOCKS) {
                    continue;
                }

                BlockPos candidate = new BlockPos(x, y, z);
                if (!this.isTerrainSupportedStand(serverLevel, candidate)) {
                    continue;
                }

                if (serverLevel.canSeeSky(candidate.above()) && this.hasLocalSurfaceRoom(serverLevel, candidate)) {
                    candidates.add(candidate.immutable());
                } else if (this.hasLocalTerrainWalkOff(serverLevel, candidate)) {
                    relaxedCandidates.add(candidate.immutable());
                }
            }
        }

        BlockPos strictTarget = this.selectBlockedEscapeTarget(candidates, feet, routeHint);
        if (strictTarget != null) {
            return strictTarget;
        }
        return this.selectBlockedEscapeTarget(relaxedCandidates, feet, routeHint);
    }

    private BlockPos selectBlockedEscapeTarget(List<BlockPos> candidates, BlockPos feet, BlockPos routeHint) {
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> routeHint == null ? 0.0D : horizontalDistanceSqr(pos, routeHint))
                .thenComparingDouble(pos -> blockDistanceSqr(feet, pos)));
        int pathChecks = 0;
        for (BlockPos candidate : candidates) {
            if (pathChecks++ >= MAX_LOCAL_ESCAPE_PATH_CHECKS) {
                return candidate;
            }
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    candidate,
                    EXPLORE_SELECTION_PATH_NODE_MULTIPLIER
            );
            if (!this.pathNavigationAi.isValidPathTo(candidate, path)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasLocalSurfaceRoom(ServerLevel serverLevel, BlockPos pos) {
        if (!this.isTerrainSupportedStand(serverLevel, pos)) {
            return false;
        }

        int neighbors = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos adjacent = pos.relative(direction).offset(0, dy, 0);
                if (this.isTerrainSupportedStand(serverLevel, adjacent)
                        && serverLevel.canSeeSky(adjacent.above())) {
                    neighbors++;
                    break;
                }
            }
        }
        return neighbors >= MIN_LOCAL_SURFACE_NEIGHBORS;
    }

    private boolean hasLocalTerrainWalkOff(ServerLevel serverLevel, BlockPos pos) {
        if (!this.isTerrainSupportedStand(serverLevel, pos)) {
            return false;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                if (this.isTerrainSupportedStand(serverLevel, pos.relative(direction).offset(0, dy, 0))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isTerrainSupportedStand(ServerLevel serverLevel, BlockPos pos) {
        if (!this.canStandAt(serverLevel, pos)) {
            return false;
        }

        BlockState support = serverLevel.getBlockState(pos.below());
        return !support.is(BlockTags.LOGS) && !support.is(BlockTags.LEAVES);
    }

    private double distanceToTargetSqr() {
        return this.targetPos == null
                ? Double.MAX_VALUE
                : this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY(), this.targetPos.getZ() + 0.5D);
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

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static boolean isColumnLoaded(ServerLevel serverLevel, int blockX, int blockZ) {
        return serverLevel.hasChunk(blockX >> 4, blockZ >> 4);
    }

    private int randomDistanceInBand(int minInclusive, int maxInclusive) {
        int min = Math.max(0, Math.min(minInclusive, maxInclusive));
        int max = Math.max(min, Math.max(minInclusive, maxInclusive));
        return min + this.playerNpc.getRandom().nextInt(max - min + 1);
    }

    private boolean shouldStopForHomeNow(ServerLevel serverLevel) {
        return this.stopForHomeNow
                && PlayerNpcHomeUtil.getHome(this.playerNpc).isPresent()
                && (this.playerNpc.hasExplorationReturnHomeRequest()
                || serverLevel.isNight()
                || serverLevel.isThundering());
    }

    private record FailedClimbFallbackRequest(
            BlockPos failedTarget,
            String ownerDetail,
            int expiresAtTick,
            int nextAttemptTick
    ) {
    }

    private record ExplorationClimbOwner(BlockPos target, String detail, int expiresAtTick) {
    }
}
