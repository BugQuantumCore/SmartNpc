package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
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

    private final PlayerNpcEntity playerNpc;
    private BlockPos waterPos;
    private BlockPos plugPos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private boolean showingPlacementItem;
    private boolean finished;
    private int escapeTicks;
    private int cooldownTicks;

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
                || !this.isActiveTravelState()) {
            return false;
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
    public boolean canContinueToUse() {
        if (this.escapeTicks >= MAX_ESCAPE_TICKS
                || this.finished
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.getTarget() != null
                || !(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
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
        this.playerNpc.setCurrentAiState("ai.player_npc.escaping_water_current");
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.waterPos == null) {
            return;
        }

        this.escapeTicks++;
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
        this.escapeTicks = 0;
        this.finished = false;
        this.cooldownTicks = COOLDOWN_TICKS;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
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

    private boolean tryPlugWater(ServerLevel serverLevel, BlockPos pos) {
        ItemStack blockStack = InventoryUtils.consumeItem(this.playerNpc, this::isWaterPlugBlock, 1).orElse(ItemStack.EMPTY);
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

        String plug = this.plugPos == null
                ? "jumping"
                : "plugging @ " + this.plugPos.getX() + " " + this.plugPos.getY() + " " + this.plugPos.getZ();
        this.playerNpc.setCurrentAiDetail("flow @ "
                + this.waterPos.getX() + " "
                + this.waterPos.getY() + " "
                + this.waterPos.getZ() + "\n" + plug);
    }

    private record WaterCurrentTarget(BlockPos waterPos, BlockPos plugPos) {}
}
