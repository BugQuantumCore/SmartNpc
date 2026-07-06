package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.player_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class BreakTargetObstructionGoal extends Goal {
    private static final double MAX_TARGET_DISTANCE_SQR = 28.0D * 28.0D;
    private static final double BREAK_DISTANCE_SQR = 3.25D * 3.25D;
    private static final int REPATH_INTERVAL_TICKS = 12;
    private static final int MAX_GOAL_TICKS = 20 * 10;
    private static final int MAX_MINE_TICKS = 20 * 8;

    private final PlayerNpcEntity playerNpc;
    private LivingEntity target;
    private BlockPos obstructionPos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int mineTicks;
    private int repathTicks;
    private int goalTicks;
    private boolean usingTemporaryTool;
    private boolean finished;

    public BreakTargetObstructionGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()) {
            return false;
        }

        LivingEntity currentTarget = this.playerNpc.getTarget();
        if (!this.isValidTarget(currentTarget)
                || this.playerNpc.distanceToSqr(currentTarget) > MAX_TARGET_DISTANCE_SQR
                || this.playerNpc.hasLineOfSight(currentTarget) && this.canReachTarget(currentTarget)) {
            return false;
        }

        BlockPos obstruction = this.findTargetObstruction(serverLevel, currentTarget);
        if (obstruction == null) {
            return false;
        }

        this.target = currentTarget;
        this.obstructionPos = obstruction;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.goalTicks < MAX_GOAL_TICKS
                && this.isValidTarget(this.target)
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing();
    }

    @Override
    public void start() {
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.goalTicks = 0;
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        this.finished = false;
        this.playerNpc.markCombatProgress();
        this.playerNpc.setCurrentAiState("ai.player_npc.breaking_target_obstruction");
        this.updateTaskDetail();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || !this.isValidTarget(this.target)) {
            this.finished = true;
            return;
        }

        this.goalTicks++;
        if (this.obstructionPos == null
                || !this.isCombatObstruction(serverLevel, this.obstructionPos, serverLevel.getBlockState(this.obstructionPos))) {
            this.playerNpc.clearBlockBreakProgress(this.obstructionPos);
            this.obstructionPos = this.findTargetObstruction(serverLevel, this.target);
            this.mineTicks = 0;
            if (this.obstructionPos == null || this.playerNpc.hasLineOfSight(this.target) && this.canReachTarget(this.target)) {
                this.finished = true;
                return;
            }
        }

        BlockState state = serverLevel.getBlockState(this.obstructionPos);
        this.playerNpc.getLookControl().setLookAt(
                this.obstructionPos.getX() + 0.5D,
                this.obstructionPos.getY() + 0.5D,
                this.obstructionPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (this.playerNpc.distanceToSqr(
                this.obstructionPos.getX() + 0.5D,
                this.obstructionPos.getY() + 0.5D,
                this.obstructionPos.getZ() + 0.5D
        ) > BREAK_DISTANCE_SQR) {
            this.playerNpc.clearBlockBreakProgress(this.obstructionPos);
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                this.moveNearObstruction(serverLevel);
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            this.updateTaskDetail();
            return;
        }

        if (!this.equipToolFor(state)) {
            this.finished = true;
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.markCombatProgress();
        if (this.mineTicks % 8 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, this.obstructionPos, state, this.playerNpc);
        }

        this.mineTicks++;
        int requiredTicks = this.getRequiredMineTicks(serverLevel, this.obstructionPos, state);
        this.playerNpc.showBlockBreakProgress(this.obstructionPos, this.mineTicks, requiredTicks);
        this.updateTaskDetail();
        if (this.mineTicks < requiredTicks) {
            return;
        }

        BlockPos brokenPos = this.obstructionPos;
        if (serverLevel.destroyBlock(brokenPos, true, this.playerNpc)) {
            this.playerNpc.hurtMainHandItem(1);
            this.playerNpc.markCombatProgress();
        }
        this.playerNpc.clearBlockBreakProgress(brokenPos);
        this.obstructionPos = this.findTargetObstruction(serverLevel, this.target);
        this.mineTicks = 0;
        this.repathTicks = 0;
        if (this.obstructionPos == null) {
            this.finished = true;
        }
    }

    @Override
    public void stop() {
        this.playerNpc.clearBlockBreakProgress(this.obstructionPos);
        this.restorePreviousMainHand();
        this.target = null;
        this.obstructionPos = null;
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.goalTicks = 0;
        this.finished = false;
        this.playerNpc.setCurrentAiDetail("");
        this.playerNpc.setCurrentAiState(this.playerNpc.getTarget() == null ? PlayerNpcEntity.AI_IDLE : "ai.player_npc.engaging");
    }

    private boolean isValidTarget(LivingEntity candidate) {
        return candidate != null
                && candidate.isAlive()
                && !candidate.isRemoved()
                && !this.playerNpc.isAlliedTo(candidate);
    }

    private boolean canReachTarget(LivingEntity target) {
        Path path = this.playerNpc.getNavigation().createPath(target.blockPosition(), 0);
        return path != null && path.canReach();
    }

    private BlockPos findTargetObstruction(ServerLevel serverLevel, LivingEntity target) {
        BlockPos rayHit = this.findRaycastObstruction(serverLevel, target);
        if (rayHit != null) {
            return rayHit;
        }

        return this.findLocalForwardObstruction(serverLevel, target);
    }

    private BlockPos findRaycastObstruction(ServerLevel serverLevel, LivingEntity target) {
        Vec3 eye = new Vec3(this.playerNpc.getX(), this.playerNpc.getEyeY(), this.playerNpc.getZ());
        Vec3 targetEye = new Vec3(target.getX(), target.getEyeY(), target.getZ());
        BlockHitResult hit = serverLevel.clip(new ClipContext(
                eye,
                targetEye,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                this.playerNpc
        ));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }

        BlockPos hitPos = hit.getBlockPos().immutable();
        BlockState state = serverLevel.getBlockState(hitPos);
        return this.isCombatObstruction(serverLevel, hitPos, state) ? hitPos : null;
    }

    private BlockPos findLocalForwardObstruction(ServerLevel serverLevel, LivingEntity target) {
        BlockPos feet = this.playerNpc.blockPosition();
        Direction towardTarget = this.directionToward(target);
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(feet.above());
        candidates.add(feet.above(2));
        BlockPos forward = feet.relative(towardTarget);
        candidates.add(forward);
        candidates.add(forward.above());
        candidates.add(forward.above(2));
        for (Direction side : new Direction[]{towardTarget.getClockWise(), towardTarget.getCounterClockWise()}) {
            BlockPos sidePos = feet.relative(side);
            candidates.add(sidePos);
            candidates.add(sidePos.above());
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(target.blockPosition()))
                .thenComparingDouble(this::distanceToBlockCenterSqr));
        for (BlockPos pos : candidates) {
            BlockPos immutable = pos.immutable();
            BlockState state = serverLevel.getBlockState(immutable);
            if (this.isCombatObstruction(serverLevel, immutable, state)) {
                return immutable;
            }
        }
        return null;
    }

    private Direction directionToward(LivingEntity target) {
        double dx = target.getX() - this.playerNpc.getX();
        double dz = target.getZ() - this.playerNpc.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx >= 0.0D ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0.0D ? Direction.SOUTH : Direction.NORTH;
    }

    private boolean isCombatObstruction(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return !state.isAir()
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && serverLevel.getBlockEntity(pos) == null
                && !this.isProtectedHomeBlock(pos)
                && this.hasRequiredToolFor(state);
    }

    private boolean hasRequiredToolFor(BlockState state) {
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.MINEABLE_WITH_AXE) || state.is(Blocks.CRAFTING_TABLE)) {
            return this.hasTool(AxeItem.class);
        }
        if (this.isPickaxeBlock(state)) {
            return this.hasTool(PickaxeItem.class);
        }
        return true;
    }

    private boolean equipToolFor(BlockState state) {
        if (state.is(BlockTags.LOGS) || state.is(BlockTags.MINEABLE_WITH_AXE) || state.is(Blocks.CRAFTING_TABLE)) {
            return this.equipTool(AxeItem.class);
        }
        if (this.isPickaxeBlock(state)) {
            return this.equipTool(PickaxeItem.class);
        }
        if (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            this.equipTool(ShovelItem.class);
            return true;
        }
        this.equipEmptyHand();
        return true;
    }

    private boolean isPickaxeBlock(BlockState state) {
        return state.requiresCorrectToolForDrops()
                || state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                || state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(Blocks.FURNACE);
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

    private void equipEmptyHand() {
        if (!this.playerNpc.getMainHandItem().isEmpty()) {
            this.setTemporaryMainHand(ItemStack.EMPTY);
        }
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
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
    }

    private void moveNearObstruction(ServerLevel serverLevel) {
        BlockPos stand = this.findStandNear(serverLevel, this.obstructionPos);
        if (stand != null) {
            this.playerNpc.getNavigation().moveTo(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D, 1.0D);
            return;
        }

        this.playerNpc.getNavigation().moveTo(
                this.obstructionPos.getX() + 0.5D,
                this.obstructionPos.getY(),
                this.obstructionPos.getZ() + 0.5D,
                1.0D
        );
    }

    private BlockPos findStandNear(ServerLevel serverLevel, BlockPos blockPos) {
        if (blockPos == null) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(blockPos.relative(direction));
            candidates.add(blockPos.relative(direction).below());
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!this.canStandAt(serverLevel, immutable)) {
                continue;
            }
            if (this.playerNpc.distanceToSqr(immutable.getX() + 0.5D, immutable.getY(), immutable.getZ() + 0.5D) <= 1.5D * 1.5D) {
                return immutable;
            }
            Path path = this.playerNpc.getNavigation().createPath(immutable, 0);
            if (path != null && path.canReach()) {
                return immutable;
            }
        }
        return null;
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

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(this.playerNpc);
        return homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), pos);
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(serverLevel, pos);
        if (hardness < 0.0F) {
            return MAX_MINE_TICKS;
        }

        ItemStack heldStack = this.playerNpc.getMainHandItem();
        float toolSpeed = heldStack.isEmpty() ? 1.0F : heldStack.getDestroySpeed(state);
        if (toolSpeed <= 0.0F) {
            toolSpeed = 1.0F;
        }

        boolean correctTool = !state.requiresCorrectToolForDrops() || heldStack.isCorrectToolForDrops(state);
        float progressPerTick = toolSpeed / hardness / (correctTool ? 30.0F : 100.0F);
        if (progressPerTick <= 0.0F) {
            return MAX_MINE_TICKS;
        }

        return Math.min(MAX_MINE_TICKS, Math.max(1, (int) Math.ceil(1.0F / progressPerTick)));
    }

    private void updateTaskDetail() {
        if (this.obstructionPos == null || !(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        BlockState state = serverLevel.getBlockState(this.obstructionPos);
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "%s @ %d %d %d",
                ForgeRegistries.BLOCKS.getKey(state.getBlock()),
                this.obstructionPos.getX(),
                this.obstructionPos.getY(),
                this.obstructionPos.getZ()
        ));
    }

    private double distanceToBlockCenterSqr(BlockPos pos) {
        return this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }
}
