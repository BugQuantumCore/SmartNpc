package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.player_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class EscapeHoleWithBlockGoal extends Goal {
    private static final int COOLDOWN_TICKS = 40;
    private static final int PLACE_DELAY_TICKS = 2;
    private static final int MAX_PLACE_WAIT_TICKS = 24;
    private static final double PLACE_CLEARANCE_Y = 0.95D;
    private static final double FALLBACK_PLACE_CLEARANCE_Y = 0.78D;
    private static final int MIN_ROUTE_ESCAPE_BLOCKS = 16;
    private static final int MAX_ROUTE_ESCAPE_BLOCKS = 32;
    private static final int MAX_GOAL_TICKS = 20 * 45;
    private static final int SEARCH_RADIUS = 5;
    private static final double BREAK_DISTANCE_SQR = 3.0D * 3.0D;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_FAILED_PATH_TICKS = 20 * 3;
    private static final int PILLAR_SEARCH_RADIUS = 5;
    private static final int PILLAR_SURFACE_SCAN_UP = 48;
    private static final double PILLAR_BASE_REACHED_SQR = 1.2D * 1.2D;

    private final PlayerNpcEntity playerNpc;
    private EscapeMode mode = EscapeMode.NONE;
    private BlockPos placePos;
    private BlockPos minePos;
    private BlockPos mineStandPos;
    private BlockPos climbTargetPos;
    private BlockPos pillarBasePos;
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
    private boolean usingTemporaryPickaxe;
    private boolean usingTemporaryBlock;
    private boolean finished;

    public EscapeHoleWithBlockGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isInWaterOrBubble()
                || this.playerNpc.getHoleEscapeCooldown() > 0) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        boolean trapped = this.hasOpenBodySpace(serverLevel, feet)
                && this.isWalkableFloor(serverLevel, feet.below())
                && this.isActuallyTrapped(serverLevel, feet);
        BlockPos routeTarget = this.getUpwardRouteTarget(serverLevel, feet);
        boolean routeNeedsClimb = this.routeNeedsClimb(serverLevel, feet, routeTarget);

        if (!trapped && !routeNeedsClimb) {
            return false;
        }

        this.resetPlan();
        this.climbTargetPos = routeNeedsClimb ? routeTarget : null;
        this.requiredEscapeBlocks = routeNeedsClimb
                ? MIN_ROUTE_ESCAPE_BLOCKS + this.playerNpc.getRandom().nextInt(MAX_ROUTE_ESCAPE_BLOCKS - MIN_ROUTE_ESCAPE_BLOCKS + 1)
                : 1;

        PillarPlan pillarPlan = this.findPillarPlan(serverLevel, feet, routeNeedsClimb ? routeTarget : null);
        if (pillarPlan == null) {
            return false;
        }
        this.pillarBasePos = pillarPlan.basePos();
        this.pillarExitY = pillarPlan.exitY();
        if (routeNeedsClimb) {
            this.requiredEscapeBlocks = Math.max(this.requiredEscapeBlocks, pillarPlan.blocksNeeded());
        }

        int escapeBlocks = this.countEscapeBlocks();
        if (escapeBlocks < this.requiredEscapeBlocks && this.hasPickaxe()) {
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
                || !(this.playerNpc.level() instanceof ServerLevel)) {
            return false;
        }

        if (this.mode == EscapeMode.GATHER_BLOCKS) {
            return this.countEscapeBlocks() < this.requiredEscapeBlocks
                    && (this.minePos != null || this.hasPickaxe());
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
        this.finished = false;
        this.previousMainHand = ItemStack.EMPTY;
        this.previousPillarMainHand = ItemStack.EMPTY;
        this.usingTemporaryPickaxe = false;
        this.usingTemporaryBlock = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.escaping_hole");
        this.playerNpc.setHoleEscapeCooldown(COOLDOWN_TICKS);

        if (this.mode == EscapeMode.GATHER_BLOCKS) {
            if (!this.equipPickaxe()) {
                this.finished = true;
                return;
            }
            this.moveToMineTarget();
        } else if (this.mode == EscapeMode.PILLAR && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.beginPillarStep(serverLevel);
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.goalTicks++;
        if (this.mode == EscapeMode.GATHER_BLOCKS) {
            this.tickGatherBlocks(serverLevel);
        } else if (this.mode == EscapeMode.PILLAR) {
            this.tickPillar(serverLevel);
        }
    }

    @Override
    public void stop() {
        this.playerNpc.clearBlockBreakProgress(this.minePos);
        this.restorePreviousMainHand();
        this.restorePreviousPillarMainHand();
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setHoleEscapeCooldown(COOLDOWN_TICKS);
        }
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.resetPlan();
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
        if (!this.isEscapeMaterial(state)) {
            this.playerNpc.clearBlockBreakProgress(this.minePos);
            this.minePos = null;
            return;
        }
        if (!this.equipPickaxe()) {
            this.playerNpc.clearBlockBreakProgress(this.minePos);
            this.finished = true;
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
        if (this.placePos == null) {
            if (!this.shouldContinuePillaring(serverLevel)) {
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
            this.placePos = null;
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
        serverLevel.setBlockAndUpdate(this.placePos, blockItem.getBlock().defaultBlockState());
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(null, this.placePos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        blockStack.shrink(1);
        if (blockStack.isEmpty()) {
            this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        this.pillarsPlaced++;
        this.placePos = null;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.updatePillarDetail();
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

        if (!this.canPillarFrom(serverLevel, feet)) {
            PillarPlan newPlan = this.findPillarPlan(serverLevel, feet, this.climbTargetPos);
            if (newPlan == null) {
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
        return feet.getY() < this.climbTargetPos.getY() - 1
                && !this.hasStepExitToward(serverLevel, feet, this.climbTargetPos);
    }

    private boolean moveToMineTarget() {
        if (this.mineStandPos == null) {
            return false;
        }

        return this.playerNpc.getNavigation().moveTo(this.mineStandPos.getX() + 0.5D, this.mineStandPos.getY(), this.mineStandPos.getZ() + 0.5D, 1.0D);
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
        List<PillarPlan> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-PILLAR_SEARCH_RADIUS, -1, -PILLAR_SEARCH_RADIUS),
                feet.offset(PILLAR_SEARCH_RADIUS, 2, PILLAR_SEARCH_RADIUS))) {
            BlockPos base = pos.immutable();
            PillarPlan plan = this.createPillarPlan(serverLevel, base, routeTarget);
            if (plan != null) {
                candidates.add(plan);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator
                .comparingInt(PillarPlan::blocksNeeded)
                .thenComparingDouble(plan -> feet.distSqr(plan.basePos()))
                .thenComparingDouble(plan -> routeTarget == null ? 0.0D : routeTarget.distSqr(plan.basePos())));
        return candidates.get(0);
    }

    private PillarPlan createPillarPlan(ServerLevel serverLevel, BlockPos base, BlockPos routeTarget) {
        if (!this.canStandAt(serverLevel, base) || !serverLevel.getBlockState(base).canBeReplaced()) {
            return null;
        }
        if (routeTarget == null) {
            return this.hasOpenBodySpace(serverLevel, base.above())
                    ? new PillarPlan(base, base.getY() + 1, 1)
                    : null;
        }

        int scanTop = Math.min(serverLevel.getMaxBuildHeight() - 3, base.getY() + PILLAR_SURFACE_SCAN_UP);
        for (int y = base.getY(); y <= scanTop; y++) {
            BlockPos feetAtY = new BlockPos(base.getX(), y, base.getZ());
            if (!this.hasOpenBodySpace(serverLevel, feetAtY)) {
                return null;
            }

            boolean reachesRoute = routeTarget != null
                    && y >= routeTarget.getY() - 1
                    && this.hasStepExitToward(serverLevel, feetAtY, routeTarget);
            boolean reachesSurface = serverLevel.canSeeSky(feetAtY.above())
                    && (routeTarget == null || y >= Math.min(routeTarget.getY() - 1, base.getY() + MIN_ROUTE_ESCAPE_BLOCKS));
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
                ? Math.min(this.pillarExitY, feet.getY() + Math.max(1, this.maxPillarBlocks - this.pillarsPlaced) + 1)
                : feet.getY() + 2;
        for (int y = feet.getY(); y <= topY; y++) {
            if (!this.hasOpenBodySpace(serverLevel, new BlockPos(feet.getX(), y, feet.getZ()))) {
                return false;
            }
        }
        return true;
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
            if (immutable.equals(center.below()) || !this.isEscapeMaterial(serverLevel.getBlockState(immutable))) {
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

    private boolean routeNeedsClimb(ServerLevel serverLevel, BlockPos feet, BlockPos routeTarget) {
        if (routeTarget == null || routeTarget.getY() <= feet.getY() + 2) {
            return false;
        }

        if (this.hasStepExitToward(serverLevel, feet, routeTarget)) {
            return false;
        }

        return this.playerNpc.getNavigation().isStuck()
                || this.playerNpc.getNavigation().isDone()
                || this.hasTallWallToward(serverLevel, feet, routeTarget);
    }

    private BlockPos getUpwardRouteTarget(ServerLevel serverLevel, BlockPos feet) {
        BlockPos navigationTarget = this.playerNpc.getNavigation().getTargetPos();
        if (navigationTarget != null && navigationTarget.getY() > feet.getY() + 2) {
            return navigationTarget.immutable();
        }

        if (!"ai.player_npc.returning_home".equals(this.playerNpc.getCurrentAiState())) {
            return null;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return null;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        return homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
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
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem
                && !stack.is(Items.CRAFTING_TABLE)
                && !stack.is(Items.CHEST)
                && !stack.is(Items.FURNACE)
                && !(stack.getItem() instanceof BedItem)
                && !((BlockItem) stack.getItem()).getBlock().defaultBlockState().is(Blocks.TORCH);
    }

    private boolean isEscapeMaterial(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE);
    }

    private ItemStack escapeDropFor(BlockState state) {
        if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE)) {
            return new ItemStack(Items.COBBLED_DEEPSLATE);
        }
        return new ItemStack(Items.COBBLESTONE);
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockState state) {
        float hardness = state.getDestroySpeed(serverLevel, this.minePos);
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
        if (this.playerNpc.getMainHandItem().getItem() instanceof PickaxeItem) {
            return true;
        }

        ItemStack pickaxe = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof PickaxeItem, 1)
                .orElse(ItemStack.EMPTY);
        if (pickaxe.isEmpty()) {
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
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, pickaxe);
        return true;
    }

    private boolean equipEscapeBlockForPlacement() {
        if (this.isEscapeBlock(this.playerNpc.getMainHandItem())) {
            return true;
        }

        ItemStack block = InventoryUtils.consumeItem(this.playerNpc, this::isEscapeBlock, 1).orElse(ItemStack.EMPTY);
        if (block.isEmpty()) {
            return false;
        }

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
        return true;
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
        return count;
    }

    private boolean hasTwoBlockBarrier(ServerLevel serverLevel, BlockPos feet, Direction direction) {
        BlockPos lower = feet.relative(direction);
        return this.hasBlockingCollision(serverLevel, lower)
                && this.hasBlockingCollision(serverLevel, lower.above());
    }

    private boolean isActuallyTrapped(ServerLevel serverLevel, BlockPos feet) {
        int tallWalls = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacentFeet = feet.relative(direction);
            if (this.hasOpenBodySpace(serverLevel, adjacentFeet)
                    && this.isWalkableFloor(serverLevel, adjacentFeet.below())) {
                return false;
            }
            if (this.hasTwoBlockBarrier(serverLevel, feet, direction)) {
                tallWalls++;
            }
        }

        return tallWalls >= 3;
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
                "pillaring %d/%d",
                this.pillarsPlaced,
                this.maxPillarBlocks
        ));
    }

    private void resetPlan() {
        this.mode = EscapeMode.NONE;
        this.placePos = null;
        this.minePos = null;
        this.mineStandPos = null;
        this.climbTargetPos = null;
        this.pillarBasePos = null;
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
        this.usingTemporaryPickaxe = false;
        this.usingTemporaryBlock = false;
        this.finished = false;
    }

    private enum EscapeMode {
        NONE,
        GATHER_BLOCKS,
        PILLAR
    }

    private record EscapeMaterialTarget(BlockPos targetPos, BlockPos standPos) {}

    private record PillarPlan(BlockPos basePos, int exitY, int blocksNeeded) {}
}
