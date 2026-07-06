package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
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
import java.util.EnumSet;
import java.util.List;

public class EscapeWaterCurrentGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20;
    private static final int MAX_ESCAPE_TICKS = 20 * 3;
    private static final double MIN_FLOW_STRENGTH_SQR = 0.0004D;
    private static final int PLACE_DELAY_TICKS = 2;
    private static final int MAX_PLACE_WAIT_TICKS = 30;
    private static final double STAND_PLACE_CLEARANCE_Y = 0.92D;
    private static final double FALLBACK_STAND_PLACE_CLEARANCE_Y = 0.68D;

    private final PlayerNpcEntity playerNpc;
    private BlockPos waterPos;
    private BlockPos plugPos;
    private BlockPos standPlacePos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private boolean showingPlacementItem;
    private boolean placingStandBlock;
    private boolean finished;
    private int escapeTicks;
    private int cooldownTicks;
    private int placeDelayTicks;
    private int placeWaitTicks;

    public EscapeWaterCurrentGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
            return false;
        }
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || "ai.player_npc.fishing".equals(this.playerNpc.getCurrentAiState())) {
            return false;
        }

        WaterStandTarget standTarget = this.findWaterStandTarget(serverLevel);
        if (standTarget != null) {
            this.waterPos = standTarget.waterPos();
            this.standPlacePos = standTarget.placePos();
            this.plugPos = null;
            this.placingStandBlock = true;
            return true;
        }

        if (!this.isActiveTravelState()) {
            return false;
        }

        WaterCurrentTarget target = this.findWaterCurrentTarget(serverLevel);
        if (target == null) {
            return false;
        }

        this.waterPos = target.waterPos();
        this.plugPos = target.plugPos();
        this.standPlacePos = null;
        this.placingStandBlock = false;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (this.escapeTicks >= MAX_ESCAPE_TICKS
                || this.finished
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.getTarget() != null
                || !(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        if (this.placingStandBlock) {
            return this.standPlacePos != null
                    && this.canPlaceStandBlockAt(serverLevel, this.standPlacePos)
                    && this.hasWaterPlugBlock();
        }

        WaterCurrentTarget target = this.findWaterCurrentTarget(serverLevel);
        if (target == null) {
            return false;
        }

        this.waterPos = target.waterPos();
        this.plugPos = target.plugPos();
        return true;
    }

    @Override
    public void start() {
        this.escapeTicks = 0;
        this.previousMainHand = ItemStack.EMPTY;
        this.showingPlacementItem = false;
        this.finished = false;
        this.placeDelayTicks = PLACE_DELAY_TICKS;
        this.placeWaitTicks = 0;
        this.playerNpc.setCurrentAiState("ai.player_npc.escaping_water_current");
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.waterPos == null) {
            return;
        }

        this.escapeTicks++;
        if (this.placingStandBlock) {
            this.tickStandPlacement(serverLevel);
            return;
        }

        this.jumpAgainstCurrent(serverLevel);
        this.updateDetail();
        if (this.plugPos != null && this.escapeTicks % 4 == 0 && this.tryPlugWater(serverLevel, this.plugPos)) {
            this.finished = true;
            this.cooldownTicks = COOLDOWN_TICKS;
        }
    }

    @Override
    public void stop() {
        this.restorePreviousMainHand();
        this.waterPos = null;
        this.plugPos = null;
        this.standPlacePos = null;
        this.escapeTicks = 0;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.placingStandBlock = false;
        this.finished = false;
        this.cooldownTicks = COOLDOWN_TICKS;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private boolean isActiveTravelState() {
        String state = this.playerNpc.getCurrentAiState();
        return !PlayerNpcEntity.AI_IDLE.equals(state)
                && !"ai.player_npc.looking_for_work".equals(state)
                && !"ai.player_npc.staying_busy".equals(state)
                && !"ai.player_npc.fishing".equals(state);
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
        if (this.hasDryStepOut(serverLevel, feet)) {
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
        this.playerNpc.getJumpControl().jump();
        this.playerNpc.setDeltaMovement(
                motion.x - flow.x * 0.18D,
                Math.max(motion.y, 0.12D),
                motion.z - flow.z * 0.18D
        );
    }

    private void tickStandPlacement(ServerLevel serverLevel) {
        if (this.standPlacePos == null) {
            this.finished = true;
            return;
        }

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
                this.finished = true;
                return;
            }
            this.waterPos = target.waterPos();
            this.standPlacePos = target.placePos();
            this.placeDelayTicks = PLACE_DELAY_TICKS;
            this.placeWaitTicks = 0;
            return;
        }

        if (this.placeDelayTicks > 0) {
            this.placeDelayTicks--;
            return;
        }

        this.placeWaitTicks++;
        if (!this.hasStandPlacementClearance(this.standPlacePos)) {
            if (this.placeWaitTicks > MAX_PLACE_WAIT_TICKS) {
                this.finished = true;
            }
            return;
        }

        if (this.tryPlaceStandBlock(serverLevel, this.standPlacePos)) {
            this.finished = true;
            this.cooldownTicks = COOLDOWN_TICKS;
            return;
        }

        if (this.placeWaitTicks > MAX_PLACE_WAIT_TICKS) {
            this.finished = true;
        }
    }

    private void jumpUpFromWater(ServerLevel serverLevel) {
        FluidState fluidState = this.waterPos == null ? serverLevel.getFluidState(this.playerNpc.blockPosition()) : serverLevel.getFluidState(this.waterPos);
        Vec3 flow = fluidState.is(FluidTags.WATER) ? fluidState.getFlow(serverLevel, this.waterPos == null ? this.playerNpc.blockPosition() : this.waterPos) : Vec3.ZERO;
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
                || !this.canPlaceWithoutClipping(serverLevel, pos, state)) {
            this.returnStack(blockStack);
            return false;
        }

        this.showPlacementItem(blockStack);
        this.playerNpc.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 50.0F, 50.0F);
        if (!serverLevel.setBlockAndUpdate(pos, state)) {
            this.returnStack(blockStack);
            return false;
        }

        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
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
                || !this.canPlaceWithoutClipping(serverLevel, pos, state)) {
            this.returnStack(blockStack);
            return false;
        }

        this.showPlacementItem(blockStack);
        if (!serverLevel.setBlockAndUpdate(pos, state)) {
            this.returnStack(blockStack);
            return false;
        }

        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
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

    private boolean canPlaceWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .noneMatch(box -> box.move(pos).intersects(this.playerNpc.getBoundingBox().inflate(0.05D)));
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
        return this.isWaterPlugBlock(this.playerNpc.getMainHandItem())
                || InventoryUtils.hasItem(this.playerNpc, this::isWaterPlugBlock);
    }

    private ItemStack takeWaterPlugBlock() {
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (this.isWaterPlugBlock(mainHand)) {
            ItemStack taken = mainHand.copy();
            taken.setCount(1);
            mainHand.shrink(1);
            if (mainHand.isEmpty()) {
                this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
            return taken;
        }

        return InventoryUtils.consumeItem(this.playerNpc, this::isWaterPlugBlock, 1).orElse(ItemStack.EMPTY);
    }

    private boolean isInWater(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        return this.playerNpc.isInWaterOrBubble()
                || serverLevel.getFluidState(feet).is(FluidTags.WATER)
                || serverLevel.getFluidState(feet.above()).is(FluidTags.WATER);
    }

    private boolean hasDryStepOut(ServerLevel serverLevel, BlockPos feet) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos adjacent = feet.relative(direction);
            if (this.canStandDryAt(serverLevel, adjacent)) {
                return true;
            }

            BlockPos stepUp = adjacent.above();
            if (this.isWalkableFloor(serverLevel, adjacent) && this.canStandDryAt(serverLevel, stepUp)) {
                return true;
            }
        }
        return false;
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

    private static boolean isFlowingWater(ServerLevel serverLevel, BlockPos pos, FluidState fluidState) {
        return fluidState.is(FluidTags.WATER)
                && !fluidState.isSource()
                && horizontalFlowStrengthSqr(serverLevel, pos, fluidState) >= MIN_FLOW_STRENGTH_SQR;
    }

    private static double horizontalFlowStrengthSqr(ServerLevel serverLevel, BlockPos pos, FluidState fluidState) {
        Vec3 flow = fluidState.getFlow(serverLevel, pos);
        return flow.x * flow.x + flow.z * flow.z;
    }

    private void showPlacementItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (!this.showingPlacementItem) {
            this.previousMainHand = this.playerNpc.getMainHandItem().copy();
            this.showingPlacementItem = true;
        }
        ItemStack held = stack.copy();
        held.setCount(1);
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, held);
    }

    private void restorePreviousMainHand() {
        if (!this.showingPlacementItem) {
            return;
        }
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.showingPlacementItem = false;
    }

    private void returnStack(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private void updateDetail() {
        if (this.waterPos == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        if (this.placingStandBlock) {
            String place = this.standPlacePos == null
                    ? "finding footing"
                    : "placing footing @ " + this.standPlacePos.getX() + " " + this.standPlacePos.getY() + " " + this.standPlacePos.getZ();
            this.playerNpc.setCurrentAiDetail("trapped water @ "
                    + this.waterPos.getX() + " "
                    + this.waterPos.getY() + " "
                    + this.waterPos.getZ() + "\n" + place);
            return;
        }

        String plug = this.plugPos == null
                ? "jumping"
                : "plugging @ " + this.plugPos.getX() + " " + this.plugPos.getY() + " " + this.plugPos.getZ();
        this.playerNpc.setCurrentAiDetail("flow @ "
                + this.waterPos.getX() + " "
                + this.waterPos.getY() + " "
                + this.waterPos.getZ() + "\n" + plug);
    }

    private record WaterCurrentTarget(BlockPos waterPos, BlockPos plugPos) {}

    private record WaterStandTarget(BlockPos waterPos, BlockPos placePos) {}
}
