package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcCraftingUtil;
import com.pla.player_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

import java.util.EnumSet;

public class ManageHomeBaseGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 8;
    private static final double RECOVER_TABLE_BREAK_DISTANCE_SQR = 3.0D * 3.0D;
    private static final double RECOVER_TABLE_MOVE_SPEED = 1.0D;
    private static final int MAX_RECOVER_TABLE_TICKS = 20 * 8;

    private final PlayerNpcEntity playerNpc;
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private BlockPos recoveryTablePos;
    private int recoveryBreakTicks;

    public ManageHomeBaseGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
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
                || this.playerNpc.getManageHomeCooldown() > 0) {
            return false;
        }

        this.homeArea = PlayerNpcHomeUtil.getOrCreateHome(this.playerNpc, serverLevel);
        return this.canRecoverTemporaryCraftingTable(serverLevel)
                || this.needsCraftingTable(serverLevel)
                || this.needsBed(serverLevel)
                || this.needsChest(serverLevel)
                || this.shouldDepositToChest(serverLevel);
    }

    @Override
    public boolean canContinueToUse() {
        return this.recoveryTablePos != null
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && serverLevel.getBlockState(this.recoveryTablePos).is(Blocks.CRAFTING_TABLE);
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.homeArea == null) {
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState("ai.player_npc.managing_home");
        this.recoveryTablePos = null;
        this.recoveryBreakTicks = 0;

        if (this.canRecoverTemporaryCraftingTable(serverLevel)) {
            this.recoveryTablePos = this.getTemporaryCraftingTablePos();
            this.updateRecoveryDetail(serverLevel);
            return;
        }

        boolean acted = this.placeCraftingTable(serverLevel)
                || this.placeBed(serverLevel)
                || this.placeChest(serverLevel)
                || this.depositToChest(serverLevel);
        if (acted) {
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        }

        this.finishHomeAction(serverLevel);
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.recoveryTablePos == null) {
            return;
        }

        BlockState state = serverLevel.getBlockState(this.recoveryTablePos);
        if (!state.is(Blocks.CRAFTING_TABLE)) {
            this.clearTemporaryCraftingTable();
            this.finishHomeAction(serverLevel);
            return;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.recoveryTablePos.getX() + 0.5D,
                this.recoveryTablePos.getY() + 0.5D,
                this.recoveryTablePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (this.playerNpc.distanceToSqr(
                this.recoveryTablePos.getX() + 0.5D,
                this.recoveryTablePos.getY() + 0.5D,
                this.recoveryTablePos.getZ() + 0.5D
        ) > RECOVER_TABLE_BREAK_DISTANCE_SQR) {
            this.playerNpc.getNavigation().moveTo(
                    this.recoveryTablePos.getX() + 0.5D,
                    this.recoveryTablePos.getY(),
                    this.recoveryTablePos.getZ() + 0.5D,
                    RECOVER_TABLE_MOVE_SPEED
            );
            this.updateRecoveryDetail(serverLevel);
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.recoveryBreakTicks % 8 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            serverLevel.levelEvent(2001, this.recoveryTablePos, Block.getId(state));
        }

        this.recoveryBreakTicks++;
        this.updateRecoveryDetail(serverLevel);
        if (this.recoveryBreakTicks < this.getRequiredBreakTicks(serverLevel, this.recoveryTablePos, state)) {
            return;
        }

        BlockPos recoveredPos = this.recoveryTablePos;
        if (!serverLevel.destroyBlock(recoveredPos, false, this.playerNpc)) {
            this.clearTemporaryCraftingTable();
            this.finishHomeAction(serverLevel);
            return;
        }
        this.playerNpc.hurtMainHandItem(1);
        this.returnStack(new ItemStack(Items.CRAFTING_TABLE));
        this.clearTemporaryCraftingTable();
        this.lookAndSound(serverLevel, recoveredPos, SoundEvents.WOOD_BREAK);
        this.finishHomeAction(serverLevel);
    }

    @Override
    public void stop() {
        this.homeArea = null;
        this.recoveryTablePos = null;
        this.recoveryBreakTicks = 0;
        this.playerNpc.setCurrentAiDetail("");
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void finishHomeAction(ServerLevel serverLevel) {
        this.playerNpc.setManageHomeCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 8));
        this.recoveryTablePos = null;
    }

    private boolean canRecoverTemporaryCraftingTable(ServerLevel serverLevel) {
        BlockPos pos = this.getTemporaryCraftingTablePos();
        if (pos == null) {
            return false;
        }

        return !PlayerNpcHomeUtil.isInside(this.homeArea, pos)
                && this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 6.0D * 6.0D
                && serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
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

    private void clearTemporaryCraftingTable() {
        this.playerNpc.getPersistentData().remove(CraftBasicGearGoal.TEMP_TABLE_X);
        this.playerNpc.getPersistentData().remove(CraftBasicGearGoal.TEMP_TABLE_Y);
        this.playerNpc.getPersistentData().remove(CraftBasicGearGoal.TEMP_TABLE_Z);
    }

    private boolean needsCraftingTable(ServerLevel serverLevel) {
        return this.findBlock(serverLevel, Blocks.CRAFTING_TABLE) == null
                && PlayerNpcCraftingUtil.canCraftCraftingTable(this.playerNpc.getInventory());
    }

    private boolean needsBed(ServerLevel serverLevel) {
        return this.findBed(serverLevel) == null
                && (this.hasBedItem() || PlayerNpcCraftingUtil.canCraftBed(this.playerNpc.getInventory()));
    }

    private boolean needsChest(ServerLevel serverLevel) {
        return this.findBlock(serverLevel, Blocks.CHEST) == null
                && (InventoryUtils.hasItem(this.playerNpc, Items.CHEST)
                || PlayerNpcCraftingUtil.canCraftChest(this.playerNpc.getInventory()));
    }

    private boolean placeCraftingTable(ServerLevel serverLevel) {
        if (!this.needsCraftingTable(serverLevel)) {
            return false;
        }

        BlockPos pos = this.findUtilityPlacement(serverLevel, 1, 1);
        if (pos == null || !PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 4)) {
            return false;
        }

        serverLevel.setBlockAndUpdate(pos, Blocks.CRAFTING_TABLE.defaultBlockState());
        this.lookAndSound(serverLevel, pos, SoundEvents.WOOD_PLACE);
        return true;
    }

    private boolean placeChest(ServerLevel serverLevel) {
        if (!this.needsChest(serverLevel)) {
            return false;
        }

        BlockPos pos = this.findUtilityPlacement(serverLevel, this.homeArea.width() - 2, 1);
        if (pos == null) {
            return false;
        }

        ItemStack chest = this.playerNpc.consumeInventoryItem(Items.CHEST, 1).orElse(ItemStack.EMPTY);
        if (chest.isEmpty() && !PlayerNpcCraftingUtil.tryCraftChest(this.playerNpc.getInventory())) {
            return false;
        }
        if (chest.isEmpty()) {
            chest = this.playerNpc.consumeInventoryItem(Items.CHEST, 1).orElse(ItemStack.EMPTY);
        }
        if (chest.isEmpty()) {
            return false;
        }

        serverLevel.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        this.lookAndSound(serverLevel, pos, SoundEvents.WOOD_PLACE);
        return true;
    }

    private boolean placeBed(ServerLevel serverLevel) {
        if (!this.needsBed(serverLevel)) {
            return false;
        }

        if (!this.hasBedItem() && !PlayerNpcCraftingUtil.tryCraftBed(this.playerNpc.getInventory())) {
            return false;
        }

        ItemStack bedStack = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BedItem, 1)
                .orElse(ItemStack.EMPTY);
        if (bedStack.isEmpty() || !(bedStack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof BedBlock bedBlock)) {
            return false;
        }

        BlockPos foot = this.findUtilityPlacement(serverLevel, 1, this.homeArea.depth() - 2);
        if (foot == null) {
            this.returnStack(bedStack);
            return false;
        }

        Direction facing = this.playerNpc.getDirection();
        BlockPos head = foot.relative(facing);
        if (!this.canPlaceUtilityAt(serverLevel, head)) {
            this.returnStack(bedStack);
            return false;
        }

        BlockState footState = bedBlock.defaultBlockState()
                .setValue(BedBlock.FACING, facing)
                .setValue(BedBlock.PART, BedPart.FOOT);
        BlockState headState = bedBlock.defaultBlockState()
                .setValue(BedBlock.FACING, facing)
                .setValue(BedBlock.PART, BedPart.HEAD);
        serverLevel.setBlockAndUpdate(foot, footState);
        serverLevel.setBlockAndUpdate(head, headState);
        this.lookAndSound(serverLevel, foot, SoundEvents.WOOD_PLACE);
        return true;
    }

    private boolean shouldDepositToChest(ServerLevel serverLevel) {
        return this.usedInventorySlots() > this.playerNpc.getInventory().getContainerSize() / 2
                && this.findBlock(serverLevel, Blocks.CHEST) != null;
    }

    private boolean depositToChest(ServerLevel serverLevel) {
        BlockPos chestPos = this.findBlock(serverLevel, Blocks.CHEST);
        if (chestPos == null || !(serverLevel.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) {
            return false;
        }

        boolean movedAny = false;
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && this.usedInventorySlots() > inventory.getContainerSize() / 2; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || this.shouldKeepStack(stack)) {
                continue;
            }

            ItemStack toMove = stack.copy();
            toMove.setCount(Math.max(1, stack.getCount() / 2));
            ItemStack remaining = this.addToContainer(chest, toMove);
            int moved = toMove.getCount() - remaining.getCount();
            if (moved > 0) {
                stack.shrink(moved);
                if (stack.isEmpty()) {
                    inventory.setItem(i, ItemStack.EMPTY);
                }
                movedAny = true;
            }
        }
        inventory.setChanged();
        if (movedAny) {
            this.lookAndSound(serverLevel, chestPos, SoundEvents.ITEM_PICKUP);
        }
        return movedAny;
    }

    private ItemStack addToContainer(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty()
                    || !ItemStack.isSameItemSameTags(existing, remaining)
                    || existing.getCount() >= existing.getMaxStackSize()) {
                continue;
            }

            int transferable = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
            existing.grow(transferable);
            remaining.shrink(transferable);
        }

        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            if (!container.getItem(i).isEmpty()) {
                continue;
            }

            ItemStack inserted = remaining.copy();
            inserted.setCount(Math.min(remaining.getCount(), remaining.getMaxStackSize()));
            container.setItem(i, inserted);
            remaining.shrink(inserted.getCount());
        }
        container.setChanged();
        return remaining;
    }

    private boolean shouldKeepStack(ItemStack stack) {
        return stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof DiggerItem
                || stack.getItem() instanceof ArmorItem
                || stack.getItem() instanceof BowItem
                || stack.getItem() instanceof ShieldItem
                || stack.isEdible()
                || stack.is(Items.ARROW)
                || stack.is(Items.ENDER_PEARL)
                || stack.is(Items.WATER_BUCKET)
                || stack.is(Items.LAVA_BUCKET)
                || stack.is(Items.BUCKET)
                || stack.is(Items.CRAFTING_TABLE)
                || stack.is(Items.CHEST)
                || stack.getItem() instanceof BedItem;
    }

    private BlockPos findUtilityPlacement(ServerLevel serverLevel, int preferredX, int preferredZ) {
        BlockPos preferred = PlayerNpcHomeUtil.interiorPos(this.homeArea, preferredX, preferredZ);
        if (this.canPlaceUtilityAt(serverLevel, preferred)) {
            return preferred;
        }

        for (int x = 1; x < this.homeArea.width() - 1; x++) {
            for (int z = 1; z < this.homeArea.depth() - 1; z++) {
                BlockPos pos = PlayerNpcHomeUtil.interiorPos(this.homeArea, x, z);
                if (this.canPlaceUtilityAt(serverLevel, pos)) {
                    return pos;
                }
            }
        }
        return null;
    }

    private boolean canPlaceUtilityAt(ServerLevel serverLevel, BlockPos pos) {
        return PlayerNpcHomeUtil.isInside(this.homeArea, pos)
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private BlockPos findBlock(ServerLevel serverLevel, Block block) {
        for (BlockPos pos : BlockPos.betweenClosed(
                this.homeArea.origin(),
                this.homeArea.origin().offset(this.homeArea.width() - 1, 3, this.homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).is(block)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findBed(ServerLevel serverLevel) {
        for (BlockPos pos : BlockPos.betweenClosed(
                this.homeArea.origin(),
                this.homeArea.origin().offset(this.homeArea.width() - 1, 3, this.homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).getBlock() instanceof BedBlock) {
                return pos.immutable();
            }
        }
        return null;
    }

    private boolean hasBedItem() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof BedItem);
    }

    private int usedInventorySlots() {
        int used = 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            if (!this.playerNpc.getInventory().getItem(i).isEmpty()) {
                used++;
            }
        }
        return used;
    }

    private void returnStack(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private int getRequiredBreakTicks(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(serverLevel, pos);
        if (hardness < 0.0F) {
            return MAX_RECOVER_TABLE_TICKS;
        }

        ItemStack heldStack = this.playerNpc.getMainHandItem();
        float toolSpeed = heldStack.isEmpty() ? 1.0F : heldStack.getDestroySpeed(state);
        if (toolSpeed <= 0.0F) {
            toolSpeed = 1.0F;
        }

        boolean correctTool = !state.requiresCorrectToolForDrops() || heldStack.isCorrectToolForDrops(state);
        float progressPerTick = toolSpeed / hardness / (correctTool ? 30.0F : 100.0F);
        if (progressPerTick <= 0.0F) {
            return MAX_RECOVER_TABLE_TICKS;
        }

        return Math.min(MAX_RECOVER_TABLE_TICKS, Math.max(1, (int) Math.ceil(1.0F / progressPerTick)));
    }

    private void updateRecoveryDetail(ServerLevel serverLevel) {
        if (this.recoveryTablePos == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        BlockState state = serverLevel.getBlockState(this.recoveryTablePos);
        int requiredTicks = this.getRequiredBreakTicks(serverLevel, this.recoveryTablePos, state);
        boolean inBreakRange = this.playerNpc.distanceToSqr(
                this.recoveryTablePos.getX() + 0.5D,
                this.recoveryTablePos.getY() + 0.5D,
                this.recoveryTablePos.getZ() + 0.5D
        ) <= RECOVER_TABLE_BREAK_DISTANCE_SQR;
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "minecraft:crafting_table @ %d %d %d %s",
                this.recoveryTablePos.getX(),
                this.recoveryTablePos.getY(),
                this.recoveryTablePos.getZ(),
                inBreakRange ? String.format(java.util.Locale.ROOT, "%d/%dt", Math.min(this.recoveryBreakTicks, requiredTicks), requiredTicks) : "walking"
        ));
    }

    private void lookAndSound(ServerLevel serverLevel, BlockPos pos, net.minecraft.sounds.SoundEvent soundEvent) {
        this.playerNpc.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 40.0F, 40.0F);
        serverLevel.playSound(null, pos, soundEvent, SoundSource.BLOCKS, 0.8F, 1.0F);
    }
}
