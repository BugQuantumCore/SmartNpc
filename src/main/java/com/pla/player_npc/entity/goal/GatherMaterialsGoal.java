package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public class GatherMaterialsGoal extends Goal {
    private static final int SEARCH_RADIUS = 8;
    private static final double BREAK_DISTANCE_SQR = 3.0D * 3.0D;
    private static final int COOLDOWN_TICKS = 120;
    private static final int STARTER_WOOD_TARGET = 12;
    private static final int STONE_GEAR_STONE_TARGET = 9;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_GATHER_TICKS = 20 * 10;
    private static final int MAX_FAILED_PATH_TICKS = 20 * 3;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private BlockPos targetPos;
    private BlockPos standPos;
    private MaterialTarget targetType = MaterialTarget.GENERAL;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int mineTicks;
    private int gatherTicks;
    private int repathTicks;
    private int failedPathTicks;
    private boolean usingTemporaryTool;

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

        this.targetType = this.chooseTargetType();
        GatherTarget target = this.findTargetBlock(serverLevel);
        if (target == null) {
            return false;
        }

        this.targetPos = target.targetPos();
        this.standPos = target.standPos();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.targetPos != null
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && !this.inventoryIsMostlyFull()
                && this.gatherTicks < MAX_GATHER_TICKS;
    }

    @Override
    public void start() {
        this.mineTicks = 0;
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.gathering_materials");
        if (this.targetPos != null && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.equipToolFor(serverLevel.getBlockState(this.targetPos));
            this.updateTaskDetail(serverLevel);
        }
        this.moveToTarget();
    }

    @Override
    public void stop() {
        this.restorePreviousMainHand();
        this.targetPos = null;
        this.standPos = null;
        this.mineTicks = 0;
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.targetType = MaterialTarget.GENERAL;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setGatherCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(120));
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.targetPos == null) {
            return;
        }
        this.gatherTicks++;

        if (!this.isWantedBlock(serverLevel.getBlockState(this.targetPos))) {
            this.targetPos = null;
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

        if (this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D) > BREAK_DISTANCE_SQR) {
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone()) {
                if (this.moveToTarget()) {
                    this.failedPathTicks = 0;
                } else {
                    this.failedPathTicks += REPATH_INTERVAL_TICKS;
                    if (this.failedPathTicks >= MAX_FAILED_PATH_TICKS) {
                        this.targetPos = null;
                    }
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.mineTicks % 8 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            serverLevel.levelEvent(2001, this.targetPos, net.minecraft.world.level.block.Block.getId(serverLevel.getBlockState(this.targetPos)));
        }

        this.mineTicks++;
        if (this.mineTicks < this.getRequiredMineTicks(serverLevel, serverLevel.getBlockState(this.targetPos))) {
            return;
        }

        BlockPos minedPos = this.targetPos;
        if (!serverLevel.destroyBlock(minedPos, true, this.playerNpc)) {
            this.targetPos = null;
            return;
        }
        this.playerNpc.hurtMainHandItem(1);
        if (this.targetType == MaterialTarget.LOG) {
            GatherTarget nextLog = this.findNextLogInSameTree(serverLevel, minedPos);
            if (nextLog != null) {
                this.targetPos = nextLog.targetPos();
                this.standPos = nextLog.standPos();
                this.mineTicks = 0;
                this.equipToolFor(serverLevel.getBlockState(this.targetPos));
                this.updateTaskDetail(serverLevel);
                this.moveToTarget();
                return;
            }
        }
        this.targetPos = null;
    }

    private boolean moveToTarget() {
        if (this.standPos == null) {
            return false;
        }

        return this.playerNpc.getNavigation().moveTo(this.standPos.getX() + 0.5D, this.standPos.getY(), this.standPos.getZ() + 0.5D, this.speed);
    }

    private GatherTarget findTargetBlock(ServerLevel serverLevel) {
        List<GatherTarget> candidates = new ArrayList<>();
        BlockPos center = this.playerNpc.blockPosition();

        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-SEARCH_RADIUS, -2, -SEARCH_RADIUS), center.offset(SEARCH_RADIUS, 3, SEARCH_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (this.isWantedBlockForTarget(serverLevel.getBlockState(immutable))) {
                BlockPos stand = this.findStandPos(serverLevel, immutable);
                if (stand != null) {
                    candidates.add(new GatherTarget(immutable, stand));
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparingDouble(target -> center.distSqr(target.standPos())));
        return candidates.get(this.playerNpc.getRandom().nextInt(Math.min(candidates.size(), 6)));
    }

    private GatherTarget findNextLogInSameTree(ServerLevel serverLevel, BlockPos minedPos) {
        List<GatherTarget> candidates = new ArrayList<>();

        for (BlockPos pos : BlockPos.betweenClosed(minedPos.offset(-2, -1, -2), minedPos.offset(2, 8, 2))) {
            BlockPos immutable = pos.immutable();
            if (!serverLevel.getBlockState(immutable).is(BlockTags.LOGS)) {
                continue;
            }

            BlockPos stand = this.findStandPos(serverLevel, immutable);
            if (stand != null) {
                candidates.add(new GatherTarget(immutable, stand));
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator
                .comparingDouble((GatherTarget target) -> minedPos.distSqr(target.targetPos()))
                .thenComparingDouble(target -> this.playerNpc.blockPosition().distSqr(target.standPos())));
        return candidates.get(0);
    }

    private BlockPos findStandPos(ServerLevel serverLevel, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
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

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
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

    private boolean isWantedBlockForTarget(BlockState state) {
        return switch (this.targetType) {
            case LOG -> state.is(BlockTags.LOGS);
            case STONE -> this.isStoneBlock(state);
            case COAL -> this.isCoalOre(state);
            case GENERAL -> this.isWantedBlock(state);
        };
    }

    private MaterialTarget chooseTargetType() {
        int woodCount = this.countWood();
        int stoneCount = this.countStone();

        if (this.needsStarterGear() && woodCount < STARTER_WOOD_TARGET) {
            return MaterialTarget.LOG;
        }

        if (!this.hasTool(PickaxeItem.class) && woodCount < 5) {
            return MaterialTarget.LOG;
        }

        if (this.hasTool(PickaxeItem.class) && this.needsStoneGear() && stoneCount < STONE_GEAR_STONE_TARGET) {
            return MaterialTarget.STONE;
        }

        if (this.hasTool(PickaxeItem.class) && this.hasRawFood() && !this.hasFuel()) {
            return MaterialTarget.COAL;
        }

        if (this.needsStoneGear() && woodCount < 6) {
            return MaterialTarget.LOG;
        }

        return MaterialTarget.GENERAL;
    }

    private boolean shouldCraftBeforeGathering(ServerLevel serverLevel) {
        if (this.playerNpc.getCraftGearCooldown() > 0) {
            return false;
        }

        boolean hasCraftingTable = this.hasNearbyCraftingTable(serverLevel);
        if (!hasCraftingTable && this.needsStarterGear()) {
            return this.countWood() >= 4;
        }

        return hasCraftingTable && (this.canCraftStarterGear() || this.canCraftStoneGear());
    }

    private boolean needsStarterGear() {
        return !this.hasTool(PickaxeItem.class)
                || !this.hasTool(AxeItem.class)
                || !this.hasTool(SwordItem.class);
    }

    private boolean needsStoneGear() {
        return !this.hasItem(Items.STONE_PICKAXE)
                || !this.hasItem(Items.STONE_AXE)
                || !this.hasItem(Items.STONE_SWORD)
                || !this.hasItem(Items.STONE_SHOVEL);
    }

    private boolean canCraftStarterGear() {
        int woodCount = this.countWood();
        return !this.hasTool(PickaxeItem.class) && woodCount >= 5
                || !this.hasTool(AxeItem.class) && woodCount >= 5
                || !this.hasTool(SwordItem.class) && woodCount >= 4
                || !this.hasTool(ShovelItem.class) && woodCount >= 3;
    }

    private boolean canCraftStoneGear() {
        if (!this.hasTool(PickaxeItem.class)) {
            return false;
        }

        int stoneCount = this.countStone();
        return !this.hasItem(Items.STONE_PICKAXE) && stoneCount >= 3 && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, 2)
                || !this.hasItem(Items.STONE_AXE) && stoneCount >= 3 && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, 2)
                || !this.hasItem(Items.STONE_SWORD) && stoneCount >= 2 && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, 1)
                || !this.hasItem(Items.STONE_SHOVEL) && stoneCount >= 1 && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, 2);
    }

    private boolean isStoneBlock(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE);
    }

    private boolean isCoalOre(BlockState state) {
        return state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE);
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockState state) {
        float hardness = state.getDestroySpeed(serverLevel, this.targetPos);
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

    private void equipToolFor(BlockState state) {
        if (state.is(BlockTags.LOGS)) {
            this.equipTool(AxeItem.class);
        } else if (this.isStoneBlock(state) || this.isCoalOre(state)) {
            this.equipTool(PickaxeItem.class);
        } else if (state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)) {
            this.equipTool(ShovelItem.class);
        }
    }

    private void equipTool(Class<?> toolClass) {
        if (toolClass.isInstance(this.playerNpc.getMainHandItem().getItem())) {
            return;
        }

        ItemStack tool = this.playerNpc.consumeInventoryItem(stack -> toolClass.isInstance(stack.getItem()), 1)
                .orElse(ItemStack.EMPTY);
        if (tool.isEmpty()) {
            return;
        }

        this.previousMainHand = this.playerNpc.getMainHandItem().copy();
        this.usingTemporaryTool = true;
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, tool);
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
        return InventoryUtils.hasItem(this.playerNpc, stack -> toolClass.isInstance(stack.getItem()));
    }

    private boolean hasItem(net.minecraft.world.level.ItemLike itemLike) {
        return this.playerNpc.getMainHandItem().is(itemLike.asItem())
                || InventoryUtils.hasItem(this.playerNpc, itemLike);
    }

    private int countWood() {
        return PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory());
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

    private boolean hasRawFood() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> stack.is(Items.BEEF)
                || stack.is(Items.PORKCHOP)
                || stack.is(Items.CHICKEN)
                || stack.is(Items.MUTTON)
                || stack.is(Items.COD)
                || stack.is(Items.SALMON)
                || stack.is(Items.POTATO));
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
                "%s @ %d %d %d %s",
                blockName,
                this.targetPos.getX(),
                this.targetPos.getY(),
                this.targetPos.getZ(),
                inBreakRange ? String.format(java.util.Locale.ROOT, "%d/%dt", Math.min(this.mineTicks, requiredMineTicks), requiredMineTicks) : "walking"
        ));
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
        STONE,
        COAL,
        GENERAL
    }

    private record GatherTarget(BlockPos targetPos, BlockPos standPos) {}
}
