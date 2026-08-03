package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PillarUpAi;
import com.pla.smart_npc.entity.ai.ResourceAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.entity.ai.TreeAi;
import com.pla.smart_npc.entity.ai.TreeAi.Tree;
import com.pla.smart_npc.entity.ai.WaterEscapeAi;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

public class GatherLogsGoal extends Goal {
    private static final int TREE_SEARCH_RADIUS = 32;
    private static final int DIRT_SEARCH_RADIUS = 10;
    private static final int MAX_LOG_PATH_CHECKS = 32;
    private static final int MAX_DIRT_PATH_CHECKS = 64;
    private static final int MAX_GATHER_TICKS = 20 * 30;
    private static final int REQUIRED_BREAK_TICKS = 60;
    private static final int LEAF_CLEAR_TICKS = 12;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_PROTECTED_PILLAR_BLOCKS = 64;
    private static final int MAX_REQUIRED_DIRT_FOR_LOG_PILLAR = 8;
    private static final int PILLAR_BASE_SEARCH_RADIUS = 3;
    private static final int DESCENT_SEARCH_RADIUS = 8;
    private static final int MAX_DESCENT_TICKS = 20 * 10;
    private static final int MAX_PILLAR_SAFE_DROP_BLOCKS = 5;
    private static final int STAND_SCAN_BELOW_TARGET = 12;
    private static final int STAND_SCAN_ABOVE_TARGET = 2;
    private static final int MAX_IGNORED_CLEAR_BLOCKS = 32;
    private static final int MAX_IGNORED_LOG_TARGETS = 32;
    private static final int MAX_SAME_LEAF_CLEAR_TICKS = 40;
    private static final double BREAK_DISTANCE_SQR = 4.5D * 4.5D;
    private static final double STAND_REACHED_DISTANCE_SQR = 1.5D * 1.5D;
    private static final double STAND_CENTER_CORRECTION_DISTANCE_SQR = 0.35D * 0.35D;
    private static final double PILLAR_APPROACH_HORIZONTAL_DISTANCE_SQR = 2.5D * 2.5D;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final Queue<BlockPos> logQueue = new ArrayDeque<>();
    private final Set<BlockPos> protectedPillarBlocks = new LinkedHashSet<>();
    private final Set<BlockPos> ignoredClearBlocks = new LinkedHashSet<>();
    private final Set<BlockPos> ignoredLogTargets = new LinkedHashSet<>();
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PathNavigationAi pathNavigationAi;
    private final PillarUpAi pillarUpAi;
    private final WaterEscapeAi waterEscapeAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private BlockPos targetPos;
    private BlockPos standPos;
    private BlockPos dirtTargetPos;
    private BlockPos descentTargetPos;
    private BlockPos lastClearTargetPos;
    private int gatherTicks;
    private int repathTicks;
    private int descentTicks;
    private int sameClearTargetTicks;
    private boolean gatheringDirt;
    private boolean searchingDirtForPillar;
    private boolean descendingFromPillar;
    private String pillarTraceDetail = "";

    public GatherLogsGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.pillarUpAi = new PillarUpAi(playerNpc, this.toolAi, Items.DIRT, Blocks.DIRT.defaultBlockState());
        this.waterEscapeAi = new WaterEscapeAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean hasNearbyLogTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        Optional<Tree> tree = TreeAi.findNearest(
                serverLevel,
                playerNpc.blockPosition(),
                TREE_SEARCH_RADIUS,
                pos -> !isProtectedHomeLogTarget(playerNpc, pos)
        );
        if (tree.isEmpty()) {
            return false;
        }

        int pathChecks = 0;
        for (BlockPos candidate : tree.get().logsNearestFirst(playerNpc.blockPosition())) {
            if (!serverLevel.getBlockState(candidate).is(BlockTags.LOGS)
                    || isProtectedHomeLogTarget(playerNpc, candidate)) {
                continue;
            }
            if (canMineFromCurrentPosition(playerNpc, candidate)) {
                return true;
            }
            if (canPillarTowardFrom(playerNpc.blockPosition(), candidate)) {
                return true;
            }
            if (pathChecks++ >= MAX_LOG_PATH_CHECKS) {
                break;
            }
            if (findStandPos(playerNpc, serverLevel, candidate).isPresent()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || this.playerNpc.getGatherCooldown() > 0
                || MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (this.shouldStayHomeForWeather(serverLevel)
                || ReturnHomeGoal.shouldSuppressExplorationForHome(this.playerNpc, serverLevel)
                || GatherStoneGoal.isStoneSupplyPhaseActive(this.playerNpc, serverLevel)
                || !this.needsLogs(serverLevel)
                || !this.isMiningOnlyLogSupply()
                && (TerraformBuildSiteGoal.hasActionablePrepWork(this.playerNpc, serverLevel)
                || BuildHouseGoal.hasReadyHomeBuildWork(this.playerNpc, serverLevel))) {
            return false;
        }

        this.prepareLogQueue(serverLevel);
        return this.selectNextTarget(serverLevel);
    }

    @Override
    public boolean canContinueToUse() {
        return (this.descendingFromPillar || this.targetPos != null)
                && (this.descendingFromPillar || this.gatherTicks < MAX_GATHER_TICKS || this.isStandingOnProtectedPillar())
                && (!this.descendingFromPillar || this.descentTicks < MAX_DESCENT_TICKS)
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
                && (this.descendingFromPillar
                || this.playerNpc.level() instanceof ServerLevel serverLevel
                && !MiningNightCampGoal.shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)
                && !this.shouldStayHomeForWeather(serverLevel)
                && !ReturnHomeGoal.shouldSuppressExplorationForHome(this.playerNpc, serverLevel)
                && this.needsLogs(serverLevel));
    }

