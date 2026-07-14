package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class WaterEscapeAi {
    public enum TickResult {
        NOT_NEEDED,
        RUNNING,
        DONE,
        FAILED
    }

    private static final int MAX_ESCAPE_TICKS = 20 * 3;
    private static final double MIN_FLOW_STRENGTH_SQR = 0.0004D;
    private static final int PLACE_DELAY_TICKS = 2;
    private static final int MAX_PLACE_WAIT_TICKS = 30;
    private static final double STAND_PLACE_CLEARANCE_Y = 0.92D;
    private static final double FALLBACK_STAND_PLACE_CLEARANCE_Y = 0.68D;

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private BlockPos waterPos;
    private BlockPos plugPos;
    private BlockPos standPlacePos;
    private BlockPos dryExitPos;
    private boolean placingStandBlock;
    private boolean movingToDryExit;
    private int escapeTicks;
    private int placeDelayTicks;
    private int placeWaitTicks;
    private String detail = "";

    public WaterEscapeAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
    }

    public boolean canStart(ServerLevel serverLevel) {
        if (!this.isInWater(serverLevel)) {
            return false;
        }
        BlockPos feet = this.playerNpc.blockPosition();
        return this.findDryStepOut(serverLevel, feet) != null
                || this.findWaterStandTarget(serverLevel) != null
                || this.findWaterCurrentTarget(serverLevel) != null;
    }

    public boolean isRunning() {
        return this.waterPos != null || this.dryExitPos != null || this.standPlacePos != null || this.plugPos != null;
    }

    public String detail() {
        return this.detail;
    }

    public TickResult tick(ServerLevel serverLevel, double speed) {
        if (!this.isInWater(serverLevel)) {
            boolean wasRunning = this.isRunning();
            this.stop();
            return wasRunning ? TickResult.DONE : TickResult.NOT_NEEDED;
        }

        if (!this.isRunning() && !this.start(serverLevel)) {
            this.jumpUpFromWater(serverLevel);
            this.detail = "water escape: jumping";
            return TickResult.FAILED;
        }

        if (this.escapeTicks++ >= MAX_ESCAPE_TICKS) {
            this.stop();
            return TickResult.FAILED;
        }

        if (this.movingToDryExit) {
            return this.tickDryExit(serverLevel, speed);
        }
        if (this.placingStandBlock) {
            return this.tickStandPlacement(serverLevel);
        }

        this.jumpAgainstCurrent(serverLevel);
        this.updateDetail();
        if (this.plugPos != null && this.escapeTicks % 4 == 0 && this.tryPlugWater(serverLevel, this.plugPos)) {
            this.stop();
            return TickResult.DONE;
        }
        return TickResult.RUNNING;
    }

    public void stop() {
        this.waterPos = null;
        this.plugPos = null;
        this.standPlacePos = null;
        this.dryExitPos = null;
        this.placingStandBlock = false;
        this.movingToDryExit = false;
        this.escapeTicks = 0;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.detail = "";
    }

    private boolean start(ServerLevel serverLevel) {
        this.escapeTicks = 0;
        this.placeDelayTicks = PLACE_DELAY_TICKS;
        this.placeWaitTicks = 0;

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos dryExit = this.findDryStepOut(serverLevel, feet);
        if (dryExit != null) {
            this.waterPos = feet.immutable();
            this.dryExitPos = dryExit;
            this.plugPos = null;
            this.standPlacePos = null;
            this.placingStandBlock = false;
            this.movingToDryExit = true;
            this.updateDetail();
            return true;
        }

        WaterStandTarget standTarget = this.findWaterStandTarget(serverLevel);
        if (standTarget != null) {
            this.waterPos = standTarget.waterPos();
            this.standPlacePos = standTarget.placePos();
            this.dryExitPos = null;
            this.plugPos = null;
            this.placingStandBlock = true;
            this.movingToDryExit = false;
            this.updateDetail();
            return true;
        }

        WaterCurrentTarget currentTarget = this.findWaterCurrentTarget(serverLevel);
        if (currentTarget == null) {
            return false;
        }

        this.waterPos = currentTarget.waterPos();
        this.plugPos = currentTarget.plugPos();
        this.standPlacePos = null;
        this.dryExitPos = null;
        this.placingStandBlock = false;
        this.movingToDryExit = false;
        this.updateDetail();
        return true;
    }

    private TickResult tickDryExit(ServerLevel serverLevel, double speed) {
        if (this.dryExitPos == null) {
            this.stop();
            return TickResult.FAILED;
        }

        if (!this.isInWater(serverLevel)) {
            this.stop();
            return TickResult.DONE;
        }

        if (!this.canStandDryAt(serverLevel, this.dryExitPos)) {
            this.dryExitPos = this.findDryStepOut(serverLevel, this.playerNpc.blockPosition());
            if (this.dryExitPos == null) {
                this.stop();
                return TickResult.FAILED;
            }
        }

        this.waterPos = this.playerNpc.blockPosition().immutable();
        this.playerNpc.getNavigation().stop();
        this.jumpUpFromWater(serverLevel);
        this.playerNpc.getLookControl().setLookAt(
                this.dryExitPos.getX() + 0.5D,
                this.dryExitPos.getY() + 0.5D,
                this.dryExitPos.getZ() + 0.5D,
                50.0F,
                50.0F
        );
        this.playerNpc.getMoveControl().setWantedPosition(
                this.dryExitPos.getX() + 0.5D,
                this.dryExitPos.getY(),
                this.dryExitPos.getZ() + 0.5D,
                speed
        );
        this.updateDetail();
        return TickResult.RUNNING;
    }

    private TickResult tickStandPlacement(ServerLevel serverLevel) {
        if (this.standPlacePos == null) {
            this.stop();
            return TickResult.FAILED;
        }

        this.playerNpc.getNavigation().stop();
        this.jumpUpFromWater(serverLevel);
        this.playerNpc.getLookControl().setLookAt(
                this.standPlacePos.getX() + 0.5D,
                this.standPlacePos.getY() + 0.5D,
                this.standPlacePos.getZ() + 0.5D,
                50.0F,
                50.0F
        );
        this.updateDetail();

        if (!this.canPlaceStandBlockAt(serverLevel, this.standPlacePos)) {
            WaterStandTarget target = this.findWaterStandTarget(serverLevel);
            if (target == null) {
                this.stop();
                return TickResult.DONE;
            }
            this.waterPos = target.waterPos();
            this.standPlacePos = target.placePos();
            this.placeDelayTicks = PLACE_DELAY_TICKS;
            this.placeWaitTicks = 0;
            return TickResult.RUNNING;
        }

        if (this.placeDelayTicks > 0) {
            this.placeDelayTicks--;
            return TickResult.RUNNING;
        }

        this.placeWaitTicks++;
        if (!this.hasStandPlacementClearance(this.standPlacePos)) {
            if (this.placeWaitTicks > MAX_PLACE_WAIT_TICKS) {
                this.stop();
                return TickResult.FAILED;
            }
            return TickResult.RUNNING;
        }

        if (this.tryPlaceStandBlock(serverLevel, this.standPlacePos)) {
            this.stop();
            return TickResult.DONE;
        }

        if (this.placeWaitTicks > MAX_PLACE_WAIT_TICKS) {
            this.stop();
            return TickResult.FAILED;
        }
        return TickResult.RUNNING;
    }

    private WaterCurrentTarget findWaterCurrentTarget(ServerLevel serverLevel) {
        if (!this.playerNpc.isInWaterOrBubble()
                && !serverLevel.getFluidState(this.playerNpc.blockPosition()).is(FluidTags.WATER)) {
            return null;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> waterCandidates = new ArrayList<>();
        waterCandidates.add(feet);
        waterCandidates.add(feet.above());
        waterCandidates.add(feet.below());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            waterCandidates.add(feet.relative(direction));
            waterCandidates.add(feet.relative(direction).above());
        }

        WaterCurrentTarget best = null;
        double bestScore = 0.0D;
        for (BlockPos candidate : waterCandidates) {
            FluidState fluidState = serverLevel.getFluidState(candidate);
            if (!isFlowingWater(serverLevel, candidate, fluidState)) {
                continue;
            }

            double flowStrength = horizontalFlowStrengthSqr(serverLevel, candidate, fluidState);
            BlockPos plug = this.findPlugPos(serverLevel, candidate);
            double score = flowStrength + (candidate.equals(feet) ? 1.0D : 0.0D) + (plug != null ? 0.25D : 0.0D);
            if (best == null || score > bestScore) {
                best = new WaterCurrentTarget(candidate.immutable(), plug);
                bestScore = score;
            }
        }
        return best;
    }

    private WaterStandTarget findWaterStandTarget(ServerLevel serverLevel) {
        if (!this.isInWater(serverLevel) || !this.hasWaterPlugBlock()) {
            return null;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.findDryStepOut(serverLevel, feet) != null) {
            return null;
        }

        BlockPos place = this.findStandPlacePos(serverLevel, feet);
        return place == null ? null : new WaterStandTarget(feet.immutable(), place);
    }

    private BlockPos findStandPlacePos(ServerLevel serverLevel, BlockPos feet) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(feet);
        candidates.add(feet.below());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(feet.relative(direction));
        }

        candidates.sort(Comparator.comparingDouble(feet::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canPlaceStandBlockAt(serverLevel, immutable)) {
                return immutable;
            }
        }
        return null;
    }

    private BlockPos findPlugPos(ServerLevel serverLevel, BlockPos waterCandidate) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(waterCandidate);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(waterCandidate.relative(direction));
            candidates.add(waterCandidate.relative(direction).below());
        }
        candidates.add(waterCandidate.below());

        BlockPos feet = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(feet::distSqr));
        for (BlockPos candidate : candidates) {
            if (this.canPlugWaterAt(serverLevel, candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private void jumpAgainstCurrent(ServerLevel serverLevel) {
        FluidState fluidState = serverLevel.getFluidState(this.waterPos);
        Vec3 flow = fluidState.getFlow(serverLevel, this.waterPos);
        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getJumpControl().jump();
        this.playerNpc.setDeltaMovement(
                motion.x - flow.x * 0.18D,
                Math.max(motion.y, 0.12D),
                motion.z - flow.z * 0.18D
        );
        this.playerNpc.hasImpulse = true;
    }

    private void jumpUpFromWater(ServerLevel serverLevel) {
        BlockPos pos = this.waterPos == null ? this.playerNpc.blockPosition() : this.waterPos;
        FluidState fluidState = serverLevel.getFluidState(pos);
        Vec3 flow = fluidState.is(FluidTags.WATER) ? fluidState.getFlow(serverLevel, pos) : Vec3.ZERO;
        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.getJumpControl().jump();
        this.playerNpc.setDeltaMovement(
                motion.x - flow.x * 0.08D,
                Math.max(motion.y, 0.22D),
                motion.z - flow.z * 0.08D
        );
        this.playerNpc.hasImpulse = true;
    }

    private boolean tryPlugWater(ServerLevel serverLevel, BlockPos pos) {
        ItemStack blockStack = this.takeWaterPlugBlock();
        if (blockStack.isEmpty() || !(blockStack.getItem() instanceof BlockItem blockItem)) {
            this.returnStack(blockStack);
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        if (!this.canPlugWaterAt(serverLevel, pos)
                || !state.canSurvive(serverLevel, pos)
                || !this.placingBlockAi.canPlaceWithoutClipping(serverLevel, pos, state)) {
            this.returnStack(blockStack);
            return false;
        }

        if (!this.placingBlockAi.placeBlock(serverLevel, pos, state)) {
            this.returnStack(blockStack);
            return false;
        }
        return true;
    }

    private boolean tryPlaceStandBlock(ServerLevel serverLevel, BlockPos pos) {
        ItemStack blockStack = this.takeWaterPlugBlock();
        if (blockStack.isEmpty() || !(blockStack.getItem() instanceof BlockItem blockItem)) {
            this.returnStack(blockStack);
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        if (!this.canPlaceStandBlockAt(serverLevel, pos)
                || !state.canSurvive(serverLevel, pos)
                || !this.placingBlockAi.canPlaceWithoutClipping(serverLevel, pos, state)) {
            this.returnStack(blockStack);
            return false;
        }

        if (!this.placingBlockAi.placeBlock(serverLevel, pos, state)) {
            this.returnStack(blockStack);
            return false;
        }
        return true;
    }

    private boolean canPlugWaterAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }

        FluidState fluidState = serverLevel.getFluidState(pos);
        return isFlowingWater(serverLevel, pos, fluidState)
                && serverLevel.getBlockState(pos).canBeReplaced()
                && !new AABB(pos).intersects(this.playerNpc.getBoundingBox().inflate(0.05D))
                && serverLevel.getEntities(this.playerNpc, new AABB(pos)).isEmpty();
    }

    private boolean canPlaceStandBlockAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }

        return serverLevel.getFluidState(pos).is(FluidTags.WATER)
                && serverLevel.getBlockState(pos).canBeReplaced()
                && serverLevel.getEntities(this.playerNpc, new AABB(pos)).isEmpty();
    }

    private boolean isWaterPlugBlock(ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || stack.is(ItemTags.SAPLINGS)
                || stack.is(Items.CRAFTING_TABLE)
                || stack.is(Items.CHEST)
                || stack.is(Items.FURNACE)
                || stack.is(Items.TORCH)
                || stack.getItem() instanceof BedItem
                || blockItem.getBlock().defaultBlockState().is(Blocks.WATER)
                || blockItem.getBlock().defaultBlockState().is(Blocks.LAVA)) {
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        return state.canOcclude()
                && !state.canBeReplaced()
                && state.getFluidState().isEmpty();
    }

    private boolean hasWaterPlugBlock() {
        return InventoryUtils.hasItem(this.playerNpc, this::isWaterPlugBlock);
    }

    private ItemStack takeWaterPlugBlock() {
        return InventoryUtils.consumeItem(this.playerNpc, this::isWaterPlugBlock, 1).orElse(ItemStack.EMPTY);
    }

    private boolean isInWater(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        return this.playerNpc.isInWaterOrBubble()
                || serverLevel.getFluidState(feet).is(FluidTags.WATER)
                || serverLevel.getFluidState(feet.above()).is(FluidTags.WATER);
    }

    private BlockPos findDryStepOut(ServerLevel serverLevel, BlockPos feet) {
        if (!this.isInWater(serverLevel)) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = feet.relative(direction);
            if (this.canStandDryAt(serverLevel, adjacent)) {
                candidates.add(adjacent.immutable());
            }

            BlockPos stepUp = adjacent.above();
            if (this.isWalkableFloor(serverLevel, adjacent) && this.canStandDryAt(serverLevel, stepUp)) {
                candidates.add(stepUp.immutable());
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }

        Vec3 look = this.playerNpc.getLookAngle();
        candidates.sort(Comparator
                .comparingDouble((BlockPos candidate) -> feet.distSqr(candidate))
                .thenComparingDouble(candidate -> -directionScore(feet, candidate, look)));
        return candidates.get(0);
    }

    private boolean canStandDryAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !serverLevel.getFluidState(pos).is(FluidTags.WATER)
                && !serverLevel.getFluidState(pos.above()).is(FluidTags.WATER)
                && !this.hasBlockingCollision(serverLevel, pos)
                && !this.hasBlockingCollision(serverLevel, pos.above())
                && this.isWalkableFloor(serverLevel, pos.below());
    }

    private boolean hasBlockingCollision(ServerLevel serverLevel, BlockPos pos) {
        return !serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty();
    }

    private boolean isWalkableFloor(ServerLevel serverLevel, BlockPos pos) {
        return !serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty();
    }

    private boolean hasStandPlacementClearance(BlockPos pos) {
        double clearedY = this.playerNpc.getBoundingBox().minY - pos.getY();
        if (clearedY >= STAND_PLACE_CLEARANCE_Y) {
            return true;
        }

        return this.placeWaitTicks >= 8
                && clearedY >= FALLBACK_STAND_PLACE_CLEARANCE_Y
                && this.playerNpc.getDeltaMovement().y <= 0.08D;
    }

    private void returnStack(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private void updateDetail() {
        if (this.waterPos == null) {
            this.detail = "";
            return;
        }

        if (this.placingStandBlock) {
            String place = this.standPlacePos == null
                    ? "finding footing"
                    : "placing footing @ " + posText(this.standPlacePos);
            this.detail = "water escape: trapped water @ " + posText(this.waterPos) + " " + place;
            return;
        }

        if (this.movingToDryExit) {
            String exit = this.dryExitPos == null
                    ? "finding dry exit"
                    : "dry exit @ " + posText(this.dryExitPos);
            this.detail = "water escape: leaving water @ " + posText(this.waterPos) + " " + exit;
            return;
        }

        String plug = this.plugPos == null
                ? "jumping"
                : "plugging @ " + posText(this.plugPos);
        this.detail = "water escape: flow @ " + posText(this.waterPos) + " " + plug;
    }

    private static boolean isFlowingWater(ServerLevel serverLevel, BlockPos pos, FluidState fluidState) {
        return fluidState.is(FluidTags.WATER)
                && !fluidState.isSource()
                && horizontalFlowStrengthSqr(serverLevel, pos, fluidState) >= MIN_FLOW_STRENGTH_SQR;
    }

    private static double horizontalFlowStrengthSqr(ServerLevel serverLevel, BlockPos pos, FluidState fluidState) {
        Vec3 flow = fluidState.getFlow(serverLevel, pos);
        return flow.x * flow.x + flow.z * flow.z;
    }

    private static double directionScore(BlockPos from, BlockPos to, Vec3 look) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length <= 0.0D) {
            return 0.0D;
        }
        return dx / length * look.x + dz / length * look.z;
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private record WaterCurrentTarget(BlockPos waterPos, BlockPos plugPos) {
    }

    private record WaterStandTarget(BlockPos waterPos, BlockPos placePos) {
    }
}
