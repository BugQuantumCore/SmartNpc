package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.util.PlayerNpcBlockBreakUtil;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class EscapeHoleWithBlockGoal extends Goal {
    private static final String AI_STATE = "ai.player_npc.pillaring_up";
    private static final int COOLDOWN_TICKS = 40;
    private static final int PLACE_DELAY_TICKS = 2;
    private static final int MAX_PLACE_WAIT_TICKS = 24;
    private static final double PLACE_CLEARANCE_Y = 0.95D;
    private static final double FALLBACK_PLACE_CLEARANCE_Y = 0.78D;
    private static final int MIN_ROUTE_ESCAPE_BLOCKS = 16;
    private static final int MAX_ROUTE_ESCAPE_BLOCKS = 96;
    private static final int MAX_GOAL_TICKS = 20 * 120;
    private static final int SEARCH_RADIUS = 5;
    private static final double BREAK_DISTANCE_SQR = 3.0D * 3.0D;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_FAILED_PATH_TICKS = 20 * 3;
    private static final int PILLAR_SEARCH_RADIUS = 5;
    private static final int PILLAR_PLAN_RETRY_TICKS = 20;
    private static final int PILLAR_SURFACE_SCAN_UP = 96;
    private static final double PILLAR_BASE_REACHED_SQR = 1.2D * 1.2D;
    private static final int ROUTE_NAV_REPATH_TICKS = 10;
    private static final int ROUTE_NAV_MAX_FAILED_TICKS = 20 * 4;
    private static final int ROUTE_NAV_HORIZONTAL_RADIUS = 18;
    private static final int ROUTE_NAV_STEP = 4;
    private static final int ROUTE_NAV_VERTICAL_DOWN = 3;
    private static final int ROUTE_NAV_VERTICAL_UP = 12;
    private static final int ROUTE_NAV_MIN_UPWARD_GAIN = 2;
    private static final int ROUTE_NAV_MAX_PATH_CHECKS = 10;
    private static final double ROUTE_NAV_REACHED_SQR = 2.0D * 2.0D;

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private EscapeMode mode = EscapeMode.NONE;
    private BlockPos placePos;
    private BlockPos minePos;
    private BlockPos mineStandPos;
    private BlockPos routeNavigationTarget;
    private BlockPos climbTargetPos;
    private BlockPos pillarBasePos;
    private BlockPos pillarClearPos;
    private BlockPos exitClearPos;
    private int pillarExitY;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private ItemStack previousPillarMainHand = ItemStack.EMPTY;
    private int placeDelayTicks;
    private int placeWaitTicks;
    private int mineTicks;
    private int repathTicks;
    private int failedPathTicks;
    private int goalTicks;
    private int requiredEscapeBlocks;
    private int maxPillarBlocks;
    private int pillarsPlaced;
    private int nextPillarPlanTick;
    private int failedPillarPlaceAttempts;
    private boolean usingTemporaryPickaxe;
    private boolean usingTemporaryBlock;
    private boolean finished;

    public EscapeHoleWithBlockGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK));
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isInWaterOrBubble()
                || this.playerNpc.isStoneAccessClearing()) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        if (requestedTarget != null && this.hasSatisfiedRequestedRoute(serverLevel, feet, requestedTarget)) {
            this.playerNpc.clearUpwardEscapeTarget();
            return false;
        }
        boolean hasRequestedEscape = requestedTarget != null;
        boolean forceRequestedClimb = this.playerNpc.isForcedUpwardEscape()
                && hasRequestedEscape
                && requestedTarget.getY() > feet.getY() + 1;
        boolean trapped = this.hasOpenBodySpace(serverLevel, feet)
                && this.isWalkableFloor(serverLevel, feet.below())
                && this.isActuallyTrapped(serverLevel, feet);
        BlockPos routeTarget = this.getUpwardRouteTarget(serverLevel, feet);
        boolean hasUpwardRouteTarget = routeTarget != null && this.isUsableUpwardRouteTarget(serverLevel, feet, routeTarget);
        if (forceRequestedClimb && !hasUpwardRouteTarget) {
            routeTarget = requestedTarget.immutable();
            hasUpwardRouteTarget = true;
        }
        if (!trapped && !hasUpwardRouteTarget && this.hasReachedOpenSky(serverLevel, feet) && !forceRequestedClimb) {
            if (requestedTarget != null) {
                this.playerNpc.clearUpwardEscapeTarget();
            }
            return false;
        }
        if (this.playerNpc.getHoleEscapeCooldown() > 0 && requestedTarget == null) {
            return false;
        }

        if (!trapped && !hasUpwardRouteTarget) {
            return false;
        }

        if (this.playerNpc.tickCount < this.nextPillarPlanTick && !forceRequestedClimb) {
            return false;
        }

        boolean routeNeedsClimb = forceRequestedClimb || hasUpwardRouteTarget && this.routeNeedsClimb(serverLevel, feet, routeTarget);

        if (!trapped && !routeNeedsClimb) {
            return false;
        }

        this.resetPlan();
        this.climbTargetPos = routeNeedsClimb ? routeTarget : null;
        this.requiredEscapeBlocks = 1;

        BlockPos currentColumnTarget = this.findCurrentColumnClearTarget(serverLevel, feet);
        if (currentColumnTarget != null) {
            this.mode = EscapeMode.CLEAR_EXIT;
            this.exitClearPos = currentColumnTarget;
            return true;
        }

        if (routeNeedsClimb && !trapped && this.hasReachedOpenSky(serverLevel, feet) && !forceRequestedClimb) {
            return false;
        }

        PillarPlan pillarPlan = this.findPillarPlan(serverLevel, feet, routeNeedsClimb ? routeTarget : null);
        if (pillarPlan == null) {
            this.nextPillarPlanTick = this.playerNpc.tickCount + PILLAR_PLAN_RETRY_TICKS;
            return false;
        }
        if (routeNeedsClimb && this.exceedsRequestedRouteMax(pillarPlan)) {
            this.playerNpc.clearUpwardEscapeTarget();
            this.nextPillarPlanTick = this.playerNpc.tickCount + PILLAR_PLAN_RETRY_TICKS;
            return false;
        }
        this.nextPillarPlanTick = 0;
        this.pillarBasePos = pillarPlan.basePos();
        this.pillarExitY = pillarPlan.exitY();
        if (routeNeedsClimb) {
            this.requiredEscapeBlocks = Math.max(1, pillarPlan.blocksNeeded());
        }

        int escapeBlocks = this.countEscapeBlocks();
        if (escapeBlocks < this.requiredEscapeBlocks) {
            EscapeMaterialTarget target = this.findEscapeMaterialTarget(serverLevel);
            if (target != null) {
                this.mode = EscapeMode.GATHER_BLOCKS;
                this.minePos = target.targetPos();
                this.mineStandPos = target.standPos();
                return true;
            }
        }

        if (escapeBlocks <= 0) {
            return false;
        }

        this.mode = EscapeMode.PILLAR;
        this.maxPillarBlocks = routeNeedsClimb
                ? Math.min(escapeBlocks, Math.max(1, Math.min(this.requiredEscapeBlocks, pillarPlan.blocksNeeded())))
                : 1;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.mode == EscapeMode.NONE
                || this.finished
                || this.goalTicks >= MAX_GOAL_TICKS
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || !(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        if (this.mode == EscapeMode.NAVIGATE_ROUTE) {
            return this.routeNavigationTarget != null
                    && this.failedPathTicks < ROUTE_NAV_MAX_FAILED_TICKS
                    && !this.isRouteNavigationComplete(serverLevel, this.playerNpc.blockPosition());
        }
        if (this.mode == EscapeMode.GATHER_BLOCKS) {
            return this.countEscapeBlocks() < this.requiredEscapeBlocks
                    && (this.minePos != null || this.findEscapeMaterialTarget(serverLevel) != null);
        }
        if (this.mode == EscapeMode.CLEAR_EXIT) {
            return this.exitClearPos != null || this.findExitClearTarget(serverLevel, this.playerNpc.blockPosition(), this.climbTargetPos) != null;
        }

        if (this.mode == EscapeMode.PILLAR && this.pillarClearPos != null) {
            return true;
        }

        if (this.mode == EscapeMode.PILLAR && this.placePos != null) {
            return true;
        }

        if (this.mode == EscapeMode.PILLAR
                && this.climbTargetPos != null
                && this.shouldStopOpenSkyRouteClimb(serverLevel, this.playerNpc.blockPosition())) {
            return false;
        }

        if (this.mode == EscapeMode.PILLAR && this.hasUnfinishedRequestedRoute(serverLevel)) {
            return true;
        }

        return this.mode == EscapeMode.PILLAR
                && this.pillarsPlaced < this.maxPillarBlocks
                && this.countEscapeBlocks() > 0;
    }

    @Override
    public void start() {
        this.goalTicks = 0;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.pillarsPlaced = 0;
        this.failedPillarPlaceAttempts = 0;
        this.finished = false;
        this.previousMainHand = ItemStack.EMPTY;
        this.previousPillarMainHand = ItemStack.EMPTY;
        this.usingTemporaryPickaxe = false;
        this.usingTemporaryBlock = false;
        this.playerNpc.setCurrentAiState(AI_STATE);
        this.playerNpc.setHoleEscapeCooldown(COOLDOWN_TICKS);

        if (this.mode == EscapeMode.GATHER_BLOCKS) {
            if (this.minePos != null && this.playerNpc.level() instanceof ServerLevel serverLevel) {
                this.equipPreferredToolForPillarClear(serverLevel.getBlockState(this.minePos));
            }
            this.moveToMineTarget();
        } else if (this.mode == EscapeMode.NAVIGATE_ROUTE) {
            this.repathTicks = 0;
            this.failedPathTicks = 0;
            this.moveToRouteNavigationTarget();
            this.updateRouteNavigationDetail();
        } else if (this.mode == EscapeMode.PILLAR && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.beginPillarStep(serverLevel);
        } else if (this.mode == EscapeMode.CLEAR_EXIT && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.playerNpc.getNavigation().stop();
            if (this.exitClearPos == null) {
                this.exitClearPos = this.findExitClearTarget(serverLevel, this.playerNpc.blockPosition(), this.climbTargetPos);
            }
            if (this.exitClearPos != null) {
                this.updateExitClearDetail(serverLevel.getBlockState(this.exitClearPos));
            }
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.goalTicks++;
        if (this.mode == EscapeMode.NAVIGATE_ROUTE) {
            this.tickRouteNavigation(serverLevel);
        } else if (this.mode == EscapeMode.GATHER_BLOCKS) {
            this.tickGatherBlocks(serverLevel);
        } else if (this.mode == EscapeMode.CLEAR_EXIT) {
            this.tickClearExit(serverLevel);
        } else if (this.mode == EscapeMode.PILLAR) {
            this.tickPillar(serverLevel);
        }
    }

    @Override
    public void stop() {
        this.playerNpc.clearBlockBreakProgress(this.minePos);
        this.playerNpc.clearBlockBreakProgress(this.pillarClearPos);
        this.playerNpc.clearBlockBreakProgress(this.exitClearPos);
        this.restorePreviousMainHand();
        this.restorePreviousPillarMainHand();
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setHoleEscapeCooldown(COOLDOWN_TICKS);
        }
        if (this.shouldClearUpwardEscapeTargetOnStop()) {
            this.playerNpc.clearUpwardEscapeTarget();
        }
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.resetPlan();
    }

    private boolean shouldClearUpwardEscapeTargetOnStop() {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        if (requestedTarget == null) {
            return true;
        }
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        return this.hasReachedRequestedRoute(serverLevel, this.playerNpc.blockPosition(), requestedTarget);
    }

    private void tickRouteNavigation(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.isRouteNavigationComplete(serverLevel, feet)) {
            if (this.hasReachedOpenSky(serverLevel, feet)) {
                this.playerNpc.clearUpwardEscapeTarget();
            }
            this.finished = true;
            return;
        }

        if (this.routeNavigationTarget != null && this.isSameOrNearbyRouteBlock(feet, this.routeNavigationTarget)) {
            this.routeNavigationTarget = null;
            this.repathTicks = 0;
        }

        if (this.routeNavigationTarget == null
                || this.routeNavigationTarget.distSqr(feet) <= ROUTE_NAV_REACHED_SQR) {
            BlockPos nextTarget = this.findReachableRouteNavigationTarget(serverLevel, feet, this.climbTargetPos);
            if (nextTarget == null) {
                this.finished = true;
                return;
            }
            this.routeNavigationTarget = nextTarget;
            this.repathTicks = 0;
        }

        this.updateRouteNavigationDetail();
        if (this.playerNpc.getNavigation().isStuck()) {
            this.failedPathTicks += ROUTE_NAV_REPATH_TICKS;
        }

        if (this.repathTicks-- <= 0
                || this.playerNpc.getNavigation().isDone()
                || this.playerNpc.getNavigation().isStuck()) {
            if (this.moveToRouteNavigationTarget()) {
                this.failedPathTicks = 0;
            } else {
                this.failedPathTicks += ROUTE_NAV_REPATH_TICKS;
            }
            this.repathTicks = ROUTE_NAV_REPATH_TICKS;
        }

        if (this.failedPathTicks >= ROUTE_NAV_MAX_FAILED_TICKS) {
            this.finished = true;
        }
    }

    private void tickGatherBlocks(ServerLevel serverLevel) {
        if (this.countEscapeBlocks() >= this.requiredEscapeBlocks) {
            this.switchToPillar(serverLevel);
            return;
        }

        if (this.minePos == null) {
            EscapeMaterialTarget nextTarget = this.findEscapeMaterialTarget(serverLevel);
            if (nextTarget == null) {
                if (this.countEscapeBlocks() > 0) {
                    this.switchToPillar(serverLevel);
                } else {
                    this.finished = true;
                }
                return;
            }
            this.minePos = nextTarget.targetPos();
            this.mineStandPos = nextTarget.standPos();
            this.mineTicks = 0;
            this.moveToMineTarget();
        }

        BlockState state = serverLevel.getBlockState(this.minePos);
        if (!this.canGatherEscapeMaterial(state)) {
            this.playerNpc.clearBlockBreakProgress(this.minePos);
            this.minePos = null;
            return;
        }
        if (!this.equipPreferredToolForPillarClear(state)) {
            this.playerNpc.clearBlockBreakProgress(this.minePos);
            this.minePos = null;
            this.mineTicks = 0;
            return;
        }

        this.updateGatherDetail(state);
        this.playerNpc.getLookControl().setLookAt(
                this.minePos.getX() + 0.5D,
                this.minePos.getY() + 0.5D,
                this.minePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (this.playerNpc.distanceToSqr(this.minePos.getX() + 0.5D, this.minePos.getY() + 0.5D, this.minePos.getZ() + 0.5D) > BREAK_DISTANCE_SQR) {
            this.playerNpc.clearBlockBreakProgress(this.minePos);
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                if (this.moveToMineTarget()) {
                    this.failedPathTicks = 0;
                } else {
                    this.failedPathTicks += REPATH_INTERVAL_TICKS;
                    if (this.failedPathTicks >= MAX_FAILED_PATH_TICKS) {
                        this.minePos = null;
                        this.failedPathTicks = 0;
                    }
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.mineTicks % 8 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, this.minePos, state, this.playerNpc);
        }

        this.mineTicks++;
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, state);
        this.playerNpc.showBlockBreakProgress(this.minePos, this.mineTicks, requiredMineTicks);
        if (this.mineTicks < requiredMineTicks) {
            return;
        }

        BlockPos minedPos = this.minePos;
        ItemStack escapeDrop = this.escapeDropFor(state);
        if (serverLevel.destroyBlock(minedPos, false, this.playerNpc)) {
            this.playerNpc.hurtMainHandItem(1);
            if (!InventoryUtils.addItem(this.playerNpc, escapeDrop)) {
                this.playerNpc.spawnAtLocation(escapeDrop);
            }
        }
        this.playerNpc.clearBlockBreakProgress(minedPos);
        this.minePos = null;
        this.mineStandPos = null;
        this.mineTicks = 0;
    }

    private void tickClearExit(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (this.climbTargetPos == null && !this.isActuallyTrapped(serverLevel, feet)) {
            this.finished = true;
            return;
        }

        if (this.exitClearPos == null || !this.isClearableExitObstruction(serverLevel, this.exitClearPos, serverLevel.getBlockState(this.exitClearPos))) {
            this.playerNpc.clearBlockBreakProgress(this.exitClearPos);
            this.exitClearPos = this.findExitClearTarget(serverLevel, feet, this.climbTargetPos);
            this.mineTicks = 0;
            if (this.exitClearPos == null) {
                this.finished = true;
                return;
            }
        }

        BlockState state = serverLevel.getBlockState(this.exitClearPos);
        if (!this.equipPreferredToolForPillarClear(state)) {
            this.playerNpc.clearBlockBreakProgress(this.exitClearPos);
            this.exitClearPos = null;
            this.mineTicks = 0;
            return;
        }
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.exitClearPos.getX() + 0.5D,
                this.exitClearPos.getY() + 0.5D,
                this.exitClearPos.getZ() + 0.5D,
                60.0F,
                60.0F
        );

        if (this.mineTicks % 8 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, this.exitClearPos, state, this.playerNpc);
        }

        this.mineTicks++;
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, this.exitClearPos, state);
        this.playerNpc.showBlockBreakProgress(this.exitClearPos, this.mineTicks, requiredMineTicks);
        this.updateExitClearDetail(state);
        if (this.mineTicks < requiredMineTicks) {
            return;
        }

        BlockPos clearedPos = this.exitClearPos;
        if (PlayerNpcBlockBreakUtil.destroyBlock(serverLevel, clearedPos, state, this.playerNpc)) {
            this.playerNpc.hurtMainHandItem(1);
        }
        this.playerNpc.clearBlockBreakProgress(clearedPos);
        this.exitClearPos = null;
        this.mineTicks = 0;
    }

    private void switchToPillar(ServerLevel serverLevel) {
        this.playerNpc.clearBlockBreakProgress(this.minePos);
        this.restorePreviousMainHand();
        this.restorePreviousPillarMainHand();
        int escapeBlocks = this.countEscapeBlocks();
        if (escapeBlocks <= 0) {
            this.finished = true;
            return;
        }

        PillarPlan pillarPlan = this.findPillarPlan(serverLevel, this.playerNpc.blockPosition(), this.climbTargetPos);
        if (pillarPlan == null) {
            this.finished = true;
            return;
        }
        if (this.exceedsRequestedRouteMax(pillarPlan)) {
            this.playerNpc.clearUpwardEscapeTarget();
            this.finished = true;
            return;
        }
        this.pillarBasePos = pillarPlan.basePos();
        this.pillarExitY = pillarPlan.exitY();
        this.mode = EscapeMode.PILLAR;
        this.minePos = null;
        this.mineStandPos = null;
        this.mineTicks = 0;
        this.maxPillarBlocks = Math.min(escapeBlocks, Math.max(1, Math.min(this.requiredEscapeBlocks, pillarPlan.blocksNeeded())));
        this.pillarsPlaced = 0;
        this.beginPillarStep(serverLevel);
    }

    private void tickPillar(ServerLevel serverLevel) {
        if (this.pillarClearPos != null) {
            this.tickPillarClearance(serverLevel);
            return;
        }

        if (this.placePos == null) {
            if (!this.shouldContinuePillaring(serverLevel)) {
                if (this.tryContinueRequestedRoutePillar(serverLevel)) {
                    return;
                }
                this.finished = true;
                return;
            }
            if (!this.isAtPillarBase()) {
                this.moveToPillarBase();
                return;
            }
            if (this.playerNpc.onGround()) {
                this.beginPillarStep(serverLevel);
            }
            return;
        }

        if (this.placeDelayTicks > 0) {
            this.placeDelayTicks--;
            return;
        }

        this.placeWaitTicks++;
        if (this.placeWaitTicks > MAX_PLACE_WAIT_TICKS) {
            BlockPos obstruction = this.findPillarObstruction(serverLevel, this.playerNpc.blockPosition());
            if (obstruction != null) {
                this.startPillarClearance(serverLevel, obstruction);
                return;
            }
            if (!this.playerNpc.onGround()) {
                this.placeWaitTicks = 0;
                this.lookDownAt(this.placePos);
                return;
            }
            this.placeWaitTicks = 0;
            this.failedPillarPlaceAttempts++;
            if (this.failedPillarPlaceAttempts >= 3) {
                this.finished = true;
                return;
            }
            this.lookDownAt(this.placePos);
            this.playerNpc.shortPillarJump();
            return;
        }

        if (!this.hasPillarPlacementClearance()) {
            this.lookDownAt(this.placePos);
            return;
        }

        if (!serverLevel.getBlockState(this.placePos).canBeReplaced()) {
            this.placePos = null;
            return;
        }

        if (!this.equipEscapeBlockForPlacement()) {
            this.finished = true;
            return;
        }

        ItemStack blockStack = this.playerNpc.getMainHandItem();
        if (blockStack.isEmpty() || !(blockStack.getItem() instanceof BlockItem blockItem)) {
            this.finished = true;
            return;
        }

        this.lookDownAt(this.placePos);
        if (!this.placingBlockAi.placeHeldBlock(serverLevel, this.placePos, blockItem.getBlock().defaultBlockState())) {
            this.finished = true;
            return;
        }
        this.snapAbovePillarIfNeeded(this.placePos);
        this.pillarsPlaced++;
        this.failedPillarPlaceAttempts = 0;
        this.placePos = null;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.updatePillarDetail();
    }

    private boolean tryContinueRequestedRoutePillar(ServerLevel serverLevel) {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        if (requestedTarget == null) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.hasSatisfiedRequestedRoute(serverLevel, feet, requestedTarget)) {
            this.playerNpc.clearUpwardEscapeTarget();
            return false;
        }

        if (!this.playerNpc.onGround()) {
            return true;
        }

        PillarPlan pillarPlan = this.findPillarPlan(serverLevel, feet, requestedTarget);
        if (pillarPlan == null) {
            return false;
        }
        if (this.exceedsRequestedRouteMax(pillarPlan)) {
            this.playerNpc.clearUpwardEscapeTarget();
            return false;
        }

        this.climbTargetPos = requestedTarget.immutable();
        this.pillarBasePos = pillarPlan.basePos();
        this.pillarExitY = pillarPlan.exitY();
        this.requiredEscapeBlocks = Math.max(1, pillarPlan.blocksNeeded());

        int escapeBlocks = this.countEscapeBlocks();
        if (escapeBlocks < this.requiredEscapeBlocks) {
            EscapeMaterialTarget target = this.findEscapeMaterialTarget(serverLevel);
            if (target != null) {
                this.mode = EscapeMode.GATHER_BLOCKS;
                this.minePos = target.targetPos();
                this.mineStandPos = target.standPos();
                this.pillarsPlaced = 0;
                this.maxPillarBlocks = 0;
                this.moveToMineTarget();
                return true;
            }
        }
        if (escapeBlocks <= 0) {
            return false;
        }

        this.maxPillarBlocks = Math.min(escapeBlocks, Math.max(1, Math.min(this.requiredEscapeBlocks, pillarPlan.blocksNeeded())));
        this.pillarsPlaced = 0;
        this.beginPillarStep(serverLevel);
        return true;
    }

    private void beginPillarStep(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (!this.playerNpc.onGround()) {
            return;
        }
        if (!this.isAtPillarBase()) {
            this.moveToPillarBase();
            return;
        }

        BlockPos obstruction = this.findPillarObstruction(serverLevel, feet);
        if (obstruction != null) {
            this.startPillarClearance(serverLevel, obstruction);
            return;
        }

        if (!this.canPillarFrom(serverLevel, feet)) {
            PillarPlan newPlan = this.findPillarPlan(serverLevel, feet, this.climbTargetPos);
            if (newPlan == null) {
                this.finished = true;
                return;
            }
            if (this.exceedsRequestedRouteMax(newPlan)) {
                this.playerNpc.clearUpwardEscapeTarget();
                this.finished = true;
                return;
            }
            this.pillarBasePos = newPlan.basePos();
            this.pillarExitY = newPlan.exitY();
            this.maxPillarBlocks = Math.min(this.countEscapeBlocks(), Math.max(1, Math.min(this.requiredEscapeBlocks, newPlan.blocksNeeded())));
            this.moveToPillarBase();
            return;
        }

        if (!this.equipEscapeBlockForPlacement()) {
            this.finished = true;
            return;
        }

        this.placePos = feet.immutable();
        this.placeDelayTicks = PLACE_DELAY_TICKS;
        this.placeWaitTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.lookDownAt(this.placePos);
        this.playerNpc.shortPillarJump();
        this.updatePillarDetail();
    }

    private void tickPillarClearance(ServerLevel serverLevel) {
        if (this.pillarClearPos == null) {
            return;
        }

        BlockState state = serverLevel.getBlockState(this.pillarClearPos);
        if (!this.isClearablePillarObstruction(serverLevel, this.pillarClearPos, state)) {
            this.clearPillarClearance();
            this.beginPillarStep(serverLevel);
            return;
        }

        if (this.shouldSkipStalePillarClearTarget()) {
            this.clearPillarClearance();
            this.pillarBasePos = this.playerNpc.blockPosition().immutable();
            this.beginPillarStep(serverLevel);
            return;
        }

        if (!this.isAtPillarBase()) {
            this.playerNpc.clearBlockBreakProgress(this.pillarClearPos);
            this.updatePillarClearDetail(state);
            this.moveToPillarBase();
            return;
        }

        this.restorePreviousPillarMainHand();
        if (!this.equipPreferredToolForPillarClear(state)) {
            this.clearPillarClearance();
            this.beginPillarStep(serverLevel);
            return;
        }
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.pillarClearPos.getX() + 0.5D,
                this.pillarClearPos.getY() + 0.5D,
                this.pillarClearPos.getZ() + 0.5D,
                60.0F,
                60.0F
        );

        if (this.mineTicks % 8 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, this.pillarClearPos, state, this.playerNpc);
        }

        this.mineTicks++;
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, this.pillarClearPos, state);
        this.playerNpc.showBlockBreakProgress(this.pillarClearPos, this.mineTicks, requiredMineTicks);
        this.updatePillarClearDetail(state);
        if (this.mineTicks < requiredMineTicks) {
            return;
        }

        BlockPos clearedPos = this.pillarClearPos;
        if (PlayerNpcBlockBreakUtil.destroyBlock(serverLevel, clearedPos, state, this.playerNpc)) {
            this.playerNpc.hurtMainHandItem(1);
        }
        this.playerNpc.clearBlockBreakProgress(clearedPos);
        this.pillarClearPos = null;
        this.mineTicks = 0;
        this.restorePreviousPillarMainHand();
        this.beginPillarStep(serverLevel);
    }

    private boolean shouldSkipStalePillarClearTarget() {
        if (this.pillarClearPos == null) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.pillarClearPos.getY() <= feet.getY()) {
            return true;
        }

        return this.pillarBasePos != null
                && this.pillarClearPos.getX() == this.pillarBasePos.getX()
                && this.pillarClearPos.getZ() == this.pillarBasePos.getZ()
                && this.pillarClearPos.getY() <= this.pillarBasePos.getY();
    }

    private void clearPillarClearance() {
        this.playerNpc.clearBlockBreakProgress(this.pillarClearPos);
        this.pillarClearPos = null;
        this.mineTicks = 0;
        this.restorePreviousPillarMainHand();
    }

    private void startPillarClearance(ServerLevel serverLevel, BlockPos obstruction) {
        if (this.pillarClearPos != null && !this.pillarClearPos.equals(obstruction)) {
            this.playerNpc.clearBlockBreakProgress(this.pillarClearPos);
        }

        this.placePos = null;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.pillarClearPos = obstruction.immutable();
        this.mineTicks = 0;
        this.restorePreviousPillarMainHand();
        this.playerNpc.getNavigation().stop();
        this.updatePillarClearDetail(serverLevel.getBlockState(this.pillarClearPos));
    }

    private void snapAbovePillarIfNeeded(BlockPos pos) {
        double topY = pos.getY() + 1.0D;
        if (this.playerNpc.getBoundingBox().minY >= topY) {
            return;
        }

        this.playerNpc.setPos(this.playerNpc.getX(), topY, this.playerNpc.getZ());
        this.playerNpc.setDeltaMovement(this.playerNpc.getDeltaMovement().x, Math.max(0.0D, this.playerNpc.getDeltaMovement().y), this.playerNpc.getDeltaMovement().z);
    }

    private boolean hasPillarPlacementClearance() {
        if (this.placePos == null) {
            return false;
        }

        double clearedY = this.playerNpc.getBoundingBox().minY - this.placePos.getY();
        if (clearedY >= PLACE_CLEARANCE_Y) {
            return true;
        }

        return this.placeWaitTicks >= 6
                && clearedY >= FALLBACK_PLACE_CLEARANCE_Y
                && this.playerNpc.getDeltaMovement().y <= 0.05D;
    }

    private boolean shouldContinuePillaring(ServerLevel serverLevel) {
        if (this.pillarsPlaced >= this.maxPillarBlocks || this.countEscapeBlocks() <= 0) {
            return false;
        }

        if (this.climbTargetPos == null) {
            return this.pillarsPlaced == 0;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.isRequestedSurfaceRoute(serverLevel, feet, this.climbTargetPos)) {
            return !this.hasReachedRequestedRoute(serverLevel, feet, this.climbTargetPos);
        }

        if (this.hasReachedOpenSky(serverLevel, feet) && !this.isActuallyTrapped(serverLevel, feet)) {
            return false;
        }

        return feet.getY() < this.climbTargetPos.getY() - 1
                && !this.hasStepExitToward(serverLevel, feet, this.climbTargetPos);
    }

    private boolean hasUnfinishedRequestedRoute(ServerLevel serverLevel) {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        return requestedTarget != null
                && !this.hasSatisfiedRequestedRoute(serverLevel, this.playerNpc.blockPosition(), requestedTarget);
    }

    private boolean moveToMineTarget() {
        if (this.mineStandPos == null) {
            return false;
        }

        return this.playerNpc.getNavigation().moveTo(this.mineStandPos.getX() + 0.5D, this.mineStandPos.getY(), this.mineStandPos.getZ() + 0.5D, 1.0D);
    }

    private boolean moveToRouteNavigationTarget() {
        if (this.routeNavigationTarget == null) {
            return false;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.routeNavigationTarget.getX() + 0.5D,
                this.routeNavigationTarget.getY() + 0.5D,
                this.routeNavigationTarget.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        return this.playerNpc.getNavigation().moveTo(
                this.routeNavigationTarget.getX() + 0.5D,
                this.routeNavigationTarget.getY(),
                this.routeNavigationTarget.getZ() + 0.5D,
                1.0D
        );
    }

    private boolean isAtPillarBase() {
        if (this.pillarBasePos == null) {
            return true;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        return feet.getY() >= this.pillarBasePos.getY()
                && feet.getX() == this.pillarBasePos.getX()
                && feet.getZ() == this.pillarBasePos.getZ()
                && this.playerNpc.distanceToSqr(
                this.pillarBasePos.getX() + 0.5D,
                feet.getY(),
                this.pillarBasePos.getZ() + 0.5D
        ) <= PILLAR_BASE_REACHED_SQR;
    }

    private boolean moveToPillarBase() {
        if (this.pillarBasePos == null) {
            return false;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.pillarBasePos.getX() + 0.5D,
                this.pillarBasePos.getY(),
                this.pillarBasePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        return this.playerNpc.getNavigation().moveTo(
                this.pillarBasePos.getX() + 0.5D,
                this.pillarBasePos.getY(),
                this.pillarBasePos.getZ() + 0.5D,
                1.0D
        );
    }

    private PillarPlan findPillarPlan(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        PillarPlan directPlan = this.findDirectPillarPlan(serverLevel, feet, routeTarget);
        if (directPlan != null) {
            return directPlan;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-PILLAR_SEARCH_RADIUS, -1, -PILLAR_SEARCH_RADIUS),
                feet.offset(PILLAR_SEARCH_RADIUS, 2, PILLAR_SEARCH_RADIUS))) {
            if (!pos.equals(feet)) {
                candidates.add(pos.immutable());
            }
        }

        candidates.sort(Comparator
                .comparingDouble(feet::distSqr)
                .thenComparingDouble(pos -> routeTarget == null ? 0.0D : routeTarget.distSqr(pos)));
        for (BlockPos base : candidates) {
            PillarPlan plan = this.createPillarPlan(serverLevel, base, routeTarget);
            if (plan != null) {
                return plan;
            }
        }

        return null;
    }

    private PillarPlan findDirectPillarPlan(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        PillarPlan plan = this.createPillarPlan(serverLevel, feet.immutable(), routeTarget);
        if (plan != null) {
            return plan;
        }

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            plan = this.createPillarPlan(serverLevel, feet.relative(direction), routeTarget);
            if (plan != null) {
                return plan;
            }
        }
        return null;
    }

    private PillarPlan createPillarPlan(ServerLevel serverLevel, BlockPos base, BlockPos routeTarget) {
        if (!this.canStandAt(serverLevel, base) || !serverLevel.getBlockState(base).canBeReplaced()) {
            return null;
        }
        if (routeTarget == null) {
            return this.hasOpenOrClearableBodySpace(serverLevel, base.above())
                    ? new PillarPlan(base, base.getY() + 1, 1)
                    : null;
        }

        boolean forcedRequestedRoute = this.isForcedRequestedRoute(routeTarget);
        boolean requestedSurfaceRoute = !forcedRequestedRoute
                && this.isRequestedSurfaceRoute(serverLevel, this.playerNpc.blockPosition(), routeTarget);
        int requestedSurfaceY = requestedSurfaceRoute ? this.nearbySurfaceY(serverLevel, base) : base.getY() + 1;
        int scanTop = Math.min(serverLevel.getMaxBuildHeight() - 3, base.getY() + PILLAR_SURFACE_SCAN_UP);
        for (int y = base.getY(); y <= scanTop; y++) {
            BlockPos feetAtY = new BlockPos(base.getX(), y, base.getZ());
            if (!this.hasOpenOrClearableBodySpace(serverLevel, feetAtY)) {
                return null;
            }

            boolean reachesRoute = !requestedSurfaceRoute
                    && routeTarget != null
                    && y >= routeTarget.getY() - 1
                    && (forcedRequestedRoute || this.hasStepExitToward(serverLevel, feetAtY, routeTarget));
            boolean reachesSurface = serverLevel.canSeeSky(feetAtY.above())
                    && (requestedSurfaceRoute
                    ? y >= requestedSurfaceY
                    : y >= Math.min(routeTarget.getY() - 1, base.getY() + MIN_ROUTE_ESCAPE_BLOCKS));
            if (reachesRoute || reachesSurface) {
                int blocksNeeded = Math.max(1, y - base.getY());
                if (blocksNeeded <= MAX_ROUTE_ESCAPE_BLOCKS || routeTarget == null) {
                    return new PillarPlan(base, y, blocksNeeded);
                }
                return null;
            }
        }

        return null;
    }

    private boolean canPillarFrom(ServerLevel serverLevel, BlockPos feet) {
        if (!serverLevel.getBlockState(feet).canBeReplaced() || !this.hasOpenBodySpace(serverLevel, feet)) {
            return false;
        }

        int topY = this.pillarExitY > feet.getY()
                ? Math.min(this.pillarExitY, feet.getY() + 1)
                : feet.getY() + 1;
        for (int y = feet.getY(); y <= topY; y++) {
            if (!this.hasOpenBodySpace(serverLevel, new BlockPos(feet.getX(), y, feet.getZ()))) {
                return false;
            }
        }
        return true;
    }

    private BlockPos findPillarObstruction(ServerLevel serverLevel, BlockPos feet) {
        int topY = this.pillarExitY > feet.getY()
                ? Math.min(this.pillarExitY, feet.getY() + 1)
                : feet.getY() + 1;
        for (int y = feet.getY() + 1; y <= topY + 1; y++) {
            BlockPos pos = new BlockPos(feet.getX(), y, feet.getZ());
            BlockState state = serverLevel.getBlockState(pos);
            if (this.isClearablePillarObstruction(serverLevel, pos, state)) {
                return pos.immutable();
            }
            if (this.hasBlockingCollision(serverLevel, pos)) {
                return null;
            }
        }
        return null;
    }

    private BlockPos findExitClearTarget(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        BlockPos currentColumnTarget = this.findCurrentColumnClearTarget(serverLevel, feet);
        if (currentColumnTarget != null) {
            return currentColumnTarget;
        }
        if (routeTarget != null && !this.isActuallyTrapped(serverLevel, feet)) {
            return null;
        }

        List<Direction> directions = routeTarget == null ? new ArrayList<>() : this.directionsToward(feet, routeTarget);
        if (routeTarget == null) {
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                directions.add(direction);
            }
        }

        for (Direction direction : directions) {
            BlockPos adjacentFeet = feet.relative(direction);
            if (!serverLevel.isInWorldBounds(adjacentFeet)
                    || !serverLevel.getWorldBorder().isWithinBounds(adjacentFeet)
                    || !this.isWalkableFloor(serverLevel, adjacentFeet.below())
                    || this.hasOpenBodySpace(serverLevel, adjacentFeet)) {
                continue;
            }

            BlockPos lower = adjacentFeet.immutable();
            BlockState lowerState = serverLevel.getBlockState(lower);
            if (this.hasBlockingCollision(serverLevel, lower)) {
                if (this.isClearableExitObstruction(serverLevel, lower, lowerState)) {
                    return lower;
                }
                continue;
            }

            BlockPos upper = adjacentFeet.above();
            BlockState upperState = serverLevel.getBlockState(upper);
            if (this.hasBlockingCollision(serverLevel, upper)
                    && this.isClearableExitObstruction(serverLevel, upper, upperState)) {
                return upper.immutable();
            }
        }

        return null;
    }

    private BlockPos findCurrentColumnClearTarget(ServerLevel serverLevel, BlockPos feet) {
        BlockState feetState = serverLevel.getBlockState(feet);
        if (this.hasBlockingCollision(serverLevel, feet)
                && this.isClearableExitObstruction(serverLevel, feet, feetState)) {
            return feet.immutable();
        }

        BlockPos head = feet.above();
        BlockState headState = serverLevel.getBlockState(head);
        if (this.hasBlockingCollision(serverLevel, head)
                && this.isClearableExitObstruction(serverLevel, head, headState)) {
            return head.immutable();
        }

        return null;
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

    private EscapeMaterialTarget findEscapeMaterialTarget(ServerLevel serverLevel) {
        List<EscapeMaterialTarget> candidates = new ArrayList<>();
        BlockPos center = this.playerNpc.blockPosition();

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-SEARCH_RADIUS, -2, -SEARCH_RADIUS), center.offset(SEARCH_RADIUS, 3, SEARCH_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (immutable.equals(center.below()) || !this.canGatherEscapeMaterial(serverLevel.getBlockState(immutable))) {
                continue;
            }

            BlockPos stand = this.findStandPos(serverLevel, immutable);
            if (stand != null) {
                candidates.add(new EscapeMaterialTarget(immutable, stand));
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator
                .comparingDouble((EscapeMaterialTarget target) -> center.distSqr(target.targetPos()))
                .thenComparingDouble(target -> center.distSqr(target.standPos())));
        return candidates.get(0);
    }

    private BlockPos findStandPos(ServerLevel serverLevel, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        candidates.add(target.above());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(target.relative(direction));
            candidates.add(target.relative(direction).above());
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canStandAt(serverLevel, immutable)
                    && immutable.distSqr(target) <= BREAK_DISTANCE_SQR + 1.0D) {
                return immutable;
            }
        }
        return null;
    }

    private BlockPos findReachableRouteNavigationTarget(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        List<BlockPos> candidates = new ArrayList<>();
        this.addRouteNavigationColumn(serverLevel, candidates, feet, feet, routeTarget);
        if (routeTarget != null) {
            this.addRouteNavigationColumn(serverLevel, candidates, routeTarget, feet, routeTarget);
            for (Direction direction : this.directionsToward(feet, routeTarget)) {
                for (int distance = ROUTE_NAV_STEP; distance <= ROUTE_NAV_HORIZONTAL_RADIUS; distance += ROUTE_NAV_STEP) {
                    this.addRouteNavigationColumn(
                            serverLevel,
                            candidates,
                            feet.relative(direction, distance),
                            feet,
                            routeTarget
                    );
                }
            }
        }

        for (int dx = -ROUTE_NAV_HORIZONTAL_RADIUS; dx <= ROUTE_NAV_HORIZONTAL_RADIUS; dx += ROUTE_NAV_STEP) {
            for (int dz = -ROUTE_NAV_HORIZONTAL_RADIUS; dz <= ROUTE_NAV_HORIZONTAL_RADIUS; dz += ROUTE_NAV_STEP) {
                if (Math.abs(dx) + Math.abs(dz) < ROUTE_NAV_STEP) {
                    continue;
                }
                this.addRouteNavigationColumn(serverLevel, candidates, feet.offset(dx, 0, dz), feet, routeTarget);
            }
        }

        candidates.sort(Comparator
                .comparingInt((BlockPos pos) -> serverLevel.canSeeSky(pos.above()) ? 0 : 1)
                .thenComparingInt(pos -> -pos.getY())
                .thenComparingDouble(pos -> routeTarget == null ? feet.distSqr(pos) : routeTarget.distSqr(pos))
                .thenComparingDouble(feet::distSqr));

        Set<BlockPos> seen = new HashSet<>();
        int checked = 0;
        for (BlockPos candidate : candidates) {
            if (!seen.add(candidate) || candidate.distSqr(feet) <= ROUTE_NAV_REACHED_SQR) {
                continue;
            }
            Path path = this.playerNpc.getNavigation().createPath(candidate, 0);
            checked++;
            if (path != null && path.canReach()) {
                return candidate;
            }
            if (checked >= ROUTE_NAV_MAX_PATH_CHECKS) {
                break;
            }
        }
        return null;
    }

    private void addRouteNavigationColumn(ServerLevel serverLevel, List<BlockPos> candidates, BlockPos column, BlockPos feet, BlockPos routeTarget) {
        int minY = Math.max(serverLevel.getMinBuildHeight() + 1, feet.getY() - ROUTE_NAV_VERTICAL_DOWN);
        int maxY = Math.min(serverLevel.getMaxBuildHeight() - 2, feet.getY() + ROUTE_NAV_VERTICAL_UP);

        for (int y = maxY; y >= minY; y--) {
            BlockPos candidate = new BlockPos(column.getX(), y, column.getZ());
            if (this.isRouteNavigationCandidate(serverLevel, feet, candidate)) {
                candidates.add(candidate.immutable());
                return;
            }
        }
    }

    private boolean isRouteNavigationCandidate(ServerLevel serverLevel, BlockPos feet, BlockPos candidate) {
        if (!serverLevel.isInWorldBounds(candidate)
                || !serverLevel.getWorldBorder().isWithinBounds(candidate)
                || !this.canStandAt(serverLevel, candidate)) {
            return false;
        }

        return candidate.getY() >= feet.getY() + ROUTE_NAV_MIN_UPWARD_GAIN
                || serverLevel.canSeeSky(candidate.above());
    }

    private boolean isRouteNavigationComplete(ServerLevel serverLevel, BlockPos feet) {
        if (this.hasReachedOpenSky(serverLevel, feet) && !this.isActuallyTrapped(serverLevel, feet)) {
            if (this.climbTargetPos != null && this.isRequestedSurfaceRoute(serverLevel, feet, this.climbTargetPos)) {
                return this.hasReachedRequestedRoute(serverLevel, feet, this.climbTargetPos);
            }
            return true;
        }

        if (this.climbTargetPos != null && this.isRequestedSurfaceRoute(serverLevel, feet, this.climbTargetPos)) {
            return this.hasReachedRequestedRoute(serverLevel, feet, this.climbTargetPos);
        }

        return this.climbTargetPos != null
                && (feet.getY() >= this.climbTargetPos.getY() - 1
                || this.hasStepExitToward(serverLevel, feet, this.climbTargetPos));
    }

    private boolean routeNeedsClimb(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        if (routeTarget == null) {
            return false;
        }
        if (this.isSameOrNearbyRouteBlock(feet, routeTarget)) {
            return false;
        }

        boolean targetIsHigher = routeTarget.getY() > feet.getY() + 2;
        boolean requestedSurfaceRoute = this.isRequestedSurfaceRoute(serverLevel, feet, routeTarget);
        if (!targetIsHigher && !requestedSurfaceRoute) {
            return false;
        }

        Path path = this.playerNpc.getNavigation().createPath(routeTarget, 0);
        if (requestedSurfaceRoute && (path == null || !path.canReach())) {
            return true;
        }

        if (targetIsHigher && this.hasStepExitToward(serverLevel, feet, routeTarget)) {
            return false;
        }

        if (path == null || !path.canReach()) {
            return true;
        }

        if (requestedSurfaceRoute && !targetIsHigher) {
            return this.playerNpc.getNavigation().isStuck()
                    || this.playerNpc.getNavigation().isDone();
        }

        if (this.hasStepExitToward(serverLevel, feet, routeTarget)) {
            return false;
        }

        return this.playerNpc.getNavigation().isStuck()
                || this.playerNpc.getNavigation().isDone()
                || this.hasTallWallToward(serverLevel, feet, routeTarget);
    }

    private BlockPos getUpwardRouteTarget(ServerLevel serverLevel, BlockPos feet) {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        if (requestedTarget != null && this.isUsableUpwardRouteTarget(serverLevel, feet, requestedTarget)) {
            return requestedTarget.immutable();
        }

        BlockPos combatTarget = this.getUnreachableHighCombatTarget(feet);
        if (combatTarget != null && this.isUsableUpwardRouteTarget(serverLevel, feet, combatTarget)) {
            return combatTarget;
        }

        return null;
    }

    private BlockPos getUnreachableHighCombatTarget(BlockPos feet) {
        LivingEntity target = this.playerNpc.getTarget();
        if (target == null || !target.isAlive() || target.isRemoved() || this.playerNpc.isAlliedTo(target)) {
            return null;
        }

        BlockPos targetPos = target.blockPosition();
        if (targetPos.getY() <= feet.getY() + 2 || this.horizontalDistanceSqr(feet, targetPos) > 12.0D * 12.0D) {
            return null;
        }

        Path path = this.playerNpc.getNavigation().createPath(targetPos, 0);
        return path == null || !path.canReach() ? targetPos.immutable() : null;
    }

    private double horizontalDistanceSqr(BlockPos from, BlockPos to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return dx * dx + dz * dz;
    }

    private boolean isUsableUpwardRouteTarget(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        if (this.isSameOrNearbyRouteBlock(feet, routeTarget)) {
            return false;
        }
        return routeTarget.getY() > feet.getY() + 2
                || this.isRequestedSurfaceRoute(serverLevel, feet, routeTarget);
    }

    private boolean isRequestedSurfaceRoute(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        return requestedTarget != null
                && requestedTarget.equals(routeTarget);
    }

    private boolean hasReachedOpenSky(ServerLevel serverLevel, BlockPos feet) {
        return this.hasOpenBodySpace(serverLevel, feet)
                && serverLevel.canSeeSky(feet.above());
    }

    private boolean shouldStopOpenSkyRouteClimb(ServerLevel serverLevel, BlockPos feet) {
        if (!this.hasReachedOpenSky(serverLevel, feet) || this.isActuallyTrapped(serverLevel, feet)) {
            return false;
        }
        if (this.climbTargetPos != null && this.isRequestedSurfaceRoute(serverLevel, feet, this.climbTargetPos)) {
            return this.hasReachedRequestedRoute(serverLevel, feet, this.climbTargetPos);
        }
        return true;
    }

    private boolean hasReachedRequestedRoute(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        if (this.isForcedRequestedRoute(routeTarget)) {
            return this.hasReachedForcedRequestedRoute(serverLevel, feet, routeTarget);
        }
        return this.hasReachedRequestedSurfaceExit(serverLevel, feet, routeTarget);
    }

    private boolean hasReachedForcedRequestedRoute(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        if (feet.getY() < routeTarget.getY() - 1
                || !this.playerNpc.onGround()
                || !this.canStandAt(serverLevel, feet)) {
            return false;
        }

        return this.isSameOrNearbyRouteBlock(feet, routeTarget)
                || this.hasStepExitToward(serverLevel, feet, routeTarget)
                || this.hasReachedOpenSky(serverLevel, feet) && !this.isActuallyTrapped(serverLevel, feet);
    }

    private boolean hasReachedRequestedSurfaceExit(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        int nearbySurfaceY = this.nearbySurfaceY(serverLevel, feet);
        if (feet.getY() < nearbySurfaceY - 1) {
            return false;
        }
        return feet.getY() >= nearbySurfaceY
                || this.hasStepExitToward(serverLevel, feet, routeTarget);
    }

    private boolean hasSatisfiedRequestedRoute(ServerLevel serverLevel, BlockPos feet, BlockPos requestedTarget) {
        if (this.isForcedRequestedRoute(requestedTarget)) {
            return this.hasReachedRequestedRoute(serverLevel, feet, requestedTarget);
        }
        if (requestedTarget.getY() > feet.getY() + 1) {
            return this.hasReachedRequestedSurfaceExit(serverLevel, feet, requestedTarget);
        }
        return this.isSameOrNearbyRouteBlock(feet, requestedTarget)
                || this.hasReachedRequestedSurfaceExit(serverLevel, feet, requestedTarget);
    }

    private boolean isSameOrNearbyRouteBlock(BlockPos feet, BlockPos routeTarget) {
        return routeTarget != null
                && Math.abs(routeTarget.getY() - feet.getY()) <= 1
                && routeTarget.distSqr(feet) <= ROUTE_NAV_REACHED_SQR;
    }

    private boolean isForcedRequestedRoute(BlockPos routeTarget) {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        return routeTarget != null
                && requestedTarget != null
                && requestedTarget.equals(routeTarget)
                && this.playerNpc.isForcedUpwardEscape();
    }

    private int nearbySurfaceY(ServerLevel serverLevel, BlockPos center) {
        int surfaceY = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ());
        return Math.max(center.getY(), surfaceY);
    }

    private boolean hasTallWallToward(ServerLevel serverLevel, BlockPos feet, BlockPos target) {
        int checked = 0;
        for (Direction direction : this.directionsToward(feet, target)) {
            if (this.hasTwoBlockBarrier(serverLevel, feet, direction)) {
                return true;
            }
            checked++;
            if (checked >= 2) {
                break;
            }
        }
        return false;
    }

    private boolean hasStepExitToward(ServerLevel serverLevel, BlockPos feet, BlockPos target) {
        int checked = 0;
        for (Direction direction : this.directionsToward(feet, target)) {
            BlockPos adjacentFeet = feet.relative(direction);
            if (this.hasOpenBodySpace(serverLevel, adjacentFeet)
                    && this.isWalkableFloor(serverLevel, adjacentFeet.below())) {
                return true;
            }

            BlockPos stepUpFeet = adjacentFeet.above();
            if (this.hasOpenBodySpace(serverLevel, stepUpFeet)
                    && this.isWalkableFloor(serverLevel, adjacentFeet)) {
                return true;
            }

            checked++;
            if (checked >= 2) {
                break;
            }
        }
        return false;
    }

    private List<Direction> directionsToward(BlockPos from, BlockPos to) {
        List<Direction> directions = new ArrayList<>();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            directions.add(dx > 0 ? Direction.EAST : Direction.WEST);
        } else if (dz != 0) {
            directions.add(dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }

        if (dx != 0) {
            Direction xDirection = dx > 0 ? Direction.EAST : Direction.WEST;
            if (!directions.contains(xDirection)) {
                directions.add(xDirection);
            }
        }
        if (dz != 0) {
            Direction zDirection = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            if (!directions.contains(zDirection)) {
                directions.add(zDirection);
            }
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!directions.contains(direction)) {
                directions.add(direction);
            }
        }
        return directions;
    }

    private boolean isEscapeBlock(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem) || stack.is(ItemTags.LOGS)) {
            return false;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && !state.canBeReplaced()
                && (this.isDirtPillarBlock(stack)
                        || this.isUsablePlankPillarBlock(stack)
                        || this.isStonePillarBlock(state));
    }

    private boolean isDirtPillarBlock(ItemStack stack) {
        return stack.is(Items.DIRT)
                || stack.is(Items.GRASS_BLOCK)
                || stack.is(Items.COARSE_DIRT)
                || stack.is(Items.ROOTED_DIRT)
                || stack.is(Items.PODZOL);
    }

    private boolean isStonePillarBlock(BlockState state) {
        return state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.BASE_STONE_NETHER)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.MOSSY_COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.TUFF)
                || state.is(Blocks.CALCITE)
                || state.is(Blocks.DRIPSTONE_BLOCK)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.MOSSY_STONE_BRICKS)
                || state.is(Blocks.CRACKED_STONE_BRICKS)
                || state.is(Blocks.CHISELED_STONE_BRICKS)
                || state.is(Blocks.POLISHED_DEEPSLATE)
                || state.is(Blocks.DEEPSLATE_BRICKS)
                || state.is(Blocks.CRACKED_DEEPSLATE_BRICKS)
                || state.is(Blocks.DEEPSLATE_TILES)
                || state.is(Blocks.CRACKED_DEEPSLATE_TILES)
                || state.is(Blocks.BLACKSTONE)
                || state.is(Blocks.POLISHED_BLACKSTONE)
                || state.is(Blocks.POLISHED_BLACKSTONE_BRICKS)
                || state.is(Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS)
                || state.is(Blocks.CHISELED_POLISHED_BLACKSTONE)
                || state.is(Blocks.BASALT)
                || state.is(Blocks.SMOOTH_BASALT)
                || state.is(Blocks.END_STONE)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE);
    }

    private boolean isUsablePlankPillarBlock(ItemStack stack) {
        return stack.is(ItemTags.PLANKS) && !this.shouldPreserveWoodForPillar();
    }

    private boolean shouldPreserveWoodForPillar() {
        return this.playerNpc.shouldPrioritizeLogGathering();
    }

    private boolean canGatherEscapeMaterial(BlockState state) {
        return this.isDirtEscapeMaterial(state)
                || this.isStoneEscapeMaterial(state) && this.hasPickaxe();
    }

    private boolean isEscapeMaterial(BlockState state) {
        return this.isDirtEscapeMaterial(state) || this.isStoneEscapeMaterial(state);
    }

    private boolean isStoneEscapeMaterial(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE);
    }

    private boolean isDirtEscapeMaterial(BlockState state) {
        return state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.ROOTED_DIRT)
                || state.is(Blocks.PODZOL);
    }

    private ItemStack escapeDropFor(BlockState state) {
        if (this.isDirtEscapeMaterial(state)) {
            if (state.is(Blocks.COARSE_DIRT)) {
                return new ItemStack(Items.COARSE_DIRT);
            }
            if (state.is(Blocks.ROOTED_DIRT)) {
                return new ItemStack(Items.ROOTED_DIRT);
            }
            return new ItemStack(Items.DIRT);
        }
        if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE)) {
            return new ItemStack(Items.COBBLED_DEEPSLATE);
        }
        return new ItemStack(Items.COBBLESTONE);
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockState state) {
        return this.getRequiredMineTicks(serverLevel, this.minePos, state);
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(serverLevel, pos);
        if (hardness < 0.0F) {
            return MAX_GOAL_TICKS;
        }

        ItemStack heldStack = this.playerNpc.getMainHandItem();
        float toolSpeed = heldStack.isEmpty() ? 1.0F : heldStack.getDestroySpeed(state);
        if (toolSpeed <= 0.0F) {
            toolSpeed = 1.0F;
        }

        boolean correctTool = !state.requiresCorrectToolForDrops() || heldStack.isCorrectToolForDrops(state);
        float progressPerTick = toolSpeed / hardness / (correctTool ? 30.0F : 100.0F);
        if (progressPerTick <= 0.0F) {
            return MAX_GOAL_TICKS;
        }

        return Math.max(1, (int) Math.ceil(1.0F / progressPerTick));
    }

    private boolean hasPickaxe() {
        return this.playerNpc.getMainHandItem().getItem() instanceof PickaxeItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof PickaxeItem);
    }

    private boolean equipPickaxe() {
        return this.equipTool(PickaxeItem.class);
    }

    private boolean equipTool(Class<?> toolClass) {
        if (toolClass.isInstance(this.playerNpc.getMainHandItem().getItem())) {
            return true;
        }

        ItemStack tool = this.playerNpc.consumeInventoryItem(stack -> toolClass.isInstance(stack.getItem()), 1)
                .orElse(ItemStack.EMPTY);
        if (tool.isEmpty()) {
            return false;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryPickaxe) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryPickaxe = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, tool);
        return true;
    }

    private boolean equipPreferredToolForPillarClear(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SAND)) {
            if (!this.equipTool(ShovelItem.class)) {
                this.equipEmptyHandForMining();
            }
            return true;
        } else if (state.is(BlockTags.MINEABLE_WITH_AXE) || state.is(BlockTags.LOGS) || state.is(Blocks.CRAFTING_TABLE)) {
            return this.equipTool(AxeItem.class);
        } else if (state.is(BlockTags.MINEABLE_WITH_PICKAXE) || state.requiresCorrectToolForDrops()) {
            return this.equipTool(PickaxeItem.class);
        }
        return true;
    }

    private void equipEmptyHandForMining() {
        if (this.playerNpc.getMainHandItem().isEmpty()) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryPickaxe) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryPickaxe = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private boolean equipEscapeBlockForPlacement() {
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (this.isDirtPillarBlock(mainHand)) {
            return true;
        }

        ItemStack preferredBlock = InventoryUtils.consumeItem(this.playerNpc, this::isDirtPillarBlock, 1)
                .orElse(ItemStack.EMPTY);
        if (!preferredBlock.isEmpty()) {
            this.equipTemporaryPillarBlock(preferredBlock);
            return true;
        }

        if (mainHand.getItem() instanceof BlockItem mainBlockItem
                && this.isStonePillarBlock(mainBlockItem.getBlock().defaultBlockState())) {
            return true;
        }

        preferredBlock = InventoryUtils.consumeItem(this.playerNpc, stack -> {
            if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
                return false;
            }
            return this.isStonePillarBlock(blockItem.getBlock().defaultBlockState());
        }, 1).orElse(ItemStack.EMPTY);
        if (!preferredBlock.isEmpty()) {
            this.equipTemporaryPillarBlock(preferredBlock);
            return true;
        }

        if (this.isUsablePlankPillarBlock(mainHand)) {
            return true;
        }

        preferredBlock = InventoryUtils.consumeItem(this.playerNpc, this::isUsablePlankPillarBlock, 1)
                .orElse(ItemStack.EMPTY);
        if (!preferredBlock.isEmpty()) {
            this.equipTemporaryPillarBlock(preferredBlock);
            return true;
        }

        if (!this.shouldPreserveWoodForPillar() && !InventoryUtils.hasItem(this.playerNpc, this::isEscapeBlock)) {
            PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), 0);
            preferredBlock = InventoryUtils.consumeItem(this.playerNpc, this::isUsablePlankPillarBlock, 1)
                    .orElse(ItemStack.EMPTY);
            if (!preferredBlock.isEmpty()) {
                this.equipTemporaryPillarBlock(preferredBlock);
                return true;
            }
        }
        return false;
    }

    private void equipTemporaryPillarBlock(ItemStack block) {
        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryBlock) {
            this.previousPillarMainHand = currentMainHand;
            this.usingTemporaryBlock = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousPillarMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, block);
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryPickaxe) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryPickaxe = false;
    }

    private void restorePreviousPillarMainHand() {
        if (!this.usingTemporaryBlock) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousPillarMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousPillarMainHand.copy());
        this.previousPillarMainHand = ItemStack.EMPTY;
        this.usingTemporaryBlock = false;
    }

    private int countEscapeBlocks() {
        int count = 0;
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (this.isEscapeBlock(mainHand)) {
            count += mainHand.getCount();
        }
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && this.isEscapeBlock(stack)) {
                count += stack.getCount();
            }
        }
        return count + this.countCraftablePillarPlanks();
    }

    private boolean exceedsRequestedRouteMax(PillarPlan plan) {
        int maxBlocks = this.getRequestedRouteMaxPillarBlocks();
        return plan != null && maxBlocks > 0 && plan.blocksNeeded() > maxBlocks;
    }

    private int getRequestedRouteMaxPillarBlocks() {
        BlockPos requestedTarget = this.playerNpc.getUpwardEscapeTarget();
        if (requestedTarget == null
                || this.climbTargetPos == null
                || !requestedTarget.equals(this.climbTargetPos)) {
            return 0;
        }
        return this.playerNpc.getUpwardEscapeMaxPillarBlocks();
    }

    private int countCraftablePillarPlanks() {
        if (this.shouldPreserveWoodForPillar()) {
            return 0;
        }
        return PlayerNpcCraftingUtil.countLogs(this.playerNpc.getInventory()) * 4;
    }

    private boolean hasTwoBlockBarrier(ServerLevel serverLevel, BlockPos feet, Direction direction) {
        BlockPos lower = feet.relative(direction);
        return this.hasBlockingCollision(serverLevel, lower)
                && this.hasBlockingCollision(serverLevel, lower.above());
    }

    private boolean isActuallyTrapped(ServerLevel serverLevel, BlockPos feet) {
        if (this.hasLocalWalkingEscape(serverLevel, feet)) {
            return false;
        }

        int blockedSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (this.hasBlockingCollision(serverLevel, feet.relative(direction))) {
                blockedSides++;
            }
        }

        return blockedSides >= 4;
    }

    private boolean hasLocalWalkingEscape(ServerLevel serverLevel, BlockPos feet) {
        Queue<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        BlockPos start = feet.immutable();
        open.add(start);
        seen.add(start);

        while (!open.isEmpty()) {
            BlockPos pos = open.poll();
            if (!pos.equals(feet) && this.isLocalEscapeStandPos(feet, pos)) {
                return true;
            }

            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos adjacent = pos.relative(direction);
                this.queueReachableStand(serverLevel, feet, adjacent, open, seen);
                this.queueReachableStand(serverLevel, feet, adjacent.above(), open, seen);
                this.queueReachableStand(serverLevel, feet, adjacent.below(), open, seen);
            }
        }

        return false;
    }

    private boolean isLocalEscapeStandPos(BlockPos feet, BlockPos pos) {
        return pos.getY() > feet.getY()
                || feet.distSqr(pos) > 3.0D * 3.0D;
    }

    private void queueReachableStand(ServerLevel serverLevel, BlockPos origin, BlockPos candidate, Queue<BlockPos> open, Set<BlockPos> seen) {
        if (Math.abs(candidate.getX() - origin.getX()) > 4
                || Math.abs(candidate.getZ() - origin.getZ()) > 4
                || candidate.getY() < origin.getY() - 1
                || candidate.getY() > origin.getY() + 2
                || seen.contains(candidate)
                || !this.canStandAt(serverLevel, candidate)) {
            return;
        }

        BlockPos immutable = candidate.immutable();
        seen.add(immutable);
        open.add(immutable);
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && this.hasOpenBodySpace(serverLevel, pos)
                && this.isWalkableFloor(serverLevel, pos.below());
    }

    private boolean hasOpenBodySpace(ServerLevel serverLevel, BlockPos pos) {
        return !this.hasBlockingCollision(serverLevel, pos)
                && !this.hasBlockingCollision(serverLevel, pos.above());
    }

    private boolean hasOpenOrClearableBodySpace(ServerLevel serverLevel, BlockPos pos) {
        return this.isOpenOrClearablePillarSpace(serverLevel, pos)
                && this.isOpenOrClearablePillarSpace(serverLevel, pos.above());
    }

    private boolean isOpenOrClearablePillarSpace(ServerLevel serverLevel, BlockPos pos) {
        return !this.hasBlockingCollision(serverLevel, pos)
                || this.isClearablePillarObstruction(serverLevel, pos, serverLevel.getBlockState(pos));
    }

    private boolean isClearablePillarObstruction(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !state.isAir()
                && !state.getCollisionShape(serverLevel, pos).isEmpty()
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && !CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)
                && serverLevel.getBlockEntity(pos) == null;
    }

    private boolean isClearableExitObstruction(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return this.isClearablePillarObstruction(serverLevel, pos, state);
    }

    private boolean hasBlockingCollision(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        return !state.getCollisionShape(serverLevel, pos).isEmpty();
    }

    private boolean isWalkableFloor(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        return !state.getCollisionShape(serverLevel, pos).isEmpty();
    }

    private void updateGatherDetail(BlockState state) {
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "escape blocks %d/%d mining %s @ %d %d %d",
                this.countEscapeBlocks(),
                this.requiredEscapeBlocks,
                state.getBlock().getDescriptionId(),
                this.minePos.getX(),
                this.minePos.getY(),
                this.minePos.getZ()
        ));
    }

    private void updatePillarDetail() {
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "pillaring %d/%d\ngiving up in %ds",
                this.pillarsPlaced,
                this.maxPillarBlocks,
                this.getRemainingGoalSeconds()
        ));
    }

    private void updateRouteNavigationDetail() {
        if (this.routeNavigationTarget == null) {
            return;
        }

        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "routing cave exit @ %d %d %d\ngiving up in %ds",
                this.routeNavigationTarget.getX(),
                this.routeNavigationTarget.getY(),
                this.routeNavigationTarget.getZ(),
                this.getRemainingGoalSeconds()
        ));
    }

    private void updatePillarClearDetail(BlockState state) {
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "pillaring %d/%d clearing %s @ %d %d %d\ngiving up in %ds",
                this.pillarsPlaced,
                this.maxPillarBlocks,
                state.getBlock().getDescriptionId(),
                this.pillarClearPos.getX(),
                this.pillarClearPos.getY(),
                this.pillarClearPos.getZ(),
                this.getRemainingGoalSeconds()
        ));
    }

    private void updateExitClearDetail(BlockState state) {
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "clearing pillar path %s @ %d %d %d",
                state.getBlock().getDescriptionId(),
                this.exitClearPos.getX(),
                this.exitClearPos.getY(),
                this.exitClearPos.getZ()
        ));
    }

    private void resetPlan() {
        this.mode = EscapeMode.NONE;
        this.placePos = null;
        this.minePos = null;
        this.mineStandPos = null;
        this.routeNavigationTarget = null;
        this.climbTargetPos = null;
        this.pillarBasePos = null;
        this.pillarClearPos = null;
        this.exitClearPos = null;
        this.pillarExitY = 0;
        this.previousMainHand = ItemStack.EMPTY;
        this.previousPillarMainHand = ItemStack.EMPTY;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.goalTicks = 0;
        this.requiredEscapeBlocks = 0;
        this.maxPillarBlocks = 0;
        this.pillarsPlaced = 0;
        this.failedPillarPlaceAttempts = 0;
        this.usingTemporaryPickaxe = false;
        this.usingTemporaryBlock = false;
        this.finished = false;
    }

    private int getRemainingGoalSeconds() {
        int remainingTicks = Math.max(0, MAX_GOAL_TICKS - this.goalTicks);
        return (remainingTicks + 19) / 20;
    }

    private enum EscapeMode {
        NONE,
        NAVIGATE_ROUTE,
        GATHER_BLOCKS,
        CLEAR_EXIT,
        PILLAR
    }

    private record EscapeMaterialTarget(BlockPos targetPos, BlockPos standPos) {}

    private record PillarPlan(BlockPos basePos, int exitY, int blocksNeeded) {}
}
