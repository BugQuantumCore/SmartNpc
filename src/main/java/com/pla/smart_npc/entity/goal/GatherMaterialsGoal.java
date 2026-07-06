package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBuildLayout;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

public class GatherMaterialsGoal extends Goal {
    private static final int SEARCH_RADIUS = 8;
    private static final int LOG_SEARCH_RADIUS = 48;
    private static final int LOCAL_RESOURCE_RADIUS = 96;
    private static final int MAX_LOG_TARGET_PATH_CHECKS = 32;
    private static final double BREAK_DISTANCE_SQR = 4.5D * 4.5D;
    private static final int COOLDOWN_TICKS = 20;
    private static final int LOG_RETRY_COOLDOWN_TICKS = 5;
    private static final int LOG_BATCH_COOLDOWN_TICKS = 2;
    private static final int MIN_LOG_RESERVE = 4;
    private static final int MAX_LOG_RESERVE = 12;
    private static final int MIN_DIRT_PILLAR_RESERVE = 4;
    private static final int MAX_DIRT_PILLAR_RESERVE = 12;
    private static final int STARTER_WOOD_TARGET = 12;
    private static final int STONE_GEAR_STONE_TARGET = 9;
    private static final int COAL_RESERVE_TARGET = 16;
    private static final int ACTIVE_FURNACE_COAL_RESERVE_TARGET = 24;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_GATHER_TICKS = 20 * 20;
    private static final int FAILED_GATHER_COOLDOWN_TICKS = 20 * 20;
    private static final int MAX_FAILED_PATH_TICKS = 20 * 3;
    private static final int MAX_TREE_LOGS = 96;
    private static final int MAX_TREE_LEAF_CLEARS = 24;
    private static final int TREE_LEAF_CLEAR_RADIUS = 2;
    private static final int TREE_LEAF_DIRECT_SCAN_RADIUS = 3;
    private static final int TREE_LEAF_MAX_ABOVE_FEET = 3;
    private static final int TREE_LEAF_MAX_BELOW_FEET = 1;
    private static final double TREE_LEAF_RELATED_LOG_DISTANCE_SQR = 5.0D * 5.0D;
    private static final int LOG_PILLAR_JUMP_WINDUP_TICKS = 2;
    private static final int LOG_PILLAR_PLACE_DELAY_TICKS = 1;
    private static final int LOG_PILLAR_MAX_PLACE_WAIT_TICKS = 32;
    private static final double LOG_PILLAR_PLACE_CLEARANCE_Y = 0.65D;
    private static final double LOG_PILLAR_FALLBACK_PLACE_CLEARANCE_Y = 0.55D;
    private static final int LOG_PILLAR_FORCE_PLACE_TICKS = 3;
    private static final double LOG_PILLAR_BASE_REACHED_SQR = 1.2D * 1.2D;
    private static final int LOG_PILLAR_MIN_VERTICAL_GAP = 3;
    private static final int LOG_PILLAR_MAX_FAILED_STEPS = 2;
    private static final int MAX_DEFERRED_HIGH_LOG_TARGETS = 64;
    private static final double PATH_OBSTRUCTION_BREAK_DISTANCE_SQR = 3.2D * 3.2D;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final Set<BlockPos> treeLogs = new HashSet<>();
    private final Set<BlockPos> minedTreeLogs = new HashSet<>();
    private final Set<BlockPos> skippedTreeTargets = new HashSet<>();
    private final Set<BlockPos> skippedPathObstructions = new HashSet<>();
    private final Set<BlockPos> deferredHighLogTargets = new HashSet<>();
    private BlockPos targetPos;
    private BlockPos standPos;
    private BlockPos pathObstructionPos;
    private MaterialTarget targetType = MaterialTarget.GENERAL;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int mineTicks;
    private int pathObstructionMineTicks;
    private int gatherTicks;
    private int repathTicks;
    private int failedPathTicks;
    private int logReserveTarget;
    private int dirtPillarReserveTarget;
    private int minedLogsThisRun;
    private int clearedLeavesThisRun;
    private BlockPos logPillarPlacePos;
    private int logPillarJumpDelayTicks;
    private int logPillarPlaceDelayTicks;
    private int logPillarPlaceWaitTicks;
    private int logPillarFailedSteps;
    private int deferredHighLogDirtCount = -1;
    private boolean usingTemporaryTool;
    private boolean failedToGather;