    @Override
    public void start() {
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.playerNpc.setCurrentAiState("ai.player_npc.gathering_logs");
        this.toolAi.equipTool(AxeItem.class);
        this.updateDetail();
        this.moveToStandPos();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.gatherTicks++;
        if (this.tickWaterEscape(serverLevel)) {
            return;
        }

        if (this.tickHelperAi(serverLevel)) {
            this.updateDetail();
            return;
        }

        if (this.descendingFromPillar) {
            this.tickDescendFromPillar(serverLevel);
            this.updateDetail();
            return;
        }

        if (this.gatherTicks >= MAX_GATHER_TICKS && this.isStandingOnProtectedPillar()) {
            this.breakingBlockAi.stop();
            this.clearBlockAi.stop();
            this.pillarUpAi.clear();
            if (this.tryStartPillarDescent(serverLevel)) {
                this.updateDetail();
                return;
            }
            this.pillarTraceDetail = "pillar descent failed after gather timeout @ "
                    + posText(this.playerNpc.blockPosition());
            this.targetPos = null;
            this.updateDetail();
            return;
        }

        if (this.targetPos == null || !this.isValidTarget(serverLevel, this.targetPos)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.breakingBlockAi.stop();
            if (!this.selectNextTarget(serverLevel)) {
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

        if (!this.gatheringDirt && this.tryStartClearBlock(serverLevel)) {
            this.updateDetail();
            return;
        }

        if (this.shouldPillarTowardLog(serverLevel) && this.tryPillarStep(serverLevel)) {
            this.updateDetail();
            return;
        }

        if (this.distanceToTargetSqr() > BREAK_DISTANCE_SQR) {
            this.breakingBlockAi.stop();
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                if (!this.moveToStandPos()) {
                    if (!this.gatheringDirt
                            && this.tryMoveToBetterPillarBase(serverLevel, this.playerNpc.blockPosition(), "stand path failed")) {
                        this.repathTicks = REPATH_INTERVAL_TICKS;
                        this.updateDetail();
                        return;
                    }
                    if (!this.selectNextTarget(serverLevel)) {
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
        if (!this.gatheringDirt
                && this.gatherTicks >= MAX_GATHER_TICKS
                && this.targetPos != null
                && !this.isStandingOnProtectedPillar()
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.isValidLog(serverLevel, this.targetPos)) {
            this.ignoreLogTarget(this.targetPos);
        }
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.toolAi.restoreMainHand();
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.pillarUpAi.clear();
        this.waterEscapeAi.stop();
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setGatherCooldown(20);
        }
        this.logQueue.clear();
        this.targetPos = null;
        this.standPos = null;
        this.dirtTargetPos = null;
        this.descentTargetPos = null;
        this.lastClearTargetPos = null;
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.descentTicks = 0;
        this.sameClearTargetTicks = 0;
        this.gatheringDirt = false;
        this.searchingDirtForPillar = false;
        this.descendingFromPillar = false;
        this.pillarTraceDetail = "";
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private boolean tickWaterEscape(ServerLevel serverLevel) {
        WaterEscapeAi.TickResult result = this.waterEscapeAi.tick(serverLevel, Math.max(1.0D, this.speed));
        if (result != WaterEscapeAi.TickResult.RUNNING && result != WaterEscapeAi.TickResult.DONE) {
            return false;
        }

        this.playerNpc.setCurrentAiState("ai.player_npc.gathering_logs");
        if (result == WaterEscapeAi.TickResult.RUNNING && !this.waterEscapeAi.detail().isBlank()) {
            this.playerNpc.setCurrentAiDetail(this.waterEscapeAi.detail());
        }
        return true;
    }

    private boolean tickHelperAi(ServerLevel serverLevel) {
        if (this.clearBlockAi.isRunning()) {
            BlockPos clearTarget = this.clearBlockAi.targetPos();
            this.trackClearTarget(clearTarget);
            if (!this.gatheringDirt && this.sameClearTargetTicks > MAX_SAME_LEAF_CLEAR_TICKS) {
                this.breakingBlockAi.stop();
                this.clearBlockAi.stop();
                this.ignoreClearBlock(clearTarget);
                if (this.forceClearLeaf(serverLevel, clearTarget)) {
                    this.pillarTraceDetail = "force cleared stuck leaf @ " + posText(clearTarget);
                } else {
                    this.pillarTraceDetail = "skipped stuck leaf @ " + posText(clearTarget);
                }
                this.prepareLogQueue(serverLevel);
                this.lastClearTargetPos = null;
                this.sameClearTargetTicks = 0;
                return true;
            }
            ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel);
            if (result == ClearBlockAi.TickResult.DONE || result == ClearBlockAi.TickResult.FAILED) {
                this.breakingBlockAi.stop();
                if (result == ClearBlockAi.TickResult.FAILED && !this.gatheringDirt) {
                    this.ignoreClearBlock(clearTarget);
                }
                if (!this.gatheringDirt) {
                    this.prepareLogQueue(serverLevel);
                }
                this.pillarTraceDetail = "";
                this.lastClearTargetPos = null;
                this.sameClearTargetTicks = 0;
            }
            return result == ClearBlockAi.TickResult.RUNNING;
        }

        if (this.pillarUpAi.isRunning()) {
            PillarUpAi.TickResult result = this.pillarUpAi.tick(serverLevel);
            if (result == PillarUpAi.TickResult.PLACED) {
                BlockPos placed = this.pillarUpAi.consumeLastPlacedPos();
                this.protectPillarBlock(placed);
                this.pillarTraceDetail = "pillar placed @ "
                        + posText(placed)
                        + "; protected="
                        + this.protectedPillarBlocks.size()
                        + "; retrying log @ "
                        + posText(this.targetPos);
            } else if (result == PillarUpAi.TickResult.FAILED) {
                BlockPos blocker = this.pillarUpAi.consumeLastFailureBlockerPos();
                String failureDetail = this.pillarUpAi.consumeLastFailureDetail();
                if (this.tryStartPillarFailureClear(serverLevel, blocker, failureDetail)) {
                    return true;
                }
                this.pillarTraceDetail = "pillar failed after start"
                        + (failureDetail == null || failureDetail.isBlank() ? "" : ": " + failureDetail)
                        + " @ "
                        + posText(this.playerNpc.blockPosition());
                if (this.tryMoveToBetterPillarBase(serverLevel, this.playerNpc.blockPosition(), this.pillarTraceDetail)) {
                    return true;
                }
            }
            return result == PillarUpAi.TickResult.RUNNING || result == PillarUpAi.TickResult.PLACED;
        }
        return false;
    }

    private void prepareLogQueue(ServerLevel serverLevel) {
        this.pruneIgnoredClearBlocks(serverLevel);
        this.pruneIgnoredLogTargets(serverLevel);
        this.logQueue.clear();
        Optional<Tree> tree = TreeAi.findNearest(
                serverLevel,
                this.playerNpc.blockPosition(),
                TREE_SEARCH_RADIUS,
                pos -> !this.isProtectedHomeLogTarget(pos)
        );
        tree.ifPresent(value -> this.logQueue.addAll(value.logsNearestFirst(this.playerNpc.blockPosition())));
    }

    private boolean shouldStayHomeForWeather(ServerLevel serverLevel) {
        return PlayerNpcHomeUtil.getHome(this.playerNpc).isPresent()
                && (serverLevel.isNight() || serverLevel.isThundering());
    }

    private boolean selectNextTarget(ServerLevel serverLevel) {
        this.gatheringDirt = false;
        this.searchingDirtForPillar = false;
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.pillarUpAi.clear();
        this.standPos = null;
        this.descentTargetPos = null;
        this.descendingFromPillar = false;
        this.descentTicks = 0;
        this.pillarTraceDetail = "";
        boolean rescanned = false;
        while (true) {
            while (!this.logQueue.isEmpty()) {
                BlockPos candidate = this.logQueue.poll();
                if (this.isIgnoredLogTarget(candidate) || !this.isValidLog(serverLevel, candidate)) {
                    continue;
                }

                Optional<BlockPos> stand = this.findStandPos(serverLevel, candidate);
                boolean canMineHere = canMineFromCurrentPosition(this.playerNpc, candidate);
                boolean canPillarHere = canPillarTowardFrom(this.playerNpc.blockPosition(), candidate);
                if (stand.isEmpty() && !canMineHere && !canPillarHere) {
                    continue;
                }

                this.targetPos = candidate.immutable();
                if (stand.isPresent()) {
                    this.standPos = stand.get();
                } else if (canMineHere || canPillarHere) {
                    this.standPos = this.playerNpc.blockPosition().immutable();
                }
                this.toolAi.equipTool(AxeItem.class);
                return true;
            }

            if (rescanned) {
                return false;
            }
            rescanned = true;
            this.prepareLogQueue(serverLevel);
        }
    }

    private boolean tryStartClearBlock(ServerLevel serverLevel) {
        if (this.targetPos == null || !this.isValidLog(serverLevel, this.targetPos)) {
            return false;
        }

        List<BlockPos> candidates = ClearBlockAi.gatherObstructionCandidates(this.playerNpc.blockPosition(), this.standPos, this.targetPos);
        candidates.removeIf(this::isIgnoredClearBlock);
        return this.clearBlockAi.startNearest(serverLevel, candidates, GatherLogsGoal::isLogClearBlock, "clearing foliage", LEAF_CLEAR_TICKS);
    }

    private void trackClearTarget(BlockPos clearTarget) {
        if (clearTarget == null) {
            this.lastClearTargetPos = null;
            this.sameClearTargetTicks = 0;
            return;
        }
        if (!clearTarget.equals(this.lastClearTargetPos)) {
            this.lastClearTargetPos = clearTarget.immutable();
            this.sameClearTargetTicks = 0;
        }
        this.sameClearTargetTicks++;
    }

    private boolean forceClearLeaf(ServerLevel serverLevel, BlockPos clearTarget) {
        if (clearTarget == null || !isLogClearBlock(serverLevel.getBlockState(clearTarget))) {
            return false;
        }
        boolean cleared = serverLevel.destroyBlock(clearTarget, false, this.playerNpc);
        if (!cleared && serverLevel.getBlockEntity(clearTarget) == null) {
            cleared = serverLevel.setBlockAndUpdate(clearTarget, Blocks.AIR.defaultBlockState());
        }
        if (cleared) {
            this.playerNpc.clearBlockBreakProgress(clearTarget);
        }
        return cleared || !isLogClearBlock(serverLevel.getBlockState(clearTarget));
    }

    private void ignoreClearBlock(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.ignoredClearBlocks.add(pos.immutable());
        while (this.ignoredClearBlocks.size() > MAX_IGNORED_CLEAR_BLOCKS) {
            Iterator<BlockPos> iterator = this.ignoredClearBlocks.iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private boolean isIgnoredClearBlock(BlockPos pos) {
        return pos != null && this.ignoredClearBlocks.contains(pos.immutable());
    }

    private void pruneIgnoredClearBlocks(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        this.ignoredClearBlocks.removeIf(pos ->
                pos.distSqr(feet) > TREE_SEARCH_RADIUS * TREE_SEARCH_RADIUS
                        || !isLogClearBlock(serverLevel.getBlockState(pos)));
    }

    private void ignoreLogTarget(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.ignoredLogTargets.add(pos.immutable());
        while (this.ignoredLogTargets.size() > MAX_IGNORED_LOG_TARGETS) {
            Iterator<BlockPos> iterator = this.ignoredLogTargets.iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private boolean isIgnoredLogTarget(BlockPos pos) {
        return pos != null && this.ignoredLogTargets.contains(pos.immutable());
    }

    private void pruneIgnoredLogTargets(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        this.ignoredLogTargets.removeIf(pos ->
                pos.distSqr(feet) > TREE_SEARCH_RADIUS * TREE_SEARCH_RADIUS
                        || !this.isValidLog(serverLevel, pos));
    }

    private static boolean isLogClearBlock(BlockState state) {
        return state.is(BlockTags.LEAVES)
                || state.is(Blocks.VINE)
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT);
    }

    private static boolean isPillarClearBlock(BlockState state) {
        return state.is(BlockTags.LEAVES)
                || state.is(Blocks.VINE)
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(Blocks.GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.SNOW);
    }

    private boolean shouldPillarTowardLog(ServerLevel serverLevel) {
        if (this.gatheringDirt || this.targetPos == null || !this.isValidLog(serverLevel, this.targetPos)) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        return distanceFromStandToTargetSqr(feet, this.targetPos) > BREAK_DISTANCE_SQR
                && canPillarTowardFrom(feet, this.targetPos);
    }

    private boolean tryPillarStep(ServerLevel serverLevel) {
        int dirtCount = ResourceAi.countDirt(this.playerNpc);
        int dirtNeeded = this.requiredDirtForCurrentPillarPlan();
        if (dirtCount < dirtNeeded) {
            this.searchingDirtForPillar = true;
            this.pillarTraceDetail = "pillar needs dirt "
                    + dirtCount
                    + "/"
                    + dirtNeeded
                    + "; searching around target "
                    + posText(this.targetPos);
            this.dirtTargetPos = this.findNearestDirt(serverLevel);
            if (this.dirtTargetPos != null) {
                Optional<BlockPos> dirtStand = this.findStandPos(serverLevel, this.dirtTargetPos);
                if (dirtStand.isEmpty()) {
                    this.pillarTraceDetail = "pillar dirt found but no stand @ " + posText(this.dirtTargetPos);
                    return false;
                }
                this.targetPos = this.dirtTargetPos;
                this.standPos = dirtStand.get();
                this.gatheringDirt = true;
                this.searchingDirtForPillar = false;
                this.pillarTraceDetail = "pillar collecting dirt "
                        + dirtCount
                        + "/"
                        + dirtNeeded
                        + " @ "
                        + posText(this.dirtTargetPos);
                this.breakingBlockAi.stop();
                this.toolAi.equipTool(ShovelItem.class);
                return true;
            }
            this.pillarTraceDetail = "pillar needs dirt but no nearby dirt target";
            return this.tryStartClearBlock(serverLevel);
        }

        this.searchingDirtForPillar = false;
        BlockPos feet = this.playerNpc.blockPosition();
        if (!this.playerNpc.onGround()) {
            this.pillarTraceDetail = "pillar waiting for ground @ " + posText(feet);
            this.lookDownAt(feet);
            return true;
        }

        String blocker = this.pillarUpAi.startBlocker(serverLevel, feet);
        if (!blocker.isBlank()) {
            this.pillarTraceDetail = "pillar blocked: "
                    + blocker
                    + " dirt="
                    + dirtCount
                    + " "
                    + targetMetricsText(feet, this.targetPos);
            if (this.tryStartPillarSpaceClear(serverLevel, feet) || this.tryStartClearBlock(serverLevel)) {
                return true;
            }
            if (this.tryMoveToBetterPillarBase(serverLevel, feet, blocker)) {
                return true;
            }
            return true;
        }
        if (!this.pillarUpAi.start(serverLevel, feet)) {
            this.pillarTraceDetail = "pillar start failed dirt="
                    + dirtCount
                    + " "
                    + targetMetricsText(feet, this.targetPos);
            return true;
        }
        this.pillarTraceDetail = "";
        return true;
    }

    private int requiredDirtForCurrentPillarPlan() {
        if (this.targetPos == null) {
            return 1;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        for (int blocks = 0; blocks <= MAX_REQUIRED_DIRT_FOR_LOG_PILLAR; blocks++) {
            BlockPos simulatedFeet = feet.above(blocks);
            if (distanceFromStandToTargetSqr(simulatedFeet, this.targetPos) <= BREAK_DISTANCE_SQR
                    || !canPillarTowardFrom(simulatedFeet, this.targetPos)) {
                return Math.max(0, blocks);
            }
        }
        return MAX_REQUIRED_DIRT_FOR_LOG_PILLAR;
    }

    private boolean tryStartPillarSpaceClear(ServerLevel serverLevel, BlockPos feet) {
        BlockPos blockerPos = this.pillarUpAi.startBlockerPos(serverLevel, feet);
        if (blockerPos != null) {
            if (this.tryStartPillarFailureClear(serverLevel, blockerPos, "pillar start blocked")) {
                return true;
            }
        }

        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(feet);
        candidates.add(feet.above());
        candidates.add(feet.above(2));
        if (this.targetPos != null) {
            candidates.addAll(ClearBlockAi.gatherObstructionCandidates(feet, this.standPos, this.targetPos));
        }
        return this.clearBlockAi.startNearest(serverLevel, candidates, GatherLogsGoal::isPillarClearBlock, "clearing pillar space", LEAF_CLEAR_TICKS);
    }

    private boolean tryStartPillarFailureClear(ServerLevel serverLevel, BlockPos blockerPos, String failureDetail) {
        if (blockerPos == null) {
            return false;
        }

        BlockState blockerState = serverLevel.getBlockState(blockerPos);
        if (blockerState.is(BlockTags.LOGS) && !this.isProtectedHomeLogTarget(blockerPos)) {
            this.targetPos = blockerPos.immutable();
            this.standPos = this.playerNpc.blockPosition().immutable();
            this.breakingBlockAi.stop();
            this.clearBlockAi.stop();
            this.toolAi.equipTool(AxeItem.class);
            this.pillarTraceDetail = "pillar blocked by log; mining blocker @ "
                    + posText(blockerPos)
                    + detailSuffix(failureDetail);
            return true;
        }

        if (this.clearBlockAi.start(
                serverLevel,
                blockerPos,
                GatherLogsGoal::isPillarClearBlock,
                "clearing pillar blocker",
                LEAF_CLEAR_TICKS
        )) {
            this.pillarTraceDetail = "clearing pillar blocker @ "
                    + posText(blockerPos)
                    + detailSuffix(failureDetail);
            return true;
        }
        return false;
    }

    private boolean tryMoveToBetterPillarBase(ServerLevel serverLevel, BlockPos blockedFeet, String blocker) {
        Optional<BlockPos> betterBase = this.findBetterPillarBase(serverLevel, blockedFeet);
        if (betterBase.isEmpty()) {
            return false;
        }

        this.standPos = betterBase.get();
        this.breakingBlockAi.stop();
        this.clearBlockAi.stop();
        this.pillarUpAi.clear();
        this.pillarTraceDetail = "pillar relocating from blocked base "
                + posText(blockedFeet)
                + " -> "
                + posText(this.standPos)
                + " because "
                + blocker
                + " "
                + targetMetricsText(this.standPos, this.targetPos);
        return this.moveToStandPos();
    }

    private Optional<BlockPos> findBetterPillarBase(ServerLevel serverLevel, BlockPos blockedFeet) {
        if (this.targetPos == null) {
            return Optional.empty();
        }

        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        for (int dx = -PILLAR_BASE_SEARCH_RADIUS; dx <= PILLAR_BASE_SEARCH_RADIUS; dx++) {
            for (int dz = -PILLAR_BASE_SEARCH_RADIUS; dz <= PILLAR_BASE_SEARCH_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                int x = this.targetPos.getX() + dx;
                int z = this.targetPos.getZ() + dz;
                int surfaceY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                candidates.add(new BlockPos(x, surfaceY, z));
                candidates.add(new BlockPos(x, surfaceY - 1, z));
                candidates.add(new BlockPos(x, surfaceY + 1, z));
                addVerticalStandCandidates(this.playerNpc, serverLevel, candidates, new BlockPos(x, this.playerNpc.blockPosition().getY(), z));
            }
        }

        return candidates.stream()
                .map(BlockPos::immutable)
                .distinct()
                .filter(pos -> !pos.equals(blockedFeet))
                .filter(pos -> distanceFromStandToTargetSqr(pos, this.targetPos) > BREAK_DISTANCE_SQR)
                .filter(pos -> canPillarTowardFrom(pos, this.targetPos))
                .filter(pos -> canStandAt(serverLevel, pos))
                .filter(pos -> canReachOrAlreadyAt(this.playerNpc, pos))
                .filter(pos -> this.pillarUpAi.startBlocker(serverLevel, pos).isBlank())
                .min(Comparator
                        .comparingDouble((BlockPos pos) -> horizontalDistanceToTargetColumnSqr(pos, this.targetPos))
                        .thenComparingDouble(pos -> pos.distSqr(this.playerNpc.blockPosition())));
    }

    private void lookDownAt(BlockPos pos) {
        this.playerNpc.getLookControl().setLookAt(
                pos.getX() + 0.5D,
                pos.getY() - 0.5D,
                pos.getZ() + 0.5D,
                60.0F,
                60.0F
        );
    }

    private void mineTarget(ServerLevel serverLevel) {
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                state -> this.isMineableTargetState(serverLevel, this.targetPos, state),
                REQUIRED_BREAK_TICKS,
                this.gatheringDirt ? "mining dirt for pillar" : "mining log"
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }

        if (result == BreakingBlockAi.TickResult.DONE && this.gatheringDirt) {
            this.gatheringDirt = false;
            this.pillarTraceDetail = "pillar dirt collected; dirt=" + ResourceAi.countDirt(this.playerNpc);
            this.prepareLogQueue(serverLevel);
        } else if (result == BreakingBlockAi.TickResult.DONE
                && !this.needsLogs(serverLevel)
                && this.tryStartPillarDescent(serverLevel)) {
            return;
        } else if (result == BreakingBlockAi.TickResult.FAILED
                && !this.gatheringDirt
                && this.targetPos != null
                && this.isValidLog(serverLevel, this.targetPos)) {
            this.ignoreLogTarget(this.targetPos);
        }
        this.selectNextTarget(serverLevel);
    }

    private boolean needsLogs(ServerLevel serverLevel) {
        if (this.isMiningOnlyLogSupply()) {
            return this.playerNpc.shouldPrioritizeLogGathering();
        }
        if (!this.playerNpc.isDailyJobActive(PlayerNpcInterest.BUILDING)) {
            return false;
        }
        return this.playerNpc.shouldPrioritizeLogGathering()
                || PlayerNpcBuildMaterialUtil.needsLogsForCurrentBuild(serverLevel, this.playerNpc);
    }

    private boolean isMiningOnlyLogSupply() {
        return this.playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)
                && !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING);
    }

    private boolean tryStartPillarDescent(ServerLevel serverLevel) {
        if (!this.isStandingOnProtectedPillar()) {
            return false;
        }

        Optional<BlockPos> descentTarget = this.findDescentTarget(serverLevel);
        if (descentTarget.isEmpty()) {
            this.pillarTraceDetail = "pillar descent needed but no safe lower ground @ "
                    + posText(this.playerNpc.blockPosition());
            return false;
        }

        this.targetPos = null;
        this.standPos = null;
        this.dirtTargetPos = null;
        this.descentTargetPos = descentTarget.get();
        this.descendingFromPillar = true;
        this.descentTicks = 0;
        this.breakingBlockAi.stop();
        this.pillarUpAi.clear();
        this.pillarTraceDetail = "descending from pillar @ "
                + posText(this.playerNpc.blockPosition())
                + " -> "
                + posText(this.descentTargetPos);
        this.pathNavigationAi.moveTo(serverLevel, this.descentTargetPos, this.speed, MAX_PILLAR_SAFE_DROP_BLOCKS);
        return true;
    }

    private void tickDescendFromPillar(ServerLevel serverLevel) {
        this.descentTicks++;
        if (this.descentTargetPos == null
                || this.descentTicks > MAX_DESCENT_TICKS
                || !this.isStandingOnProtectedPillar()
                || this.playerNpc.distanceToSqr(
                this.descentTargetPos.getX() + 0.5D,
                this.descentTargetPos.getY(),
                this.descentTargetPos.getZ() + 0.5D
        ) <= STAND_REACHED_DISTANCE_SQR) {
            this.descendingFromPillar = false;
            this.descentTargetPos = null;
            this.pillarTraceDetail = "";
            return;
        }

        if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
            if (!this.pathNavigationAi.moveTo(serverLevel, this.descentTargetPos, this.speed, MAX_PILLAR_SAFE_DROP_BLOCKS)) {
                if (!this.tryStartDescentClear(serverLevel)) {
                    this.descendingFromPillar = false;
                    this.descentTargetPos = null;
                    this.pillarTraceDetail = "";
                }
            }
            this.repathTicks = REPATH_INTERVAL_TICKS;
        }
    }

    private boolean tryStartDescentClear(ServerLevel serverLevel) {
        if (this.descentTargetPos == null) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : directionsToward(feet, this.descentTargetPos)) {
            BlockPos step = feet.relative(direction);
            candidates.add(step);
            candidates.add(step.above());
        }
        return this.clearBlockAi.startNearest(
                serverLevel,
                candidates,
                GatherLogsGoal::isPillarClearBlock,
                "clearing descent path",
                LEAF_CLEAR_TICKS
        );
    }

    private Optional<BlockPos> findDescentTarget(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -DESCENT_SEARCH_RADIUS; dx <= DESCENT_SEARCH_RADIUS; dx++) {
            for (int dz = -DESCENT_SEARCH_RADIUS; dz <= DESCENT_SEARCH_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (candidate.getY() >= feet.getY()
                        || feet.getY() - candidate.getY() > MAX_PILLAR_SAFE_DROP_BLOCKS
                        || !canStandAt(serverLevel, candidate)
                        || !this.pathNavigationAi.canReachOrSafelyDropTo(serverLevel, candidate, MAX_PILLAR_SAFE_DROP_BLOCKS)) {
                    continue;
                }
                candidates.add(candidate.immutable());
            }
        }

        return candidates.stream()
                .min(Comparator
                        .comparingDouble((BlockPos pos) -> pos.distSqr(feet))
                        .thenComparingInt(BlockPos::getY));
    }

    private boolean isStandingOnProtectedPillar() {
        BlockPos feet = this.playerNpc.blockPosition();
        return this.isProtectedPillarBlock(feet)
                || this.isProtectedPillarBlock(feet.below())
                || this.isProtectedPillarBlock(feet.below(2));
    }

    private static List<Direction> directionsToward(BlockPos from, BlockPos to) {
        List<Direction> directions = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            directions.add(direction);
        }
        directions.sort(Comparator.comparingDouble(direction ->
                horizontalDistanceToTargetColumnSqr(from.relative(direction), to)));
        return directions;
    }

    private BlockPos findNearestDirt(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        this.pruneProtectedPillarBlocks(serverLevel, center);
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        for (int x = center.getX() - DIRT_SEARCH_RADIUS; x <= center.getX() + DIRT_SEARCH_RADIUS; x++) {
            for (int z = center.getZ() - DIRT_SEARCH_RADIUS; z <= center.getZ() + DIRT_SEARCH_RADIUS; z++) {
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                this.addDirtCandidate(serverLevel, candidates, new BlockPos(x, y, z));
            }
        }

        for (BlockPos mutable : BlockPos.betweenClosed(
                center.offset(-DIRT_SEARCH_RADIUS, -2, -DIRT_SEARCH_RADIUS),
                center.offset(DIRT_SEARCH_RADIUS, 2, DIRT_SEARCH_RADIUS))) {
            this.addDirtCandidate(serverLevel, candidates, mutable.immutable());
        }

        List<BlockPos> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(pos -> pos.distSqr(center)));
        int pathChecks = 0;
        for (BlockPos pos : sorted) {
            if (pathChecks++ >= MAX_DIRT_PATH_CHECKS) {
                break;
            }
            if (this.findStandPos(serverLevel, pos).isPresent()) {
                return pos;
            }
        }
        return null;
    }

