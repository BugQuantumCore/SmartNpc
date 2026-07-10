package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ResourceAi;
import com.pla.smart_npc.entity.ai.StoneAi;
import com.pla.smart_npc.entity.ai.StoneAi.StoneCluster;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

public class GatherStoneGoal extends Goal {
    private static final int SEARCH_RADIUS = 24;
    private static final int MAX_GATHER_TICKS = 20 * 35;
    private static final int REQUIRED_BREAK_TICKS = 70;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final double BREAK_DISTANCE_SQR = 4.0D * 4.0D;
    private static final int MAX_STONE_PATH_CHECKS = 48;
    private static final int MAX_STAND_SAFE_DROP_BLOCKS = 3;
    private static final int CLEAR_OBSTRUCTION_TICKS = 24;
    private static final double CLEAR_OBSTRUCTION_DISTANCE_SQR = 5.0D * 5.0D;
    private static final double STAND_REACHED_HORIZONTAL_SQR = 0.9D * 0.9D;
    private static final int MAX_STAND_ROUTE_ATTEMPTS_BEFORE_CLEAR = 2;
    private static final int PROTECTED_BASE_SUPPORT_DEPTH = 3;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private BlockPos targetPos;
    private BlockPos standPos;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PathNavigationAi pathNavigationAi;
    private final Queue<BlockPos> stoneQueue = new ArrayDeque<>();
    private int gatherTicks;
    private int repathTicks;
    private int standRouteAttempts;

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

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getGatherCooldown() > 0
                || this.shouldStayHomeForWeather(serverLevel)
                || this.playerNpc.shouldPrioritizeLogGathering()
                || !this.playerNpc.shouldPrioritizeCobblestoneGathering()
                || !hasPreparedBaseForStone(this.playerNpc, serverLevel)
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
                && !this.playerNpc.shouldPrioritizeLogGathering()
                && this.playerNpc.shouldPrioritizeCobblestoneGathering()
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && !this.shouldStayHomeForWeather(serverLevel)
                && hasPreparedBaseForStone(this.playerNpc, serverLevel);
    }

    @Override
    public void start() {
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.standRouteAttempts = 0;
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
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                boolean navigationEnded = this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck();
                if (navigationEnded
                        && this.standRouteAttempts >= MAX_STAND_ROUTE_ATTEMPTS_BEFORE_CLEAR
                        && this.startClearingRoute(serverLevel)) {
                    this.standRouteAttempts = 0;
                    this.repathTicks = REPATH_INTERVAL_TICKS;
                    this.updateDetail();
                    return;
                }

                boolean moved = this.moveToStandPos(serverLevel);
                if (!moved) {
                    if (this.startClearingRoute(serverLevel)) {
                        this.standRouteAttempts = 0;
                        this.repathTicks = REPATH_INTERVAL_TICKS;
                        this.updateDetail();
                        return;
                    }
                    this.playerNpc.clearBlockBreakProgress(this.targetPos);
                    if (!this.selectTarget(serverLevel)) {
                        this.targetPos = null;
                    }
                } else if (moved && navigationEnded) {
                    this.standRouteAttempts++;
                    if (this.standRouteAttempts >= MAX_STAND_ROUTE_ATTEMPTS_BEFORE_CLEAR
                            && this.startClearingRoute(serverLevel)) {
                        this.standRouteAttempts = 0;
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
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                boolean moved = this.moveToStandPos(serverLevel);
                if (!moved) {
                    if (this.startClearingRoute(serverLevel)) {
                        this.standRouteAttempts = 0;
                        this.repathTicks = REPATH_INTERVAL_TICKS;
                        this.updateDetail();
                        return;
                    }
                    if (!this.selectTarget(serverLevel)) {
                        this.targetPos = null;
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
        while (!this.stoneQueue.isEmpty()) {
            BlockPos candidate = this.stoneQueue.poll();
            Optional<BlockPos> stand = findStandPos(this.playerNpc, serverLevel, candidate, this.pathNavigationAi);
            if (stand.isEmpty() || !isActionableStoneTarget(this.playerNpc, serverLevel, candidate, this.pathNavigationAi)) {
                continue;
            }

            this.targetPos = candidate.immutable();
            this.standPos = stand.get();
            this.standRouteAttempts = 0;
            this.clearBlockAi.stop();
            this.breakingBlockAi.stop();
            this.toolAi.equipTool(PickaxeItem.class);
            return true;
        }

        this.targetPos = null;
        this.standPos = null;
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
                if (isActionableStoneTarget(playerNpc, serverLevel, candidate, pathNavigationAi)) {
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
                .filter(pos -> canStandAt(serverLevel, pos))
                .filter(pos -> pathNavigationAi.canReachOrSafelyDropTo(serverLevel, pos, MAX_STAND_SAFE_DROP_BLOCKS))
                .min(Comparator.comparingDouble(pos -> pos.distSqr(playerNpc.blockPosition())))
                .map(BlockPos::immutable);
    }

    private boolean isValidTarget(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        return StoneAi.isStone(state) && !isInsideHomeArea(this.playerNpc, pos);
    }

    private boolean isMineableTargetState(BlockState state) {
        return StoneAi.isStone(state);
    }

    private static boolean hasAirFace(ServerLevel serverLevel, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (serverLevel.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private static boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos);
    }

    private static boolean isActionableStoneTarget(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            BlockPos pos,
            PathNavigationAi pathNavigationAi
    ) {
        return StoneAi.isStone(serverLevel.getBlockState(pos))
                && !isInsideHomeArea(playerNpc, pos)
                && hasAirFace(serverLevel, pos)
                && findStandPos(playerNpc, serverLevel, pos, pathNavigationAi).isPresent();
    }

    private static boolean isAllowedStoneSearchPos(PlayerNpcEntity playerNpc, BlockPos center, BlockPos pos) {
        return pos.getY() >= center.getY() - 1 && !isInsideHomeArea(playerNpc, pos);
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

        this.repathTicks = 0;
        return false;
    }

    private boolean startClearingRoute(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return false;
        }

        List<BlockPos> candidates = ClearBlockAi.gatherObstructionCandidates(
                this.playerNpc.blockPosition(),
                this.standPos,
                this.targetPos
        );
        candidates.removeIf(pos -> pos.equals(this.targetPos) || this.isProtectedHomeBlock(pos));
        return this.clearBlockAi.startNearest(
                serverLevel,
                candidates,
                this::isClearablePathState,
                "clearing stone path",
                CLEAR_OBSTRUCTION_TICKS,
                CLEAR_OBSTRUCTION_DISTANCE_SQR
        );
    }

    private boolean isClearablePathState(BlockState state) {
        return !state.isAir();
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        return home.isPresent()
                && (PlayerNpcHomeUtil.isInside(home.get(), pos) || isInsideHomeSupport(home.get(), pos));
    }
}