    public GatherMaterialsGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
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
                || this.inventoryIsMostlyFull()) {
            return false;
        }

        if (this.shouldCraftBeforeGathering(serverLevel)) {
            return false;
        }
        if (BuildHouseGoal.hasReadyHomeBuildWork(this.playerNpc, serverLevel)
                && !this.needsBuildMaterialReserves(serverLevel)) {
            return false;
        }

        this.refreshDeferredHighLogTargets();
        this.targetType = this.chooseTargetType(serverLevel);
        GatherTarget target = this.findTargetBlock(serverLevel);
        if (target == null && this.targetType == MaterialTarget.LOG) {
            this.ensureDirtPillarReserveTarget();
            if (this.countDirtBlocks() < this.dirtPillarReserveTarget) {
                this.targetType = MaterialTarget.DIRT;
                target = this.findTargetBlock(serverLevel);
            } else {
                this.targetType = MaterialTarget.GENERAL;
                target = this.findTargetBlock(serverLevel);
            }
        }
        if (target == null && this.targetType == MaterialTarget.DIRT) {
            this.targetType = MaterialTarget.LOG;
            target = this.findTargetBlock(serverLevel);
        }
        if (target != null && this.targetType == MaterialTarget.LOG && this.shouldCollectDirtBeforeHighLog(target)) {
            this.ensureDirtPillarReserveTarget();
            this.targetType = MaterialTarget.DIRT;
            GatherTarget dirtTarget = this.findTargetBlock(serverLevel);
            if (dirtTarget != null) {
                target = dirtTarget;
            } else {
                this.targetType = MaterialTarget.LOG;
            }
        }
        if (target == null) {
            return false;
        }

        this.targetPos = target.targetPos();
        this.standPos = target.standPos();
        if (serverLevel.getBlockState(this.targetPos).is(BlockTags.LOGS)) {
            this.targetType = MaterialTarget.LOG;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.playerNpc.level() instanceof ServerLevel serverLevel
                && BuildHouseGoal.hasReadyHomeBuildWork(this.playerNpc, serverLevel)
                && !this.needsBuildMaterialReserves(serverLevel)) {
            return false;
        }

        return this.targetPos != null
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && !this.inventoryIsMostlyFull()
                && this.gatherTicks < this.getMaxGatherTicks();
    }

    @Override
    public void start() {
        this.mineTicks = 0;
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.logPillarPlacePos = null;
        this.logPillarJumpDelayTicks = 0;
        this.pathObstructionPos = null;
        this.logPillarPlaceDelayTicks = 0;
        this.logPillarPlaceWaitTicks = 0;
        this.logPillarFailedSteps = 0;
        this.pathObstructionMineTicks = 0;
        this.minedLogsThisRun = 0;
        this.clearedLeavesThisRun = 0;
        this.previousMainHand = ItemStack.EMPTY;
        this.failedToGather = false;
        this.treeLogs.clear();
        this.minedTreeLogs.clear();
        this.skippedTreeTargets.clear();
        this.skippedPathObstructions.clear();
        this.usingTemporaryTool = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.gathering_materials");
        if (this.targetPos != null && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            if (!this.equipToolFor(serverLevel.getBlockState(this.targetPos))) {
                this.failedToGather = true;
                this.targetPos = null;
                return;
            }
            if (this.targetType == MaterialTarget.LOG) {
                this.refreshTreeLogs(serverLevel, this.targetPos);
            }
            this.updateTaskDetail(serverLevel);
        }
        this.moveToTarget();
    }

    @Override
    public void stop() {
        boolean shouldRetryLogsSoon = this.targetType == MaterialTarget.LOG
                && this.logReserveTarget > 0
                && this.countRawLogs() < this.logReserveTarget;
        boolean shouldRunNextAiSoon = this.targetType == MaterialTarget.LOG
                && this.minedLogsThisRun > 0;
        boolean shouldRetryDirtSoon = this.targetType == MaterialTarget.DIRT
                && this.dirtPillarReserveTarget > 0
                && this.countDirtBlocks() < this.dirtPillarReserveTarget;
        boolean failedThisRun = this.failedToGather || this.gatherTicks >= this.getMaxGatherTicks();
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.playerNpc.clearBlockBreakProgress(this.pathObstructionPos);
        this.playerNpc.getNavigation().stop();
        this.restorePreviousMainHand();
        this.targetPos = null;
        this.standPos = null;
        this.pathObstructionPos = null;
        this.mineTicks = 0;
        this.pathObstructionMineTicks = 0;
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.logPillarPlacePos = null;
        this.logPillarJumpDelayTicks = 0;
        this.logPillarPlaceDelayTicks = 0;
        this.logPillarPlaceWaitTicks = 0;
        this.logPillarFailedSteps = 0;
        this.minedLogsThisRun = 0;
        this.clearedLeavesThisRun = 0;
        this.treeLogs.clear();
        this.minedTreeLogs.clear();
        this.skippedTreeTargets.clear();
        this.skippedPathObstructions.clear();
        this.targetType = MaterialTarget.GENERAL;
        this.failedToGather = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        if (!this.playerNpc.level().isClientSide) {
            int cooldown = failedThisRun
                    ? FAILED_GATHER_COOLDOWN_TICKS
                    : shouldRunNextAiSoon
                    ? LOG_BATCH_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(6)
                    : shouldRetryLogsSoon
                    ? LOG_RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(10)
                    : shouldRetryDirtSoon
                    ? LOG_RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(8)
                    : COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(30);
            this.playerNpc.setGatherCooldown(cooldown);
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.targetPos == null) {
            return;
        }
        this.gatherTicks++;
        if (this.gatherTicks >= this.getMaxGatherTicks()) {
            this.failGathering(serverLevel, String.format(java.util.Locale.ROOT, "giving up after %ds", this.getMaxGatherSeconds()));
            return;
        }

        BlockState targetState = serverLevel.getBlockState(this.targetPos);
        if (this.targetType == MaterialTarget.LOG
                && targetState.is(BlockTags.LOGS)
                && !this.hasTool(AxeItem.class)
                && this.canCraftAxeNow(serverLevel)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.targetPos = null;
            return;
        }

        if (!this.isCurrentTargetBlock(targetState) || !this.hasRequiredToolFor(targetState)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            if (this.targetType == MaterialTarget.LOG && this.switchToNextLog(serverLevel, this.targetPos)) {
                return;
            }
            this.failedToGather = true;
            this.targetPos = null;
            return;
        }

        if (this.logPillarPlacePos != null) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.tickLogPillarPlacement(serverLevel);
            return;
        }

        this.updateTaskDetail(serverLevel);

        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (this.tickTargetCoverObstruction(serverLevel)) {
            return;
        }

        if (this.targetType == MaterialTarget.LOG
                && targetState.is(BlockTags.LOGS)
                && this.shouldPillarForHighLog(this.playerNpc.blockPosition(), this.targetPos)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            if (this.countDirtBlocks() < this.neededDirtForHighLog(this.playerNpc.blockPosition(), this.targetPos)
                    && this.switchToDirtTarget(serverLevel)) {
                return;
            }
            if (this.tryPillarTowardLog(serverLevel)) {
                return;
            }
            if (this.switchToTreeLeafClearTarget(serverLevel)) {
                return;
            }
            if (this.skipCurrentTreeTargetAndSwitch(serverLevel)) {
                return;
            }
            this.deferHighLogTarget(this.targetPos);
            this.targetPos = null;
            return;
        }

        if (!this.equipToolFor(targetState)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.failedToGather = true;
            this.targetPos = null;
            return;
        }

        if (this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D) > BREAK_DISTANCE_SQR) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            if (this.tickPathObstruction(serverLevel)) {
                return;
            }
            if (this.targetType == MaterialTarget.LOG && targetState.is(BlockTags.LOGS) && this.tryPillarTowardLog(serverLevel)) {
                return;
            }
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                if (this.tryStartPathObstructionMining(serverLevel)) {
                    return;
                }
                if (this.moveToTarget()) {
                    this.failedPathTicks = 0;
                } else {
                    this.failedPathTicks += REPATH_INTERVAL_TICKS;
                    if (this.failedPathTicks >= MAX_FAILED_PATH_TICKS) {
                        this.playerNpc.clearBlockBreakProgress(this.targetPos);
                        if (this.skipCurrentTreeTargetAndSwitch(serverLevel)) {
                            return;
                        }
                        this.failGathering(serverLevel, "failed to path to target");
                    }
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            return;
        }

        this.clearPathObstruction(serverLevel);
        this.playerNpc.getNavigation().stop();
        if (this.mineTicks % 8 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, this.targetPos, targetState, this.playerNpc);
        }

        this.mineTicks++;
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, serverLevel.getBlockState(this.targetPos));
        this.playerNpc.showBlockBreakProgress(this.targetPos, this.mineTicks, requiredMineTicks);
        if (this.mineTicks < requiredMineTicks) {
            return;
        }

        BlockPos minedPos = this.targetPos;
        BlockState minedState = serverLevel.getBlockState(minedPos);
        if (!serverLevel.destroyBlock(minedPos, true, this.playerNpc)) {
            this.playerNpc.clearBlockBreakProgress(minedPos);
            if (this.skipCurrentTreeTargetAndSwitch(serverLevel)) {
                return;
            }
            this.failedToGather = true;
            this.targetPos = null;
            return;
        }
        this.playerNpc.clearBlockBreakProgress(minedPos);
        this.playerNpc.hurtMainHandItem(1);
        if (this.targetType == MaterialTarget.LOG) {
            this.logPillarPlacePos = null;
            this.logPillarJumpDelayTicks = 0;
            if (this.isTreeLeaf(minedState)) {
                this.clearedLeavesThisRun++;
                this.skippedTreeTargets.add(minedPos.immutable());
            } else {
                this.minedLogsThisRun++;
                this.minedTreeLogs.add(minedPos.immutable());
                this.treeLogs.remove(minedPos);
            }
            if (this.switchToNextLog(serverLevel, minedPos)) {
                return;
            }
        }
        this.targetPos = null;
    }

    private void failGathering(ServerLevel serverLevel, String detail) {
        this.failedToGather = true;
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.playerNpc.clearBlockBreakProgress(this.pathObstructionPos);
        this.playerNpc.getNavigation().stop();
        this.targetPos = null;
        this.standPos = null;
        this.pathObstructionPos = null;
        this.logPillarPlacePos = null;
        this.logPillarJumpDelayTicks = 0;
        this.mineTicks = 0;
        this.pathObstructionMineTicks = 0;
        this.logPillarPlaceDelayTicks = 0;
        this.logPillarPlaceWaitTicks = 0;
        this.playerNpc.setCurrentAiDetail(detail);
    }

    private boolean moveToTarget() {
        if (this.standPos == null) {
            return false;
        }

        Path path = this.playerNpc.getNavigation().createPath(this.standPos, 0);
        if (path == null || !path.canReach()) {
            return false;
        }
        return this.playerNpc.getNavigation().moveTo(path, this.speed);
    }

    private boolean tryStartPathObstructionMining(ServerLevel serverLevel) {
        BlockPos obstruction = this.findPathObstructionTarget(serverLevel);
        if (obstruction == null) {
            return false;
        }

        this.pathObstructionPos = obstruction;
        this.pathObstructionMineTicks = 0;
        this.playerNpc.getNavigation().stop();
        return this.tickPathObstruction(serverLevel);
    }

    private boolean tickPathObstruction(ServerLevel serverLevel) {
        if (this.pathObstructionPos == null) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(this.pathObstructionPos);
        if (!this.isPathObstructionBlock(serverLevel, this.pathObstructionPos, state)) {
            this.clearPathObstruction(serverLevel);
            return false;
        }
        if (this.playerNpc.distanceToSqr(
                this.pathObstructionPos.getX() + 0.5D,
                this.pathObstructionPos.getY() + 0.5D,
                this.pathObstructionPos.getZ() + 0.5D
        ) > PATH_OBSTRUCTION_BREAK_DISTANCE_SQR) {
            this.skippedPathObstructions.add(this.pathObstructionPos.immutable());
            this.clearPathObstruction(serverLevel);
            return false;
        }
        if (!this.equipToolFor(state)) {
            this.skippedPathObstructions.add(this.pathObstructionPos.immutable());
            this.clearPathObstruction(serverLevel);
            return false;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.pathObstructionPos.getX() + 0.5D,
                this.pathObstructionPos.getY() + 0.5D,
                this.pathObstructionPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        if (this.pathObstructionMineTicks % 8 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, this.pathObstructionPos, state, this.playerNpc);
        }

        this.pathObstructionMineTicks++;
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, this.pathObstructionPos, state);
        this.playerNpc.showBlockBreakProgress(this.pathObstructionPos, this.pathObstructionMineTicks, requiredMineTicks);
        this.updatePathObstructionDetail(serverLevel, state, requiredMineTicks);
        if (this.pathObstructionMineTicks < requiredMineTicks) {
            return true;
        }

        BlockPos clearedPos = this.pathObstructionPos;
        if (serverLevel.destroyBlock(clearedPos, true, this.playerNpc)) {
            this.playerNpc.hurtMainHandItem(1);
            this.failedPathTicks = 0;
            this.repathTicks = 0;
            this.moveToTarget();
        } else {
            this.skippedPathObstructions.add(clearedPos.immutable());
        }
        this.clearPathObstruction(serverLevel);
        return true;
    }

    private boolean tickTargetCoverObstruction(ServerLevel serverLevel) {
        BlockPos obstruction = this.findTargetCoverObstruction(serverLevel);
        if (obstruction == null) {
            return false;
        }

        if (!obstruction.equals(this.pathObstructionPos)) {
            this.clearPathObstruction(serverLevel);
            this.pathObstructionPos = obstruction;
            this.pathObstructionMineTicks = 0;
            this.playerNpc.getNavigation().stop();
        }
        return this.tickPathObstruction(serverLevel);
    }

    private void clearPathObstruction(ServerLevel serverLevel) {
        this.playerNpc.clearBlockBreakProgress(this.pathObstructionPos);
        this.pathObstructionPos = null;
        this.pathObstructionMineTicks = 0;
    }

    private BlockPos findPathObstructionTarget(ServerLevel serverLevel) {
        if (this.standPos == null || this.targetPos == null) {
            return null;
        }

        return this.findPathObstructionToward(serverLevel, this.standPos, this.targetPos);
    }

    private boolean hasLocalPathObstructionToward(ServerLevel serverLevel, BlockPos destination) {
        return this.playerNpc.blockPosition().distSqr(destination) <= 6.0D * 6.0D
                && this.findPathObstructionToward(serverLevel, destination, null) != null;
    }

    private BlockPos findPathObstructionToward(ServerLevel serverLevel, BlockPos destination, BlockPos materialTarget) {
        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(feet.above());
        candidates.add(feet.above(2));
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            candidates.add(side);
            candidates.add(side.above());
            if (destination.getY() > feet.getY()) {
                candidates.add(side.above(2));
            }
        }

        Set<BlockPos> seen = new HashSet<>();
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(destination))
                .thenComparingDouble(this::distanceToBlockCenterSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!seen.add(immutable)
                    || this.skippedPathObstructions.contains(immutable)
                    || materialTarget != null && immutable.equals(materialTarget)
                    || !serverLevel.isInWorldBounds(immutable)
                    || !serverLevel.getWorldBorder().isWithinBounds(immutable)) {
                continue;
            }

            BlockState state = serverLevel.getBlockState(immutable);
            if (this.isPathObstructionBlock(serverLevel, immutable, state)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean isPathObstructionBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return !state.isAir()
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && (!state.getCollisionShape(serverLevel, pos).isEmpty()
                || state.is(BlockTags.LEAVES)
                || state.canBeReplaced())
                && state.getFluidState().isEmpty()
                && !this.isProtectedHomeBlock(pos)
                && serverLevel.getBlockEntity(pos) == null
                && this.hasRequiredToolFor(state);
    }

    private boolean isFenceLikePathObstruction(BlockState state) {
        return state.getBlock() instanceof FenceBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof WallBlock;
    }

    private BlockPos findTargetCoverObstruction(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return null;
        }

        Vec3 eye = new Vec3(this.playerNpc.getX(), this.playerNpc.getEyeY(), this.playerNpc.getZ());
        Vec3 targetCenter = Vec3.atCenterOf(this.targetPos);
        BlockHitResult hit = serverLevel.clip(new ClipContext(
                eye,
                targetCenter,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                this.playerNpc
        ));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return this.findNearbyFenceLikeTargetObstruction(serverLevel);
        }

        BlockPos hitPos = hit.getBlockPos().immutable();
        if (hitPos.equals(this.targetPos)
                || !serverLevel.isInWorldBounds(hitPos)
                || !serverLevel.getWorldBorder().isWithinBounds(hitPos)
                || this.playerNpc.distanceToSqr(hitPos.getX() + 0.5D, hitPos.getY() + 0.5D, hitPos.getZ() + 0.5D) > PATH_OBSTRUCTION_BREAK_DISTANCE_SQR) {
            return this.findNearbyFenceLikeTargetObstruction(serverLevel);
        }

        BlockState state = serverLevel.getBlockState(hitPos);
        return this.isPathObstructionBlock(serverLevel, hitPos, state) ? hitPos : this.findNearbyFenceLikeTargetObstruction(serverLevel);
    }

    private BlockPos findNearbyFenceLikeTargetObstruction(ServerLevel serverLevel) {
        if (this.targetType != MaterialTarget.LOG || this.targetPos == null) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(this.targetPos.relative(direction));
            candidates.add(this.targetPos.relative(direction).below());
            candidates.add(this.targetPos.relative(direction, 2));
            candidates.add(this.targetPos.relative(direction, 2).below());
        }

        candidates.sort(Comparator
                .comparingDouble(this::distanceToBlockCenterSqr)
                .thenComparingDouble(pos -> pos.distSqr(this.targetPos)));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (immutable.equals(this.targetPos)
                    || this.skippedPathObstructions.contains(immutable)
                    || !serverLevel.isInWorldBounds(immutable)
                    || !serverLevel.getWorldBorder().isWithinBounds(immutable)
                    || this.playerNpc.distanceToSqr(immutable.getX() + 0.5D, immutable.getY() + 0.5D, immutable.getZ() + 0.5D) > PATH_OBSTRUCTION_BREAK_DISTANCE_SQR) {
                continue;
            }

            BlockState state = serverLevel.getBlockState(immutable);
            if (this.isFenceLikePathObstruction(state) && this.isPathObstructionBlock(serverLevel, immutable, state)) {
                return immutable;
            }
        }
        return null;
    }

    private GatherTarget findTargetBlock(ServerLevel serverLevel) {
        List<GatherTarget> candidates = new ArrayList<>();
        List<GatherTarget> pillarCandidates = new ArrayList<>();
        List<GatherTarget> leafCandidates = new ArrayList<>();
        List<BlockPos> logCandidates = new ArrayList<>();
        BlockPos center = this.playerNpc.blockPosition();
        int searchRadius = this.targetType == MaterialTarget.LOG ? LOG_SEARCH_RADIUS : SEARCH_RADIUS;
        int searchUp = this.targetType == MaterialTarget.LOG ? 10 : 3;

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-searchRadius, -2, -searchRadius), center.offset(searchRadius, searchUp, searchRadius))) {
            BlockPos immutable = pos.immutable();
            if (this.isProtectedHomeBlock(immutable) || !this.isInsideResourceRadius(immutable)) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(immutable);
            if (this.isWantedBlockForTarget(state) && this.hasRequiredToolFor(state)) {
                if (this.targetType == MaterialTarget.LOG) {
                    if (!this.skippedTreeTargets.contains(immutable)
                            && !this.deferredHighLogTargets.contains(immutable)) {
                        logCandidates.add(immutable);
                    }
                } else {
                    BlockPos stand = this.findStandPos(serverLevel, immutable);
                    if (stand != null) {
                        candidates.add(new GatherTarget(immutable, stand));
                    }
                }
            }
        }

        if (this.targetType == MaterialTarget.LOG) {
            logCandidates.sort(Comparator
                    .comparingDouble((BlockPos pos) -> center.distSqr(pos))
                    .thenComparingInt(BlockPos::getY));
            int checked = 0;
            for (BlockPos logCandidate : logCandidates) {
                BlockPos stand = this.findStandPos(serverLevel, logCandidate);
                if (stand != null) {
                    candidates.add(new GatherTarget(logCandidate, stand));
                } else {
                    BlockPos pillarBase = this.findLogPillarBase(serverLevel, logCandidate);
                    if (pillarBase != null) {
                        pillarCandidates.add(new GatherTarget(logCandidate, pillarBase));
                    } else {
                        GatherTarget leafTarget = this.findTreeLeafClearTarget(serverLevel, logCandidate);
                        if (leafTarget != null) {
                            leafCandidates.add(leafTarget);
                        }
                    }
                }
                if (++checked >= MAX_LOG_TARGET_PATH_CHECKS) {
                    break;
                }
            }
        }

        if (candidates.isEmpty()) {
            if (this.targetType != MaterialTarget.LOG) {
                return null;
            }
            if (!pillarCandidates.isEmpty()) {
                pillarCandidates.sort(Comparator
                        .comparingDouble((GatherTarget target) -> center.distSqr(target.standPos()))
                        .thenComparingInt(target -> target.targetPos().getY()));
                return pillarCandidates.get(0);
            }
            if (!leafCandidates.isEmpty()) {
                leafCandidates.sort(Comparator
                        .comparingDouble((GatherTarget target) -> this.distanceToBlockCenterSqr(target.targetPos()))
                        .thenComparingDouble(target -> center.distSqr(target.standPos()))
                        .thenComparingInt(target -> target.targetPos().getY()));
                return leafCandidates.get(0);
            }
            return null;
        }
        candidates.sort(Comparator
                .comparingDouble((GatherTarget target) -> center.distSqr(target.standPos()))
                .thenComparingInt(target -> target.targetPos().getY()));
        if (this.targetType == MaterialTarget.LOG) {
            return candidates.get(0);
        }
        return candidates.get(this.playerNpc.getRandom().nextInt(Math.min(candidates.size(), 6)));
    }

    private GatherTarget findNextLogInSameTree(ServerLevel serverLevel, BlockPos minedPos) {
        this.refreshTreeLogs(serverLevel, minedPos);
        List<GatherTarget> candidates = new ArrayList<>();
        List<GatherTarget> pillarCandidates = new ArrayList<>();
        List<GatherTarget> leafCandidates = new ArrayList<>();

        for (BlockPos treeLog : this.treeLogs) {
            if (this.minedTreeLogs.contains(treeLog)
                    || this.skippedTreeTargets.contains(treeLog)
                    || this.deferredHighLogTargets.contains(treeLog)
                    || !this.isInsideResourceRadius(treeLog)
                    || this.isProtectedHomeBlock(treeLog)
                    || !serverLevel.getBlockState(treeLog).is(BlockTags.LOGS)) {
                continue;
            }

            BlockPos stand = this.findStandPos(serverLevel, treeLog);
            if (stand != null) {
                candidates.add(new GatherTarget(treeLog, stand));
            } else {
                BlockPos pillarBase = this.findLogPillarBase(serverLevel, treeLog);
                if (pillarBase != null) {
                    pillarCandidates.add(new GatherTarget(treeLog, pillarBase));
                } else {
                    GatherTarget leafTarget = this.findTreeLeafClearTarget(serverLevel, treeLog);
                    if (leafTarget != null) {
                        leafCandidates.add(leafTarget);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            if (!pillarCandidates.isEmpty()) {
                pillarCandidates.sort(Comparator
                        .comparingInt((GatherTarget target) -> target.targetPos().getY())
                        .thenComparingDouble(target -> minedPos.distSqr(target.targetPos()))
                        .thenComparingDouble(target -> this.playerNpc.blockPosition().distSqr(target.standPos())));
                return pillarCandidates.get(0);
            }
            if (!leafCandidates.isEmpty()) {
                leafCandidates.sort(Comparator
                        .comparingDouble((GatherTarget target) -> this.distanceToBlockCenterSqr(target.targetPos()))
                        .thenComparingInt(target -> target.targetPos().getY())
                        .thenComparingDouble(target -> minedPos.distSqr(target.targetPos()))
                        .thenComparingDouble(target -> this.playerNpc.blockPosition().distSqr(target.standPos())));
                return leafCandidates.get(0);
            }
            return null;
        }

        candidates.sort(Comparator
                .comparingInt((GatherTarget target) -> target.targetPos().getY())
                .thenComparingDouble(target -> minedPos.distSqr(target.targetPos()))
                .thenComparingDouble(target -> this.playerNpc.blockPosition().distSqr(target.standPos())));
        return candidates.get(0);
    }

    private boolean switchToNextLog(ServerLevel serverLevel, BlockPos originPos) {
        GatherTarget nextLog = this.findNextLogInSameTree(serverLevel, originPos);
        if (nextLog == null) {
            return false;
        }

        this.targetPos = nextLog.targetPos();
        this.standPos = nextLog.standPos();
        this.mineTicks = 0;
        this.clearPathObstruction(serverLevel);
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.logPillarPlacePos = null;
        this.logPillarJumpDelayTicks = 0;
        this.logPillarPlaceDelayTicks = 0;
        this.logPillarPlaceWaitTicks = 0;
        this.logPillarFailedSteps = 0;
        this.equipToolFor(serverLevel.getBlockState(this.targetPos));
        this.updateTaskDetail(serverLevel);
        this.moveToTarget();
        return true;
    }

    private boolean switchToDirtTarget(ServerLevel serverLevel) {
        this.ensureDirtPillarReserveTarget();
        MaterialTarget previousTargetType = this.targetType;
        this.targetType = MaterialTarget.DIRT;
        GatherTarget dirtTarget = this.findTargetBlock(serverLevel);
        if (dirtTarget == null) {
            this.targetType = previousTargetType;
            return false;
        }

        this.targetPos = dirtTarget.targetPos();
        this.standPos = dirtTarget.standPos();
        this.mineTicks = 0;
        this.clearPathObstruction(serverLevel);
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.logPillarPlacePos = null;
        this.logPillarJumpDelayTicks = 0;
        this.logPillarPlaceDelayTicks = 0;
        this.logPillarPlaceWaitTicks = 0;
        this.logPillarFailedSteps = 0;
        this.equipToolFor(serverLevel.getBlockState(this.targetPos));
        this.updateTaskDetail(serverLevel);
        this.moveToTarget();
        return true;
    }

    private boolean skipCurrentTreeTargetAndSwitch(ServerLevel serverLevel) {
        if (this.targetType != MaterialTarget.LOG || this.targetPos == null) {
            return false;
        }

        BlockPos skipped = this.targetPos.immutable();
        this.skippedTreeTargets.add(skipped);
        this.logPillarPlacePos = null;
        this.logPillarJumpDelayTicks = 0;
        this.logPillarPlaceDelayTicks = 0;
        this.logPillarPlaceWaitTicks = 0;
        this.logPillarFailedSteps = 0;
        return this.switchToNextLog(serverLevel, skipped);
    }

    private void deferHighLogTarget(BlockPos pos) {
        if (pos == null) {
            return;
        }

        if (this.deferredHighLogTargets.size() >= MAX_DEFERRED_HIGH_LOG_TARGETS) {
            this.deferredHighLogTargets.clear();
        }
        this.deferredHighLogTargets.add(pos.immutable());
        this.deferredHighLogDirtCount = this.countDirtBlocks();
    }

    private void refreshDeferredHighLogTargets() {
        if (this.deferredHighLogTargets.isEmpty()) {
            return;
        }

        int dirtCount = this.countDirtBlocks();
        if (dirtCount > this.deferredHighLogDirtCount) {
            this.deferredHighLogTargets.clear();
            this.deferredHighLogDirtCount = -1;
        }
    }

    private void refreshTreeLogs(ServerLevel serverLevel, BlockPos seedPos) {
        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        this.addTreeScanSeed(serverLevel, seedPos, queue, visited);
        for (BlockPos knownLog : this.treeLogs) {
            this.addTreeScanSeed(serverLevel, knownLog, queue, visited);
        }
        for (BlockPos minedLog : this.minedTreeLogs) {
            if (visited.add(minedLog)) {
                queue.add(minedLog);
            }
        }

        int scannedLogs = 0;
        while (!queue.isEmpty() && scannedLogs < MAX_TREE_LOGS) {
            BlockPos current = queue.remove();
            for (BlockPos next : this.treeNeighbors(current)) {
                if (!visited.add(next)
                        || !this.isInsideResourceRadius(next)
                        || this.isProtectedHomeBlock(next)
                        || !serverLevel.getBlockState(next).is(BlockTags.LOGS)) {
                    continue;
                }

                scannedLogs++;
                BlockPos immutable = next.immutable();
                this.treeLogs.add(immutable);
                queue.add(immutable);
            }
        }
    }

    private void addTreeScanSeed(ServerLevel serverLevel, BlockPos seedPos, Queue<BlockPos> queue, Set<BlockPos> visited) {
        if (seedPos == null) {
            return;
        }

        BlockPos immutable = seedPos.immutable();
        if (this.isProtectedHomeBlock(immutable) || !this.isInsideResourceRadius(immutable)) {
            return;
        }

        if (serverLevel.getBlockState(immutable).is(BlockTags.LOGS)) {
            this.treeLogs.add(immutable);
        }
        if (visited.add(immutable)) {
            queue.add(immutable);
        }
    }

    private List<BlockPos> treeNeighbors(BlockPos center) {
        List<BlockPos> neighbors = new ArrayList<>(26);
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            if (!pos.equals(center)) {
                neighbors.add(pos.immutable());
            }
        }
        return neighbors;
    }

    private GatherTarget findTreeLeafClearTarget(ServerLevel serverLevel, BlockPos logPos) {
        if (this.clearedLeavesThisRun >= MAX_TREE_LEAF_CLEARS) {
            return null;
        }

        GatherTarget directTarget = this.findDirectTreeLeafClearTarget(serverLevel, logPos);
        if (directTarget != null) {
            return directTarget;
        }

        List<GatherTarget> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                logPos.offset(-TREE_LEAF_CLEAR_RADIUS, -1, -TREE_LEAF_CLEAR_RADIUS),
                logPos.offset(TREE_LEAF_CLEAR_RADIUS, TREE_LEAF_CLEAR_RADIUS, TREE_LEAF_CLEAR_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (!this.isValidTreeLeafClearTarget(serverLevel, immutable, logPos)
                    || !this.isLeafInCurrentReachBand(immutable)) {
                continue;
            }

            BlockPos stand = this.findStandPos(serverLevel, immutable);
            if (stand != null && this.isBlockCenterWithinBreakRange(stand, immutable)) {
                candidates.add(new GatherTarget(immutable, stand));
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator
                .comparingDouble((GatherTarget target) -> this.distanceToBlockCenterSqr(target.targetPos()))
                .thenComparingDouble(target -> center.distSqr(target.standPos()))
                .thenComparingDouble(target -> logPos.distSqr(target.targetPos()))
                .thenComparingInt(target -> target.targetPos().getY()));
        return candidates.get(0);
    }

    private GatherTarget findDirectTreeLeafClearTarget(ServerLevel serverLevel, BlockPos logPos) {
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos center = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-TREE_LEAF_DIRECT_SCAN_RADIUS, -TREE_LEAF_MAX_BELOW_FEET, -TREE_LEAF_DIRECT_SCAN_RADIUS),
                center.offset(TREE_LEAF_DIRECT_SCAN_RADIUS, TREE_LEAF_MAX_ABOVE_FEET, TREE_LEAF_DIRECT_SCAN_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (this.isValidTreeLeafClearTarget(serverLevel, immutable, logPos)
                    && this.distanceToBlockCenterSqr(immutable) <= BREAK_DISTANCE_SQR) {
                candidates.add(immutable);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator
                .comparingDouble(this::distanceToBlockCenterSqr)
                .thenComparingInt(BlockPos::getY)
                .thenComparingDouble(logPos::distSqr));
        return new GatherTarget(candidates.get(0), center.immutable());
    }

    private boolean isValidTreeLeafClearTarget(ServerLevel serverLevel, BlockPos leafPos, BlockPos logPos) {
        return !leafPos.equals(logPos)
                && !this.skippedTreeTargets.contains(leafPos)
                && !this.isProtectedHomeBlock(leafPos)
                && leafPos.distSqr(logPos) <= TREE_LEAF_RELATED_LOG_DISTANCE_SQR
                && this.isTreeLeaf(serverLevel.getBlockState(leafPos));
    }

    private boolean isLeafInCurrentReachBand(BlockPos leafPos) {
        int yDelta = leafPos.getY() - this.playerNpc.blockPosition().getY();
        return yDelta >= -TREE_LEAF_MAX_BELOW_FEET && yDelta <= TREE_LEAF_MAX_ABOVE_FEET;
    }

    private double distanceToBlockCenterSqr(BlockPos pos) {
        return this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private boolean isBlockCenterWithinBreakRange(BlockPos standPos, BlockPos targetPos) {
        double dx = standPos.getX() + 0.5D - (targetPos.getX() + 0.5D);
        double dy = standPos.getY() - (targetPos.getY() + 0.5D);
        double dz = standPos.getZ() + 0.5D - (targetPos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz <= BREAK_DISTANCE_SQR;
    }

    private BlockPos findStandPos(ServerLevel serverLevel, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        candidates.add(target.above());
        candidates.add(target.below());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int yOffset = -4; yOffset <= 1; yOffset++) {
                candidates.add(target.relative(direction).offset(0, yOffset, 0));
                candidates.add(target.relative(direction, 2).offset(0, yOffset, 0));
            }
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canReachStand(serverLevel, immutable)
                    && immutable.distSqr(target) <= BREAK_DISTANCE_SQR + 1.0D) {
                return immutable;
            }
        }
        return null;
    }

    private BlockPos findLogPillarBase(ServerLevel serverLevel, BlockPos target) {
        if (this.countDirtBlocks() <= 0) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos center = this.playerNpc.blockPosition();
        candidates.add(center);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int yOffset = -2; yOffset <= 2; yOffset++) {
                candidates.add(new BlockPos(
                        target.getX() + direction.getStepX(),
                        center.getY() + yOffset,
                        target.getZ() + direction.getStepZ()
                ));
            }
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> center.distSqr(pos))
                .thenComparingInt(pos -> Math.abs(target.getY() - pos.getY())));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canStandAt(serverLevel, immutable)
                    && this.canPillarReachLog(serverLevel, immutable, target)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean tryPillarTowardLog(ServerLevel serverLevel) {
        if (this.logPillarPlacePos != null) {
            this.tickLogPillarPlacement(serverLevel);
            return true;
        }

        boolean highLogNeedsPillar = this.shouldPillarForHighLog(this.playerNpc.blockPosition(), this.targetPos);
        if (this.targetPos == null
                || this.standPos == null
                || this.countDirtBlocks() <= 0
                || !highLogNeedsPillar
                && this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D) <= BREAK_DISTANCE_SQR) {
            return false;
        }

        if (!this.isAtLogPillarBase()) {
            this.moveToTarget();
            return true;
        }

        if (!this.playerNpc.onGround()) {
            this.lookDownAt(this.playerNpc.blockPosition());
            return true;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (!this.canPillarReachLog(serverLevel, feet, this.targetPos)) {
            if (this.switchToTreeLeafClearTarget(serverLevel)) {
                return true;
            }
            return false;
        }

        if (this.beginLogPillarStep(serverLevel, feet)) {
            return true;
        }
        return this.switchToTreeLeafClearTarget(serverLevel);
    }

    private boolean shouldCollectDirtBeforeHighLog(GatherTarget target) {
        return target != null
                && this.countDirtBlocks() < this.neededDirtForHighLog(target.standPos(), target.targetPos())
                && this.shouldPillarForHighLog(target.standPos(), target.targetPos());
    }

    private boolean shouldPillarForHighLog(BlockPos feetPos, BlockPos target) {
        return feetPos != null
                && target != null
                && target.getY() - feetPos.getY() >= LOG_PILLAR_MIN_VERTICAL_GAP;
    }

    private int neededDirtForHighLog(BlockPos feetPos, BlockPos target) {
        if (!this.shouldPillarForHighLog(feetPos, target)) {
            return 0;
        }
        return Math.max(1, target.getY() - feetPos.getY() - 2);
    }

    private boolean switchToTreeLeafClearTarget(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return false;
        }

        GatherTarget leafTarget = this.findTreeLeafClearTarget(serverLevel, this.targetPos);
        if (leafTarget == null) {
            return false;
        }

        this.targetPos = leafTarget.targetPos();
        this.standPos = leafTarget.standPos();
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.logPillarPlacePos = null;
        this.logPillarJumpDelayTicks = 0;
        this.logPillarPlaceDelayTicks = 0;
        this.logPillarPlaceWaitTicks = 0;
        this.logPillarFailedSteps = 0;
        this.equipToolFor(serverLevel.getBlockState(this.targetPos));
        this.updateTaskDetail(serverLevel);
        this.moveToTarget();
        return true;
    }

    private boolean beginLogPillarStep(ServerLevel serverLevel, BlockPos feet) {
        boolean replaceable = serverLevel.getBlockState(feet).canBeReplaced();
        boolean openBodySpace = this.hasOpenBodySpace(serverLevel, feet);
        boolean otherEntity = this.hasOtherEntityInBlock(serverLevel, feet);
        boolean equippedDirt = replaceable && openBodySpace && !otherEntity && this.equipDirtForLogPillar();
        if (!replaceable || !openBodySpace || otherEntity || !equippedDirt) {
            return false;
        }

        this.logPillarPlacePos = feet.immutable();
        this.logPillarJumpDelayTicks = LOG_PILLAR_JUMP_WINDUP_TICKS;
        this.logPillarPlaceDelayTicks = LOG_PILLAR_PLACE_DELAY_TICKS;
        this.logPillarPlaceWaitTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.lookDownAt(this.logPillarPlacePos);
        this.updateTaskDetail(serverLevel);
        return true;
    }

    private void tickLogPillarPlacement(ServerLevel serverLevel) {
        if (this.logPillarPlacePos == null) {
            return;
        }

        if (this.logPillarJumpDelayTicks > 0) {
            if (!this.equipDirtForLogPillar()) {
                this.failLogPillarPlacement(serverLevel);
                return;
            }
            this.playerNpc.getNavigation().stop();
            this.lookDownAt(this.logPillarPlacePos);
            this.logPillarJumpDelayTicks--;
            if (this.logPillarJumpDelayTicks <= 0) {
                this.playerNpc.shortPillarJump();
            }
            return;
        }

        if (this.logPillarPlaceDelayTicks > 0) {
            this.logPillarPlaceDelayTicks--;
            return;
        }

        this.logPillarPlaceWaitTicks++;
        if (this.logPillarPlaceWaitTicks > LOG_PILLAR_MAX_PLACE_WAIT_TICKS) {
            this.failLogPillarPlacement(serverLevel);
            return;
        }

        if (!this.hasLogPillarPlacementClearance()) {
            this.lookDownAt(this.logPillarPlacePos);
            return;
        }

        if (!serverLevel.getBlockState(this.logPillarPlacePos).canBeReplaced()
                || !this.equipDirtForLogPillar()) {
            this.failLogPillarPlacement(serverLevel);
            return;
        }

        ItemStack dirtStack = this.playerNpc.getMainHandItem();
        if (!dirtStack.is(Items.DIRT)) {
            this.failLogPillarPlacement(serverLevel);
            return;
        }

        BlockState dirtState = Blocks.DIRT.defaultBlockState();
        if (!this.canPlaceLogPillarWithoutClipping(serverLevel, this.logPillarPlacePos, dirtState)) {
            this.lookDownAt(this.logPillarPlacePos);
            return;
        }

        this.lookDownAt(this.logPillarPlacePos);
        if (!serverLevel.setBlockAndUpdate(this.logPillarPlacePos, dirtState)) {
            this.failLogPillarPlacement(serverLevel);
            return;
        }
        this.snapAboveLogPillarIfNeeded(this.logPillarPlacePos);
        this.playerNpc.triggerMainHandUseAnimation();
        PlayerNpcBlockSoundUtil.playPlaceSound(serverLevel, this.logPillarPlacePos, dirtState, this.playerNpc);
        BlockPos placedPos = this.logPillarPlacePos.immutable();
        dirtStack.shrink(1);
        if (dirtStack.isEmpty()) {
            this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        this.logPillarPlacePos = null;
        this.logPillarJumpDelayTicks = 0;
        this.logPillarPlaceDelayTicks = 0;
        this.logPillarPlaceWaitTicks = 0;
        this.logPillarFailedSteps = 0;
        this.updateTaskDetail(serverLevel);
    }

    private void failLogPillarPlacement(ServerLevel serverLevel) {
        this.logPillarPlacePos = null;
        this.logPillarJumpDelayTicks = 0;
        this.logPillarPlaceDelayTicks = 0;
        this.logPillarPlaceWaitTicks = 0;
        this.logPillarFailedSteps++;
        if (this.logPillarFailedSteps < LOG_PILLAR_MAX_FAILED_STEPS) {
            return;
        }

        BlockPos skipped = this.targetPos == null ? null : this.targetPos.immutable();
        if (skipped != null) {
            this.skippedTreeTargets.add(skipped);
        }
        this.logPillarFailedSteps = 0;
        this.playerNpc.setCurrentAiDetail("pillar blocked; trying another tree block");
        if (!this.switchToTreeLeafClearTarget(serverLevel)
                && (skipped == null || !this.switchToNextLog(serverLevel, skipped))) {
            this.failedToGather = true;
            this.targetPos = null;
            this.standPos = null;
        }
    }



    private boolean hasLogPillarPlacementClearance() {
        if (this.logPillarPlacePos == null) {
            return false;
        }

        double clearedY = this.playerNpc.getBoundingBox().minY - this.logPillarPlacePos.getY();
        return clearedY >= LOG_PILLAR_PLACE_CLEARANCE_Y
                || this.logPillarPlaceWaitTicks >= LOG_PILLAR_FORCE_PLACE_TICKS
                && clearedY >= LOG_PILLAR_FALLBACK_PLACE_CLEARANCE_Y
                && this.playerNpc.getDeltaMovement().y <= 0.05D;
    }

    private boolean canPlaceLogPillarWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (this.hasOtherEntityInBlock(serverLevel, pos)) {
            return false;
        }

        List<AABB> boxes = state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .map(box -> box.move(pos))
                .toList();
        if (boxes.stream().noneMatch(box -> box.intersects(this.playerNpc.getBoundingBox().inflate(0.02D)))) {
            return true;
        }

        double snapUp = pos.getY() + 1.0D - this.playerNpc.getBoundingBox().minY;
        if (snapUp < -0.05D || snapUp > 0.35D) {
            return false;
        }

        AABB snappedBox = this.playerNpc.getBoundingBox().move(0.0D, snapUp + 0.01D, 0.0D);
        return boxes.stream().noneMatch(box -> box.intersects(snappedBox.inflate(0.001D)))
                && serverLevel.noCollision(this.playerNpc, snappedBox);
    }

    private void snapAboveLogPillarIfNeeded(BlockPos pos) {
        double topY = pos.getY() + 1.0D;
        if (this.playerNpc.getBoundingBox().minY >= topY) {
            return;
        }

        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.setPos(this.playerNpc.getX(), topY, this.playerNpc.getZ());
        this.playerNpc.setDeltaMovement(motion.x, Math.max(0.0D, motion.y), motion.z);
        this.playerNpc.fallDistance = 0.0F;
    }

    private boolean hasOtherEntityInBlock(ServerLevel serverLevel, BlockPos pos) {
        return !serverLevel.getEntities(
                this.playerNpc,
                new AABB(pos).inflate(0.05D),
                entity -> entity.isAlive() && !(entity instanceof ItemEntity)
        ).isEmpty();
    }

    private boolean canPillarReachLog(ServerLevel serverLevel, BlockPos feet, BlockPos target) {
        int availableDirt = this.countDirtBlocks();
        if (availableDirt <= 0) {
            return false;
        }

        int maxPlacements = Math.min(availableDirt, Math.max(1, target.getY() - feet.getY() + 1));
        for (int placed = 0; placed <= maxPlacements; placed++) {
            BlockPos feetAtHeight = feet.above(placed);
            if (!this.hasOpenBodySpace(serverLevel, feetAtHeight)) {
                return false;
            }
            if (feetAtHeight.distSqr(target) <= BREAK_DISTANCE_SQR + 1.0D) {
                return true;
            }
        }
        return false;
    }

    private boolean isAtLogPillarBase() {
        if (this.standPos == null) {
            return true;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        return feet.getY() >= this.standPos.getY()
                && feet.getX() == this.standPos.getX()
                && feet.getZ() == this.standPos.getZ()
                && this.playerNpc.distanceToSqr(
                this.standPos.getX() + 0.5D,
                feet.getY(),
                this.standPos.getZ() + 0.5D
        ) <= LOG_PILLAR_BASE_REACHED_SQR;
    }

    private boolean hasOpenBodySpace(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
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

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private boolean canReachStand(ServerLevel serverLevel, BlockPos pos) {
        if (!this.canStandAt(serverLevel, pos)) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (feet.equals(pos)
                || this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) <= 1.5D * 1.5D) {
            return true;
        }

        Path path = this.playerNpc.getNavigation().createPath(pos, 0);
        return path != null && path.canReach()
                || this.hasLocalPathObstructionToward(serverLevel, pos);
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(this.playerNpc);
        return homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), pos);
    }

    private boolean isInsideResourceRadius(BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideActivityRadius(this.playerNpc, pos, LOCAL_RESOURCE_RADIUS, true);
    }

    private boolean isWantedBlock(BlockState state) {
        return state.is(BlockTags.LOGS)
                || state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.COAL_ORE)
                || state.is(Blocks.DEEPSLATE_COAL_ORE)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK);
    }

    private boolean isCurrentTargetBlock(BlockState state) {
        return this.targetType == MaterialTarget.LOG
                ? state.is(BlockTags.LOGS) || this.isTreeLeaf(state)
                : this.isWantedBlockForTarget(state);
    }

    private boolean isTreeLeaf(BlockState state) {
        return state.is(BlockTags.LEAVES);
    }

    private boolean isWantedBlockForTarget(BlockState state) {
        return switch (this.targetType) {
            case LOG -> state.is(BlockTags.LOGS);
            case DIRT -> state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK);
            case SAND -> state.is(Blocks.SAND) || state.is(Blocks.RED_SAND);
            case STONE -> this.isStoneBlock(state);
            case COAL -> this.isCoalOre(state);
            case GENERAL -> this.isWantedBlock(state);
        };
    }

    private MaterialTarget chooseTargetType(ServerLevel serverLevel) {
        int rawLogCount = this.countRawLogs();
        int rawLogTarget = this.playerNpc.getRawLogReserveTarget();
        if (rawLogCount < rawLogTarget) {
            this.logReserveTarget = rawLogTarget;
            return MaterialTarget.LOG;
        }
        if (this.logReserveTarget > 0) {
            this.logReserveTarget = 0;
            this.dirtPillarReserveTarget = 0;
        }

        int woodCount = this.countWood();
        int stoneCount = this.countStone();
        if (!this.hasTool(AxeItem.class) || !this.hasTool(PickaxeItem.class)) {
            return MaterialTarget.LOG;
        }

        if (this.needsStarterGear() && woodCount < STARTER_WOOD_TARGET) {
            return MaterialTarget.LOG;
        }

        if (this.hasTool(PickaxeItem.class) && this.needsStoneGear() && stoneCount < STONE_GEAR_STONE_TARGET) {
            return MaterialTarget.STONE;
        }

        if (this.hasTool(PickaxeItem.class) && stoneCount < this.playerNpc.getCobblestoneSupplyTarget()) {
            return MaterialTarget.STONE;
        }

        if (this.hasTool(ShovelItem.class) && this.needsBuildGlassSand(serverLevel)) {
            return MaterialTarget.SAND;
        }

        if (woodCount < this.playerNpc.getWoodSupplyTarget()) {
            return MaterialTarget.LOG;
        }

        if (this.hasTool(PickaxeItem.class) && this.shouldGatherCoal()) {
            return MaterialTarget.COAL;
        }

        if (this.needsStoneGear() && woodCount < 6) {
            return MaterialTarget.LOG;
        }

        return MaterialTarget.GENERAL;
    }

    private boolean needsBuildMaterialReserves(ServerLevel serverLevel) {
        return this.playerNpc.shouldPrioritizeLogGathering()
                || this.playerNpc.shouldPrioritizeCobblestoneGathering()
                || this.needsBuildGlassSand(serverLevel);
    }

    private boolean needsBuildGlassSand(ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        Optional<String> layoutId = PlayerNpcHomeUtil.getHomeLayoutId(this.playerNpc);
        if (home.isEmpty() || layoutId.isEmpty()) {
            return false;
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcBuildLayoutLoader.getLayout(layoutId.get());
        if (layout.isEmpty()) {
            return false;
        }

        BlockPos origin = home.get().origin();
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.get().blocks()) {
            if (block.optional() || PlayerNpcBuildMaterialUtil.matches(serverLevel.getBlockState(block.toWorld(origin)), block.state())) {
                continue;
            }
            if (PlayerNpcBuildMaterialUtil.hasMaterialFor(serverLevel, this.playerNpc, block, origin)) {
                return false;
            }
            return PlayerNpcBuildMaterialUtil.needsSandForBuildMaterial(serverLevel, this.playerNpc, block, origin);
        }
        return false;
    }

    private void ensureDirtPillarReserveTarget() {
        if (this.dirtPillarReserveTarget <= 0) {
            this.dirtPillarReserveTarget = MIN_DIRT_PILLAR_RESERVE + this.playerNpc.getRandom().nextInt(MAX_DIRT_PILLAR_RESERVE - MIN_DIRT_PILLAR_RESERVE + 1);
        }
    }

    private boolean shouldCraftBeforeGathering(ServerLevel serverLevel) {
        boolean hasCraftingTable = this.hasNearbyCraftingTable(serverLevel);
        boolean canCraftNow;
        if (this.needsStarterGear()) {
            if (hasCraftingTable) {
                canCraftNow = this.canCraftStarterGear();
            } else {
                canCraftNow = this.canPlaceTableAndCraftStarterGear() && this.canCraftingGoalPlaceTable(serverLevel);
            }
        } else {
            canCraftNow = hasCraftingTable && this.canCraftStoneGear();
        }

        if (!canCraftNow) {
            return false;
        }
        if (this.playerNpc.getCraftGearCooldown() <= 0) {
            return true;
        }
        if (this.needsCriticalGatherTool()) {
            this.playerNpc.setCraftGearCooldown(0);
            return true;
        }
        return false;
    }

    private boolean canPlaceTableAndCraftStarterGear() {
        int rawLogReserve = this.rawLogReserveForStarterCrafting();
        int tableCost = InventoryUtils.hasItem(this.playerNpc, Items.CRAFTING_TABLE) ? 0 : 4;
        int woodAfterTable = this.countWood(rawLogReserve) - tableCost;
        if (woodAfterTable < 0) {
            return false;
        }

        if (!this.hasTool(AxeItem.class)) {
            return woodAfterTable >= 5;
        }
        if (!this.hasTool(PickaxeItem.class)) {
            return woodAfterTable >= 5;
        }
        return !this.hasTool(SwordItem.class) && woodAfterTable >= 4
                || !this.hasTool(ShovelItem.class) && woodAfterTable >= 3;
    }

    private boolean canCraftingGoalPlaceTable(ServerLevel serverLevel) {
        return (!this.isNearSavedHome() || this.needsCriticalGatherTool())
                && !this.hasValidTemporaryCraftingTable(serverLevel)
                && this.findCraftingTablePlacement(serverLevel) != null;
    }

    private boolean isNearSavedHome() {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return false;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        BlockPos homeCenter = homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
        return this.playerNpc.distanceToSqr(homeCenter.getX() + 0.5D, homeCenter.getY(), homeCenter.getZ() + 0.5D) <= 48.0D * 48.0D;
    }

    private boolean hasValidTemporaryCraftingTable(ServerLevel serverLevel) {
        BlockPos pos = this.getTemporaryCraftingTablePos();
        return pos != null && serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    private BlockPos getTemporaryCraftingTablePos() {
        if (!this.playerNpc.getPersistentData().contains(CraftBasicGearGoal.TEMP_TABLE_X)) {
            return null;
        }

        return new BlockPos(
                this.playerNpc.getPersistentData().getInt(CraftBasicGearGoal.TEMP_TABLE_X),
                this.playerNpc.getPersistentData().getInt(CraftBasicGearGoal.TEMP_TABLE_Y),
                this.playerNpc.getPersistentData().getInt(CraftBasicGearGoal.TEMP_TABLE_Z)
        );
    }

    private BlockPos findCraftingTablePlacement(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        BlockPos[] candidates = {
                origin.relative(this.playerNpc.getDirection()),
                origin.relative(this.playerNpc.getDirection().getClockWise()),
                origin.relative(this.playerNpc.getDirection().getCounterClockWise()),
                origin.relative(this.playerNpc.getDirection().getOpposite()),
                origin
        };

        for (BlockPos candidate : candidates) {
            if (serverLevel.getBlockState(candidate).isAir()
                    && serverLevel.getBlockState(candidate.below()).isSolidRender(serverLevel, candidate.below())) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private boolean needsStarterGear() {
        return !this.hasTool(PickaxeItem.class)
                || !this.hasTool(AxeItem.class)
                || !this.hasTool(SwordItem.class);
    }

    private boolean needsCriticalGatherTool() {
        return !this.hasTool(AxeItem.class) || !this.hasTool(PickaxeItem.class);
    }

    private boolean needsStoneGear() {
        return !this.hasItem(Items.STONE_PICKAXE)
                || !this.hasItem(Items.STONE_AXE)
                || !this.hasItem(Items.STONE_SWORD)
                || !this.hasItem(Items.STONE_SHOVEL);
    }

    private boolean canCraftStarterGear() {
        int woodCount = this.countWood(this.rawLogReserveForStarterCrafting());
        if (!this.hasTool(AxeItem.class)) {
            return woodCount >= 5;
        }
        if (!this.hasTool(PickaxeItem.class)) {
            return woodCount >= 5;
        }
        return !this.hasTool(SwordItem.class) && woodCount >= 4
                || !this.hasTool(ShovelItem.class) && woodCount >= 3;
    }

    private boolean canCraftStoneGear() {
        if (!this.hasTool(PickaxeItem.class)) {
            return false;
        }

        int stoneCount = this.countStone();
        return !this.hasItem(Items.STONE_PICKAXE) && stoneCount >= 3 && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, 2, this.playerNpc.getRawLogReserveTarget())
                || !this.hasItem(Items.STONE_AXE) && stoneCount >= 3 && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, 2, this.playerNpc.getRawLogReserveTarget())
                || !this.hasItem(Items.STONE_SWORD) && stoneCount >= 2 && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, 1, this.playerNpc.getRawLogReserveTarget())
                || !this.hasItem(Items.STONE_SHOVEL) && stoneCount >= 1 && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, 2, this.playerNpc.getRawLogReserveTarget());
    }

    private boolean isStoneBlock(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.FURNACE)
                || state.is(BlockTags.MINEABLE_WITH_PICKAXE);
    }

    private boolean isShovelBlock(BlockState state) {
        return state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(BlockTags.MINEABLE_WITH_SHOVEL);
    }

    private boolean isCoalOre(BlockState state) {
        return state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE);
    }

    private boolean hasRequiredToolFor(BlockState state) {
        if (this.isShovelBlock(state)) {
            return this.hasTool(ShovelItem.class);
        }
        if (this.isStoneBlock(state) || this.isCoalOre(state)) {
            return this.hasTool(PickaxeItem.class);
        }
        return true;
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockState state) {
        return this.getRequiredMineTicks(serverLevel, this.targetPos, state);
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(serverLevel, pos);
        if (hardness < 0.0F) {
            return MAX_GATHER_TICKS;
        }

        ItemStack heldStack = this.playerNpc.getMainHandItem();
        float toolSpeed = heldStack.isEmpty() ? 1.0F : heldStack.getDestroySpeed(state);
        if (toolSpeed <= 0.0F) {
            toolSpeed = 1.0F;
        }

        boolean correctTool = !state.requiresCorrectToolForDrops() || heldStack.isCorrectToolForDrops(state);
        float progressPerTick = toolSpeed / hardness / (correctTool ? 30.0F : 100.0F);
        if (progressPerTick <= 0.0F) {
            return MAX_GATHER_TICKS;
        }

        return Math.max(1, (int) Math.ceil(1.0F / progressPerTick));
    }

    private boolean equipToolFor(BlockState state) {
        if (this.isShovelBlock(state)) {
            return this.equipTool(ShovelItem.class);
        } else if (state.is(BlockTags.LOGS) || state.is(BlockTags.MINEABLE_WITH_AXE) || state.is(Blocks.CRAFTING_TABLE)) {
            if (!this.equipTool(AxeItem.class)) {
                this.equipEmptyHandForMining();
            }
            return true;
        } else if (this.isTreeLeaf(state)) {
            this.equipEmptyHandForMining();
            return true;
        } else if (this.isStoneBlock(state) || this.isCoalOre(state) || state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return this.equipTool(PickaxeItem.class);
        }
        return true;
    }

    private int getMaxGatherTicks() {
        return MAX_GATHER_TICKS;
    }

    private boolean equipTool(Class<?> toolClass) {
        if (toolClass.isInstance(this.playerNpc.getMainHandItem().getItem())) {
            return true;
        }
        if (this.restorePreviousMainHandForTool(toolClass)) {
            return true;
        }

        ItemStack tool = this.playerNpc.consumeInventoryItem(stack -> toolClass.isInstance(stack.getItem()), 1)
                .orElse(ItemStack.EMPTY);
        if (tool.isEmpty()) {
            return false;
        }

        this.setTemporaryMainHand(tool);
        return true;
    }

    private void equipEmptyHandForMining() {
        if (this.playerNpc.getMainHandItem().isEmpty()) {
            return;
        }

        this.setTemporaryMainHand(ItemStack.EMPTY);
    }

    private boolean equipDirtForLogPillar() {
        if (this.playerNpc.getMainHandItem().is(Items.DIRT)) {
            return true;
        }

        ItemStack dirt = this.playerNpc.consumeInventoryItem(Items.DIRT, 1).orElse(ItemStack.EMPTY);
        if (dirt.isEmpty()) {
            return false;
        }

        this.setTemporaryMainHand(dirt);
        return true;
    }

    private void setTemporaryMainHand(ItemStack stack) {
        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryTool) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryTool = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, stack);
    }

    private boolean restorePreviousMainHandForTool(Class<?> toolClass) {
        if (!this.usingTemporaryTool || !toolClass.isInstance(this.previousMainHand.getItem())) {
            return false;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        return true;
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryTool) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)) {
            if (!InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
                this.playerNpc.spawnAtLocation(currentMainHand);
            }
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
    }

    private boolean hasTool(Class<?> toolClass) {
        if (toolClass.isInstance(this.playerNpc.getMainHandItem().getItem())) {
            return true;
        }
        if (this.usingTemporaryTool && toolClass.isInstance(this.previousMainHand.getItem())) {
            return true;
        }
        return InventoryUtils.hasItem(this.playerNpc, stack -> toolClass.isInstance(stack.getItem()));
    }

    private boolean hasItem(net.minecraft.world.level.ItemLike itemLike) {
        return this.playerNpc.getMainHandItem().is(itemLike.asItem())
                || InventoryUtils.hasItem(this.playerNpc, itemLike);
    }

    private int countWood() {
        return PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget());
    }

    private int countWood(int rawLogReserve) {
        return PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), rawLogReserve);
    }

    private int rawLogReserveForStarterCrafting() {
        return this.needsCriticalGatherTool() ? 0 : this.playerNpc.getRawLogReserveTarget();
    }

    private boolean canCraftAxeNow(ServerLevel serverLevel) {
        if (this.hasTool(AxeItem.class)) {
            return false;
        }
        if (this.hasNearbyCraftingTable(serverLevel)) {
            return this.canProvideAxeIngredients(0, 0);
        }
        if (!this.canCraftingGoalPlaceTable(serverLevel)) {
            return false;
        }
        int tableCost = InventoryUtils.hasItem(this.playerNpc, Items.CRAFTING_TABLE) ? 0 : 4;
        return this.canProvideAxeIngredients(0, tableCost);
    }

    private boolean canProvideAxeIngredients(int rawLogReserve, int reservedPlanks) {
        int availablePlanks = PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), rawLogReserve) - reservedPlanks;
        if (availablePlanks < 0) {
            return false;
        }

        int availableSticks = PlayerNpcCraftingUtil.countSticks(this.playerNpc.getInventory());
        int missingSticks = Math.max(0, 2 - availableSticks);
        int planksForSticks = ((missingSticks + 3) / 4) * 2;
        return availablePlanks >= 3 + planksForSticks
                || this.countStone() >= 3 && availablePlanks >= planksForSticks;
    }

    private int countRawLogs() {
        return this.countItem(stack -> stack.is(ItemTags.LOGS));
    }

    private int countDirtBlocks() {
        int count = this.playerNpc.getMainHandItem().is(Items.DIRT) ? this.playerNpc.getMainHandItem().getCount() : 0;
        return count + this.countItem(stack -> stack.is(Items.DIRT));
    }

    private int countStone() {
        return this.countItem(stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    private int countItem(java.util.function.Predicate<ItemStack> matcher) {
        int count = 0;
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean shouldGatherCoal() {
        int torchFuelCount = this.countTorchFuel();
        if (torchFuelCount < COAL_RESERVE_TARGET) {
            return true;
        }

        return torchFuelCount < ACTIVE_FURNACE_COAL_RESERVE_TARGET
                && (this.hasRawFood() || this.hasSmeltableMaterial() || this.hasFuelHungryFurnaceWork());
    }

    private int countTorchFuel() {
        return this.countItem(stack -> stack.is(Items.COAL) || stack.is(Items.CHARCOAL));
    }

    private boolean hasRawFood() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> stack.is(Items.BEEF)
                || stack.is(Items.PORKCHOP)
                || stack.is(Items.CHICKEN)
                || stack.is(Items.MUTTON)
                || stack.is(Items.RABBIT)
                || stack.is(Items.COD)
                || stack.is(Items.SALMON)
                || stack.is(Items.POTATO));
    }

    private boolean hasSmeltableMaterial() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> stack.is(Items.COBBLESTONE)
                || stack.is(Items.COBBLED_DEEPSLATE)
                || this.shouldSmeltSandForGlass(stack)
                || stack.is(Items.RAW_IRON)
                || stack.is(Items.RAW_COPPER)
                || stack.is(Items.RAW_GOLD)
                || stack.is(Items.IRON_ORE)
                || stack.is(Items.DEEPSLATE_IRON_ORE)
                || stack.is(Items.COPPER_ORE)
                || stack.is(Items.DEEPSLATE_COPPER_ORE)
                || stack.is(Items.GOLD_ORE)
                || stack.is(Items.DEEPSLATE_GOLD_ORE));
    }

    private boolean shouldSmeltSandForGlass(ItemStack stack) {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && PlayerNpcBuildMaterialUtil.isGlassSmeltingInput(stack)
                && PlayerNpcBuildMaterialUtil.needsGlassSmelting(serverLevel, this.playerNpc);
    }

    private boolean hasFuelHungryFurnaceWork() {
        return (this.hasRawFood() || this.hasSmeltableMaterial()) && !this.hasFuel();
    }

    private boolean hasFuel() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> !stack.isEmpty() && AbstractFurnaceBlockEntity.isFuel(stack));
    }

    private boolean hasNearbyCraftingTable(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-5, -2, -5), origin.offset(5, 2, 5))) {
            if (serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }

    private void updateTaskDetail(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        BlockState state = serverLevel.getBlockState(this.targetPos);
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String blockName = blockId == null ? state.getBlock().getDescriptionId() : blockId.toString();
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, state);
        boolean inBreakRange = this.playerNpc.distanceToSqr(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D
        ) <= BREAK_DISTANCE_SQR;
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "%s\n@ %d %d %d %s\ngiving up in %ds",
                blockName,
                this.targetPos.getX(),
                this.targetPos.getY(),
                this.targetPos.getZ(),
                inBreakRange ? String.format(java.util.Locale.ROOT, "%d/%dt", Math.min(this.mineTicks, requiredMineTicks), requiredMineTicks) : "walking",
                this.getRemainingGatherSeconds()
        ));
    }

    private void updatePathObstructionDetail(ServerLevel serverLevel, BlockState state, int requiredMineTicks) {
        if (this.pathObstructionPos == null) {
            return;
        }

        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String blockName = blockId == null ? state.getBlock().getDescriptionId() : blockId.toString();
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "clearing path %s\n@ %d %d %d %d/%dt\ngiving up in %ds",
                blockName,
                this.pathObstructionPos.getX(),
                this.pathObstructionPos.getY(),
                this.pathObstructionPos.getZ(),
                Math.min(this.pathObstructionMineTicks, requiredMineTicks),
                requiredMineTicks,
                this.getRemainingGatherSeconds()
        ));
    }

    private int getRemainingGatherSeconds() {
        int remainingTicks = Math.max(0, this.getMaxGatherTicks() - this.gatherTicks);
        return Math.max(0, (remainingTicks + 19) / 20);
    }

    private int getMaxGatherSeconds() {
        return Math.max(1, this.getMaxGatherTicks() / 20);
    }

    private boolean inventoryIsMostlyFull() {
        int freeSlots = 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            if (this.playerNpc.getInventory().getItem(i).isEmpty()) {
                freeSlots++;
            }
        }
        return freeSlots <= 2 && !InventoryUtils.hasPlaceableBlock(this.playerNpc);
    }

    private enum MaterialTarget {
        LOG,
        DIRT,
        SAND,
        STONE,
        COAL,
        GENERAL
    }

    private record GatherTarget(BlockPos targetPos, BlockPos standPos) {}
}