    private void addDirtCandidate(ServerLevel serverLevel, Set<BlockPos> candidates, BlockPos pos) {
        if (this.isValidDirtTarget(serverLevel, pos)) {
            candidates.add(pos.immutable());
        }
    }

    private boolean isValidDirtTarget(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null || this.isProtectedPillarBlock(pos) || this.isCurrentSupportBlock(pos)) {
            return false;
        }
        BlockState state = serverLevel.getBlockState(pos);
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK);
    }

    private boolean isProtectedPillarBlock(BlockPos pos) {
        return pos != null && this.protectedPillarBlocks.contains(pos.immutable());
    }

    private boolean isCurrentSupportBlock(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        return pos.equals(feet)
                || pos.equals(feet.below())
                || pos.equals(feet.below(2));
    }

    private void protectPillarBlock(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.protectedPillarBlocks.add(pos.immutable());
        while (this.protectedPillarBlocks.size() > MAX_PROTECTED_PILLAR_BLOCKS) {
            Iterator<BlockPos> iterator = this.protectedPillarBlocks.iterator();
            if (!iterator.hasNext()) {
                return;
            }
            iterator.next();
            iterator.remove();
        }
    }

    private void pruneProtectedPillarBlocks(ServerLevel serverLevel, BlockPos center) {
        this.protectedPillarBlocks.removeIf(pos ->
                pos.distSqr(center) > TREE_SEARCH_RADIUS * TREE_SEARCH_RADIUS
                        || !serverLevel.getBlockState(pos).is(Blocks.DIRT));
    }

    private static boolean canMineFromCurrentPosition(PlayerNpcEntity playerNpc, BlockPos target) {
        return playerNpc.distanceToSqr(
                target.getX() + 0.5D,
                target.getY() + 0.5D,
                target.getZ() + 0.5D
        ) <= BREAK_DISTANCE_SQR;
    }

    private static boolean canMineFromStandPosition(BlockPos standPos, BlockPos target) {
        return distanceFromStandToTargetSqr(standPos, target) <= BREAK_DISTANCE_SQR;
    }

    private static boolean canPillarTowardFrom(BlockPos feet, BlockPos target) {
        int verticalGap = target.getY() - feet.getY();
        return verticalGap >= 3 && horizontalDistanceToTargetColumnSqr(feet, target) <= PILLAR_APPROACH_HORIZONTAL_DISTANCE_SQR;
    }

    private Optional<BlockPos> findStandPos(ServerLevel serverLevel, BlockPos target) {
        return findStandPos(this.playerNpc, serverLevel, target);
    }

    private static Optional<BlockPos> findStandPos(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos target) {
        LinkedHashSet<BlockPos> candidates = new LinkedHashSet<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = target.relative(direction);
            addSurfaceStandCandidate(serverLevel, candidates, side);
            addVerticalStandCandidates(playerNpc, serverLevel, candidates, side);
        }
        addNearbySurfaceStandCandidates(serverLevel, candidates, target);

        return candidates.stream()
                .filter(pos -> canStandAt(serverLevel, pos))
                .filter(pos -> canReachOrAlreadyAt(playerNpc, pos))
                .filter(pos -> canMineFromStandPosition(pos, target) || canPillarTowardFrom(pos, target))
                .min(Comparator
                        .comparingDouble((BlockPos pos) -> horizontalDistanceToTargetColumnSqr(pos, target))
                        .thenComparingDouble(pos -> pos.distSqr(playerNpc.blockPosition())))
                .map(BlockPos::immutable);
    }

    private static void addSurfaceStandCandidate(ServerLevel serverLevel, Set<BlockPos> candidates, BlockPos side) {
        int surfaceY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, side.getX(), side.getZ());
        candidates.add(new BlockPos(side.getX(), surfaceY, side.getZ()));
        candidates.add(new BlockPos(side.getX(), surfaceY - 1, side.getZ()));
        candidates.add(new BlockPos(side.getX(), surfaceY + 1, side.getZ()));
    }

    private static void addNearbySurfaceStandCandidates(ServerLevel serverLevel, Set<BlockPos> candidates, BlockPos target) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int x = target.getX() + dx;
                int z = target.getZ() + dz;
                int surfaceY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                candidates.add(new BlockPos(x, surfaceY, z));
            }
        }
    }

    private static void addVerticalStandCandidates(PlayerNpcEntity playerNpc, ServerLevel serverLevel, Set<BlockPos> candidates, BlockPos side) {
        int playerY = playerNpc.blockPosition().getY();
        int minY = Math.max(
                serverLevel.getMinBuildHeight() + 1,
                Math.min(playerY, side.getY()) - STAND_SCAN_BELOW_TARGET
        );
        int maxY = Math.min(
                serverLevel.getMaxBuildHeight() - 2,
                Math.max(playerY, side.getY()) + STAND_SCAN_ABOVE_TARGET
        );
        for (int y = maxY; y >= minY; y--) {
            candidates.add(new BlockPos(side.getX(), y, side.getZ()));
        }
    }

    private static boolean canReachOrAlreadyAt(PlayerNpcEntity playerNpc, BlockPos pos) {
        if (playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) <= STAND_REACHED_DISTANCE_SQR) {
            return true;
        }

        Path path = playerNpc.getNavigation().createPath(pos, 0);
        return isUsablePathToStand(path, pos);
    }

    private static double horizontalDistanceToTargetColumnSqr(BlockPos standPos, BlockPos target) {
        int dx = standPos.getX() - target.getX();
        int dz = standPos.getZ() - target.getZ();
        return dx * dx + dz * dz;
    }

    private static double distanceFromStandToTargetSqr(BlockPos standPos, BlockPos target) {
        double dx = standPos.getX() - target.getX();
        double dy = standPos.getY() - (target.getY() + 0.5D);
        double dz = standPos.getZ() - target.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean isValidTarget(ServerLevel serverLevel, BlockPos pos) {
        return this.gatheringDirt
                ? this.isValidDirtTarget(serverLevel, pos)
                : this.isValidLog(serverLevel, pos);
    }

    private boolean isMineableTargetState(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (this.gatheringDirt) {
            return this.isValidDirtTarget(serverLevel, pos)
                    && (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK));
        }
        return this.isValidLog(serverLevel, pos) && state.is(BlockTags.LOGS);
    }

    private boolean isValidLog(ServerLevel serverLevel, BlockPos pos) {
        return pos != null
                && !this.isProtectedHomeLogTarget(pos)
                && serverLevel.getBlockState(pos).is(BlockTags.LOGS);
    }

    private boolean isProtectedHomeLogTarget(BlockPos pos) {
        return isProtectedHomeLogTarget(this.playerNpc, pos);
    }

    private static boolean isProtectedHomeLogTarget(PlayerNpcEntity playerNpc, BlockPos pos) {
        if (playerNpc == null || pos == null) {
            return false;
        }
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        return home.isPresent()
                && (PlayerNpcHomeUtil.isInside(home.get(), pos)
                || PlayerNpcHomeUtil.isInsideBuildFootprint(playerNpc, pos));
    }

    private static boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private boolean moveToStandPos() {
        if (this.standPos == null) {
            return false;
        }

        double standDistanceSqr = this.playerNpc.distanceToSqr(
                this.standPos.getX() + 0.5D,
                this.standPos.getY(),
                this.standPos.getZ() + 0.5D
        );
        if (standDistanceSqr <= STAND_REACHED_DISTANCE_SQR) {
            if (this.targetPos == null || this.distanceToTargetSqr() <= BREAK_DISTANCE_SQR) {
                return true;
            }
            if (standDistanceSqr > STAND_CENTER_CORRECTION_DISTANCE_SQR) {
                this.playerNpc.getMoveControl().setWantedPosition(
                        this.standPos.getX() + 0.5D,
                        this.standPos.getY(),
                        this.standPos.getZ() + 0.5D,
                        this.speed
                );
                this.pillarTraceDetail = "closing on log stand @ "
                        + posText(this.standPos)
                        + " "
                        + targetMetricsText(this.playerNpc.blockPosition(), this.targetPos);
                return true;
            }
            return false;
        }

        Path path = this.playerNpc.getNavigation().createPath(this.standPos, 0);
        if (isUsablePathToStand(path, this.standPos)) {
            return this.playerNpc.getNavigation().moveTo(path, this.speed);
        }
        return false;
    }

    private static boolean isUsablePathToStand(Path path, BlockPos standPos) {
        if (path == null || !path.canReach()) {
            return false;
        }
        Node endNode = path.getEndNode();
        return endNode != null && endNode.asBlockPos().equals(standPos);
    }

    private double distanceToTargetSqr() {
        return this.targetPos == null
                ? Double.MAX_VALUE
                : this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D);
    }

    private void updateDetail() {
        if (this.clearBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.clearBlockAi.detail());
            return;
        }
        if (this.breakingBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.breakingBlockAi.detail());
            return;
        }
        if (this.pillarUpAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.pillarUpAi.detail());
            return;
        }
        if (this.descendingFromPillar) {
            this.playerNpc.setCurrentAiDetail(this.pillarTraceDetail.isBlank()
                    ? "descending from pillar"
                    : this.pillarTraceDetail);
            return;
        }
        if (!this.pillarTraceDetail.isBlank()) {
            this.playerNpc.setCurrentAiDetail(this.pillarTraceDetail);
            return;
        }
        if (this.targetPos == null) {
            this.playerNpc.setCurrentAiDetail("searching for logs");
            return;
        }
        if (this.searchingDirtForPillar) {
            this.playerNpc.setCurrentAiDetail("searching dirt for pillar");
            return;
        }
        if (this.gatheringDirt) {
            this.playerNpc.setCurrentAiDetail("collecting dirt for pillar @ "
                    + this.targetPos.getX() + " "
                    + this.targetPos.getY() + " "
                    + this.targetPos.getZ());
            return;
        }
        this.playerNpc.setCurrentAiDetail("log @ "
                + this.targetPos.getX() + " "
                + this.targetPos.getY() + " "
                + this.targetPos.getZ()
                + " dirt="
                + ResourceAi.countDirt(this.playerNpc)
                + " "
                + targetMetricsText(this.playerNpc.blockPosition(), this.targetPos));
    }

    private static String targetMetricsText(BlockPos feet, BlockPos target) {
        if (feet == null || target == null) {
            return "target=none";
        }
        int verticalGap = target.getY() - feet.getY();
        double horizontal = Math.sqrt(horizontalDistanceToTargetColumnSqr(feet, target));
        return String.format(Locale.ROOT, "feet=%s target=%s gap=%d horiz=%.1f", posText(feet), posText(target), verticalGap, horizontal);
    }

    private static String posText(BlockPos pos) {
        if (pos == null) {
            return "none";
        }
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String detailSuffix(String detail) {
        return detail == null || detail.isBlank() ? "" : " (" + detail + ")";
    }
}
