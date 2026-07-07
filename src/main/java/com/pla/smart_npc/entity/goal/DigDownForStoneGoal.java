package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcBlockBreakUtil;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class DigDownForStoneGoal extends Goal {
    private static final int MIN_HOME_DISTANCE = 18;
    private static final int DIG_SITE_MIN_RADIUS = 10;
    private static final int DIG_SITE_MAX_RADIUS = 24;
    private static final int LOCAL_RESOURCE_RADIUS = 96;
    private static final int MAX_GOAL_TICKS = 20 * 120;
    private static final int MAX_STAIR_STEPS = 24;
    private static final int MAX_MINE_TICKS = 20 * 8;
    private static final double BREAK_DISTANCE_SQR = 4.0D * 4.0D;
    private static final double LOCAL_STEP_DISTANCE_SQR = 3.0D * 3.0D;
    private static final int REPATH_INTERVAL_TICKS = 15;
    private static final int COOLDOWN_TICKS = 20 * 18;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private BlockPos digOrigin;
    private BlockPos targetPos;
    private Direction digDirection;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int goalTicks;
    private int mineTicks;
    private int repathTicks;
    private int stairSteps;
    private int stoneBlocksMined;
    private int stoneBlocksNeeded;
    private boolean usingTemporaryTool;
    private boolean minedStone;
    private boolean finished;

    public DigDownForStoneGoal(PlayerNpcEntity playerNpc, double speed) {
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
                || !this.hasPickaxe()
                || this.countStone() >= this.playerNpc.getCobblestoneSupplyTarget()
                || this.countRawLogs() < this.playerNpc.getRawLogReserveTarget()) {
            return false;
        }

        this.digOrigin = this.findDigOrigin(serverLevel);
        if (this.digOrigin == null) {
            return false;
        }
        this.digDirection = this.chooseDigDirection();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.goalTicks < MAX_GOAL_TICKS
                && this.stairSteps < MAX_STAIR_STEPS
                && this.countStone() < this.playerNpc.getCobblestoneSupplyTarget()
                && this.stoneBlocksMined < this.stoneBlocksNeeded
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && this.playerNpc.getTarget() == null;
    }

    @Override
    public void start() {
        this.goalTicks = 0;
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.stairSteps = 0;
        this.stoneBlocksMined = 0;
        this.stoneBlocksNeeded = Math.max(1, this.playerNpc.getCobblestoneSupplyTarget() - this.countStone());
        this.targetPos = null;
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        this.minedStone = false;
        this.finished = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.digging_down_for_stone");
        this.playerNpc.setCurrentAiDetail("walking to dig site");
        this.moveTo(this.digOrigin);
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.digOrigin == null || this.digDirection == null) {
            this.finished = true;
            return;
        }

        this.goalTicks++;
        if (!this.hasReached(this.digOrigin)) {
            this.playerNpc.setCurrentAiDetail("walking to dig site");
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                if (!this.moveTo(this.digOrigin)) {
                    this.finished = true;
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            return;
        }

        if (this.targetPos == null) {
            this.targetPos = this.findNextDigTarget(serverLevel);
            this.mineTicks = 0;
            if (this.targetPos == null) {
                if (!this.hasReached(this.digOrigin)) {
                    return;
                }
                this.finished = true;
                return;
            }
        }

        this.tickMineTarget(serverLevel);
    }

    @Override
    public void stop() {
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.restorePreviousMainHand();
        if (!this.playerNpc.level().isClientSide) {
            int cooldown = this.minedStone ? COOLDOWN_TICKS : COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 12);
            this.playerNpc.setGatherCooldown(cooldown);
        }
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.digOrigin = null;
        this.targetPos = null;
        this.digDirection = null;
        this.goalTicks = 0;
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.stairSteps = 0;
        this.minedStone = false;
        this.finished = false;
    }

    private BlockPos findDigOrigin(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = center.getX() - DIG_SITE_MAX_RADIUS; x <= center.getX() + DIG_SITE_MAX_RADIUS; x++) {
            for (int z = center.getZ() - DIG_SITE_MAX_RADIUS; z <= center.getZ() + DIG_SITE_MAX_RADIUS; z++) {
                int dx = x - center.getX();
                int dz = z - center.getZ();
                int distSqr = dx * dx + dz * dz;
                if (distSqr < DIG_SITE_MIN_RADIUS * DIG_SITE_MIN_RADIUS || distSqr > DIG_SITE_MAX_RADIUS * DIG_SITE_MAX_RADIUS) {
                    continue;
                }
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (this.canStandAt(serverLevel, candidate)
                        && this.isAwayFromHome(candidate)
                        && this.isInsideResourceRadius(candidate)) {
                    candidates.add(candidate.immutable());
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(center::distSqr));
        int checks = Math.min(candidates.size(), 32);
        for (int i = 0; i < checks; i++) {
            BlockPos candidate = candidates.get(i);
            Path path = this.playerNpc.getNavigation().createPath(candidate, 0);
            if (path != null && path.canReach()) {
                return candidate;
            }
        }
        return null;
    }

    private Direction chooseDigDirection() {
        Direction[] directions = Direction.Plane.HORIZONTAL.stream().toArray(Direction[]::new);
        return directions[this.playerNpc.getRandom().nextInt(directions.length)];
    }

    private BlockPos findNextDigTarget(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos forwardHead = feet.relative(this.digDirection);
        BlockPos forwardFeet = forwardHead.below();

        if (this.isDiggable(serverLevel, forwardHead, serverLevel.getBlockState(forwardHead))) {
            return forwardHead.immutable();
        }
        if (this.isDiggable(serverLevel, forwardFeet, serverLevel.getBlockState(forwardFeet))) {
            return forwardFeet.immutable();
        }
        if (this.canStandAt(serverLevel, forwardFeet)) {
            this.digOrigin = forwardFeet.immutable();
            this.stairSteps++;
            this.moveTo(this.digOrigin);
            return null;
        }
        return null;
    }

    private void tickMineTarget(ServerLevel serverLevel) {
        BlockState state = serverLevel.getBlockState(this.targetPos);
        if (!this.isDiggable(serverLevel, this.targetPos, state)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.targetPos = null;
            return;
        }
        if (!this.equipToolFor(state)) {
            this.finished = true;
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        if (this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D) > BREAK_DISTANCE_SQR) {
            this.targetPos = null;
            return;
        }

        if (this.mineTicks % 8 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, this.targetPos, state, this.playerNpc);
        }
        this.mineTicks++;
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, this.targetPos, state);
        this.playerNpc.showBlockBreakProgress(this.targetPos, this.mineTicks, requiredMineTicks);
        this.updateTaskDetail(state, requiredMineTicks);
        if (this.mineTicks < requiredMineTicks) {
            return;
        }

        BlockPos minedPos = this.targetPos;
        if (PlayerNpcBlockBreakUtil.destroyBlock(serverLevel, minedPos, state, this.playerNpc)) {
            if (this.isStoneMaterial(state)) {
                this.minedStone = true;
            }
            this.playerNpc.hurtMainHandItem(1);
        }
        this.playerNpc.clearBlockBreakProgress(minedPos);
        this.targetPos = null;
    }

    private boolean isDiggable(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !state.isAir()
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && serverLevel.getBlockEntity(pos) == null
                && !this.isProtectedHomeBlock(pos);
    }

    private boolean equipToolFor(BlockState state) {
        if (this.isStoneMaterial(state) || state.is(BlockTags.MINEABLE_WITH_PICKAXE) || state.requiresCorrectToolForDrops()) {
            return this.equipTool(PickaxeItem.class);
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SAND)) {
            if (this.equipTool(ShovelItem.class)) {
                return true;
            }
            this.equipEmptyHandForMining();
            return true;
        }
        return this.equipTool(PickaxeItem.class);
    }

    private void equipEmptyHandForMining() {
        if (this.playerNpc.getMainHandItem().isEmpty()) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryTool) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryTool = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
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
        if (!this.usingTemporaryTool) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryTool = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, tool);
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

    private void updateTaskDetail(BlockState state, int requiredMineTicks) {
        this.playerNpc.setCurrentAiDetail(String.format(
                Locale.ROOT,
                "%s @ %d %d %d %d/%dt",
                ForgeRegistries.BLOCKS.getKey(state.getBlock()),
                this.targetPos.getX(),
                this.targetPos.getY(),
                this.targetPos.getZ(),
                Math.min(this.mineTicks, requiredMineTicks),
                requiredMineTicks
        ));
    }

    private boolean moveTo(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        Path path = this.playerNpc.getNavigation().createPath(pos, 0);
        if (path != null && path.canReach()) {
            return this.playerNpc.getNavigation().moveTo(path, this.speed);
        }
        if (this.playerNpc.blockPosition().distSqr(pos) <= LOCAL_STEP_DISTANCE_SQR) {
            this.playerNpc.getMoveControl().setWantedPosition(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, this.speed);
            return true;
        }
        return false;
    }

    private boolean hasReached(BlockPos pos) {
        return pos != null && this.playerNpc.blockPosition().equals(pos);
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private boolean isAwayFromHome(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return true;
        }
        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        BlockPos homeCenter = PlayerNpcHomeUtil.center(homeArea);
        return homeCenter.distSqr(pos) >= MIN_HOME_DISTANCE * MIN_HOME_DISTANCE;
    }

    private boolean isInsideResourceRadius(BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideActivityRadius(this.playerNpc, pos, LOCAL_RESOURCE_RADIUS, true);
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        return home.isPresent() && PlayerNpcHomeUtil.isInside(home.get(), pos);
    }

    private boolean hasPickaxe() {
        return this.playerNpc.getMainHandItem().getItem() instanceof PickaxeItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof PickaxeItem);
    }

    private int countStone() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    private int countRawLogs() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(ItemTags.LOGS));
    }

    private boolean isStoneMaterial(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(BlockTags.MINEABLE_WITH_PICKAXE);
    }
}
