package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.ChatUtil;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class ManageHomeBaseGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 8;
    private static final double HOME_ACTION_DISTANCE_SQR = 8.0D * 8.0D;
    private static final double RECOVER_TABLE_BREAK_DISTANCE_SQR = 3.0D * 3.0D;
    private static final double RECOVER_TABLE_MOVE_SPEED = 1.0D;
    private static final int MAX_RECOVER_TABLE_TICKS = 20 * 8;
    private static final int DEPOSIT_INTERVAL_TICKS = 6;
    private static final int BUILD_SITE_CRAFTING_TABLE_MARGIN = 5;
    private static final int BUILD_SITE_CRAFTING_TABLE_SCAN_BELOW = 2;
    private static final int BUILD_SITE_CRAFTING_TABLE_SCAN_ABOVE = 3;

    private final PlayerNpcEntity playerNpc;
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private BlockPos recoveryTablePos;
    private BlockPos depositChestPos;
    private BlockPos pendingCraftingTablePos;
    private BlockPos pendingCraftingTableStandPos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int recoveryBreakTicks;
    private int depositDelayTicks;
    private boolean usingTemporaryTool;
    private boolean returnTemporaryMainHandOnRestore;
    private boolean depositChestOpen;
    private boolean depositFinished;
    private boolean depositMovedAny;

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

        Optional<PlayerNpcHomeUtil.HomeArea> savedHome = PlayerNpcHomeUtil.getHome(this.playerNpc);
        this.homeArea = savedHome.orElse(null);
        if (this.canRecoverTemporaryCraftingTable(serverLevel)) {
            return true;
        }
        if (this.homeArea == null) {
            return false;
        }
        if (this.playerNpc.shouldPrioritizeLogGathering() && !this.inventoryMoreThanHalfFull()) {
            return false;
        }
        if (!this.isNearHome()) {
            return false;
        }

        return this.needsCraftingTable(serverLevel)
                || this.needsBed(serverLevel)
                || this.needsChest(serverLevel)
                || this.shouldDepositToChest(serverLevel);
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.getTarget() != null
                || !(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (this.recoveryTablePos != null) {
            return serverLevel.getBlockState(this.recoveryTablePos).is(Blocks.CRAFTING_TABLE);
        }
        return this.pendingCraftingTablePos != null
                || this.depositChestPos != null
                && !this.depositFinished
                && serverLevel.getBlockState(this.depositChestPos).is(Blocks.CHEST);
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState("ai.player_npc.managing_home");
        this.recoveryTablePos = null;
        this.depositChestPos = null;
        this.pendingCraftingTablePos = null;
        this.pendingCraftingTableStandPos = null;
        this.recoveryBreakTicks = 0;
        this.depositDelayTicks = 0;
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        this.returnTemporaryMainHandOnRestore = false;
        this.depositChestOpen = false;
        this.depositFinished = false;
        this.depositMovedAny = false;

        if (this.canRecoverTemporaryCraftingTable(serverLevel)) {
            this.recoveryTablePos = this.getTemporaryCraftingTablePos();
            this.equipAxeOrEmptyForRecovery();
            this.updateRecoveryDetail(serverLevel);
            return;
        }

        if (this.homeArea == null) {
            this.finishHomeAction(false);
            return;
        }

        if (this.beginPlaceCraftingTable(serverLevel)) {
            return;
        }

        boolean acted = this.placeBed(serverLevel)
                || this.placeChest(serverLevel);
        if (acted) {
            this.playerNpc.triggerMainHandUseAnimation();
            this.finishHomeAction(true);
            return;
        }

        if (this.beginDepositToChest(serverLevel)) {
            return;
        }

        this.finishHomeAction(false);
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (this.recoveryTablePos == null) {
            if (this.pendingCraftingTablePos != null) {
                this.tickPlaceCraftingTable(serverLevel);
                return;
            }
            if (this.depositChestPos != null) {
                this.tickDepositToChest(serverLevel);
            }
            return;
        }

        BlockState state = serverLevel.getBlockState(this.recoveryTablePos);
        if (!state.is(Blocks.CRAFTING_TABLE)) {
            this.playerNpc.clearBlockBreakProgress(this.recoveryTablePos);
            this.clearTemporaryCraftingTable();
            this.finishHomeAction(true);
            return;
        }
        this.equipAxeOrEmptyForRecovery();

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
            this.playerNpc.clearBlockBreakProgress(this.recoveryTablePos);
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
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, this.recoveryTablePos, state, this.playerNpc);
        }

        this.recoveryBreakTicks++;
        int requiredBreakTicks = this.getRequiredBreakTicks(serverLevel, this.recoveryTablePos, state);
        this.playerNpc.showBlockBreakProgress(this.recoveryTablePos, this.recoveryBreakTicks, requiredBreakTicks);
        this.updateRecoveryDetail(serverLevel);
        if (this.recoveryBreakTicks < requiredBreakTicks) {
            return;
        }

        BlockPos recoveredPos = this.recoveryTablePos;
        if (!serverLevel.destroyBlock(recoveredPos, false, this.playerNpc)) {
            this.playerNpc.clearBlockBreakProgress(recoveredPos);
            this.clearTemporaryCraftingTable();
            this.finishHomeAction(false);
            return;
        }
        this.playerNpc.clearBlockBreakProgress(recoveredPos);
        this.playerNpc.hurtMainHandItem(1);
        this.returnStack(new ItemStack(Items.CRAFTING_TABLE));
        this.clearTemporaryCraftingTable();
        this.finishHomeAction(true);
    }

    @Override
    public void stop() {
        if (this.depositChestOpen
                && this.depositChestPos != null
                && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.closeChest(serverLevel, this.depositChestPos);
        }
        this.playerNpc.clearBlockBreakProgress(this.recoveryTablePos);
        this.restorePreviousMainHand();
        this.homeArea = null;
        this.recoveryTablePos = null;
        this.depositChestPos = null;
        this.pendingCraftingTablePos = null;
        this.pendingCraftingTableStandPos = null;
        this.recoveryBreakTicks = 0;
        this.depositDelayTicks = 0;
        this.depositChestOpen = false;
        this.depositFinished = false;
        this.depositMovedAny = false;
        this.playerNpc.setCurrentAiDetail("");
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void finishHomeAction(boolean acted) {
        int cooldown = acted
                ? COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 8)
                : 20 + this.playerNpc.getRandom().nextInt(20);
        this.playerNpc.setManageHomeCooldown(cooldown);
        this.recoveryTablePos = null;
        this.pendingCraftingTablePos = null;
        this.pendingCraftingTableStandPos = null;
        this.depositFinished = true;
    }

    private boolean canRecoverTemporaryCraftingTable(ServerLevel serverLevel) {
        BlockPos pos = this.getTemporaryCraftingTablePos();
        if (pos == null) {
            return false;
        }

        if (this.isInsideBuildSiteCraftingTableSearchArea(pos)) {
            return false;
        }
        if (CraftBasicGearGoal.shouldKeepTemporaryCraftingTableForGear(this.playerNpc, serverLevel)) {
            return false;
        }

        return (this.homeArea == null || !PlayerNpcHomeUtil.isInside(this.homeArea, pos))
                && this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 6.0D * 6.0D
                && serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    private BlockPos getTemporaryCraftingTablePos() {
        return CraftBasicGearGoal.getTemporaryCraftingTablePos(this.playerNpc);
    }

    private void clearTemporaryCraftingTable() {
        CraftBasicGearGoal.clearTemporaryCraftingTable(this.playerNpc);
    }

    private boolean needsCraftingTable(ServerLevel serverLevel) {
        return this.findCraftingTableForHomeUse(serverLevel) == null
                && (InventoryUtils.hasItem(this.playerNpc, Items.CRAFTING_TABLE)
                || PlayerNpcCraftingUtil.canCraftCraftingTable(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()));
    }

    private boolean needsBed(ServerLevel serverLevel) {
        return this.findBed(serverLevel) == null
                && (this.hasBedItem() || PlayerNpcCraftingUtil.canCraftBed(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()));
    }

    private boolean needsChest(ServerLevel serverLevel) {
        return this.findHomeChest(serverLevel) == null
                && (InventoryUtils.hasItem(this.playerNpc, Items.CHEST)
                || PlayerNpcCraftingUtil.canCraftChest(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()));
    }

    private boolean beginPlaceCraftingTable(ServerLevel serverLevel) {
        if (!this.needsCraftingTable(serverLevel)) {
            return false;
        }

        BlockPos pos = this.findCraftingTablePlacement(serverLevel);
        if (pos == null) {
            return false;
        }
        BlockPos standPos = this.findCraftingTableStand(serverLevel, pos);
        if (standPos == null) {
            return false;
        }

        this.pendingCraftingTablePos = pos;
        this.pendingCraftingTableStandPos = standPos;
        this.playerNpc.setCurrentAiDetail("walking to crafting table spot");
        return true;
    }

    private void tickPlaceCraftingTable(ServerLevel serverLevel) {
        if (this.pendingCraftingTablePos == null || this.pendingCraftingTableStandPos == null) {
            this.finishHomeAction(false);
            return;
        }

        if (!this.isAtCraftingTableStand()) {
            this.playerNpc.setCurrentAiDetail("walking to crafting table spot");
            if (!this.moveToCraftingTableStand()) {
                this.finishHomeAction(false);
            }
            return;
        }

        if (!this.canPlaceBuildSiteCraftingTableAt(serverLevel, this.pendingCraftingTablePos)
                && !this.canPlaceUtilityAt(serverLevel, this.pendingCraftingTablePos)) {
            this.finishHomeAction(false);
            return;
        }

        ItemStack table = this.playerNpc.consumeInventoryItem(Items.CRAFTING_TABLE, 1).orElse(ItemStack.EMPTY);
        if (table.isEmpty() && !PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 4, this.playerNpc.getRawLogReserveTarget())) {
            this.finishHomeAction(false);
            return;
        }

        this.showPlacementItem(table.isEmpty() ? new ItemStack(Items.CRAFTING_TABLE) : table);
        serverLevel.setBlockAndUpdate(this.pendingCraftingTablePos, Blocks.CRAFTING_TABLE.defaultBlockState());
        this.lookAndSound(serverLevel, this.pendingCraftingTablePos, SoundEvents.WOOD_PLACE);
        this.playerNpc.triggerMainHandUseAnimation();
        this.finishHomeAction(true);
    }

    private boolean isNearHome() {
        if (this.homeArea == null) {
            return false;
        }

        BlockPos homeCenter = this.homeArea.origin().offset(this.homeArea.width() / 2, 1, this.homeArea.depth() / 2);
        return this.playerNpc.distanceToSqr(homeCenter.getX() + 0.5D, homeCenter.getY(), homeCenter.getZ() + 0.5D) <= HOME_ACTION_DISTANCE_SQR;
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
        if (chest.isEmpty() && !PlayerNpcCraftingUtil.tryCraftChest(serverLevel, this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
            return false;
        }
        if (chest.isEmpty()) {
            chest = this.playerNpc.consumeInventoryItem(Items.CHEST, 1).orElse(ItemStack.EMPTY);
        }
        if (chest.isEmpty()) {
            return false;
        }

        this.showPlacementItem(chest);
        serverLevel.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        this.playerNpc.setOwnedChestPos(pos);
        this.lookAndSound(serverLevel, pos, SoundEvents.WOOD_PLACE);
        return true;
    }

    private boolean placeBed(ServerLevel serverLevel) {
        if (!this.needsBed(serverLevel)) {
            return false;
        }

        if (!this.hasBedItem() && !PlayerNpcCraftingUtil.tryCraftBed(serverLevel, this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
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
        this.showPlacementItem(bedStack);
        serverLevel.setBlockAndUpdate(foot, footState);
        serverLevel.setBlockAndUpdate(head, headState);
        this.lookAndSound(serverLevel, foot, SoundEvents.WOOD_PLACE);
        return true;
    }

    private boolean shouldDepositToChest(ServerLevel serverLevel) {
        return this.inventoryMoreThanHalfFull()
                && this.findHomeChest(serverLevel) != null;
    }

    private boolean beginDepositToChest(ServerLevel serverLevel) {
        BlockPos chestPos = this.findHomeChest(serverLevel);
        if (chestPos == null || !(serverLevel.getBlockEntity(chestPos) instanceof ChestBlockEntity chest)) {
            return false;
        }
        if (!this.hasDepositCandidate(chest)) {
            return false;
        }

        this.depositChestPos = chestPos.immutable();
        this.depositDelayTicks = 0;
        this.depositChestOpen = false;
        this.depositFinished = false;
        this.depositMovedAny = false;
        this.updateDepositDetail();
        return true;
    }

    private void tickDepositToChest(ServerLevel serverLevel) {
        if (this.depositChestPos == null
                || !(serverLevel.getBlockEntity(this.depositChestPos) instanceof ChestBlockEntity chest)) {
            this.finishHomeAction(this.depositMovedAny);
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.depositChestPos.getX() + 0.5D,
                this.depositChestPos.getY() + 0.5D,
                this.depositChestPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (!this.depositChestOpen) {
            this.openChest(serverLevel, this.depositChestPos);
            this.depositChestOpen = true;
            this.depositDelayTicks = DEPOSIT_INTERVAL_TICKS;
            this.updateDepositDetail();
            return;
        }

        if (this.depositDelayTicks > 0) {
            this.depositDelayTicks--;
            return;
        }

        if (!this.inventoryMoreThanHalfFull()) {
            this.closeChest(serverLevel, this.depositChestPos);
            this.depositChestOpen = false;
            this.finishHomeAction(this.depositMovedAny);
            return;
        }

        int moved = this.depositNextStack(chest);
        if (moved > 0) {
            this.depositMovedAny = true;
            this.depositDelayTicks = DEPOSIT_INTERVAL_TICKS;
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
            serverLevel.playSound(null, this.depositChestPos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
            this.updateDepositDetail();
            return;
        }

        this.closeChest(serverLevel, this.depositChestPos);
        this.depositChestOpen = false;
        this.finishHomeAction(this.depositMovedAny);
    }

    private int depositNextStack(Container chest) {
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && this.inventoryMoreThanHalfFull(); i++) {
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
                inventory.setChanged();
                return moved;
            }
        }
        return 0;
    }

    private boolean hasDepositCandidate(Container chest) {
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || this.shouldKeepStack(stack)) {
                continue;
            }
            ItemStack toMove = stack.copy();
            toMove.setCount(Math.max(1, stack.getCount() / 2));
            if (toMove.getCount() > this.addToContainerPreview(chest, toMove).getCount()) {
                return true;
            }
        }
        return false;
    }

    private ItemStack addToContainerPreview(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty()
                    || !ItemStack.isSameItemSameTags(existing, remaining)
                    || existing.getCount() >= existing.getMaxStackSize()) {
                continue;
            }

            int transferable = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
            remaining.shrink(transferable);
        }

        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            if (container.getItem(i).isEmpty()) {
                remaining.shrink(Math.min(remaining.getCount(), remaining.getMaxStackSize()));
            }
        }
        return remaining;
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

    private BlockPos findCraftingTableForHomeUse(ServerLevel serverLevel) {
        if (this.homeArea == null) {
            return null;
        }

        for (BlockPos pos : BlockPos.betweenClosed(
                this.homeArea.origin().offset(-BUILD_SITE_CRAFTING_TABLE_MARGIN, -BUILD_SITE_CRAFTING_TABLE_SCAN_BELOW, -BUILD_SITE_CRAFTING_TABLE_MARGIN),
                this.homeArea.origin().offset(this.homeArea.width() + BUILD_SITE_CRAFTING_TABLE_MARGIN - 1, BUILD_SITE_CRAFTING_TABLE_SCAN_ABOVE, this.homeArea.depth() + BUILD_SITE_CRAFTING_TABLE_MARGIN - 1))) {
            if (serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findCraftingTablePlacement(ServerLevel serverLevel) {
        return this.findBuildSiteCraftingTablePlacement(serverLevel);
    }

    private BlockPos findBuildSiteCraftingTablePlacement(ServerLevel serverLevel) {
        if (this.homeArea == null) {
            return null;
        }

        for (int margin = 1; margin <= BUILD_SITE_CRAFTING_TABLE_MARGIN; margin++) {
            List<BlockPos> candidates = this.buildSiteOuterRing(margin);
            this.shuffleCandidates(candidates);
            for (BlockPos candidate : candidates) {
                if (this.canPlaceBuildSiteCraftingTableAt(serverLevel, candidate)
                        && this.findCraftingTableStand(serverLevel, candidate) != null) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private void shuffleCandidates(List<BlockPos> candidates) {
        for (int i = candidates.size() - 1; i > 0; i--) {
            Collections.swap(candidates, i, this.playerNpc.getRandom().nextInt(i + 1));
        }
    }

    private List<BlockPos> buildSiteOuterRing(int margin) {
        List<BlockPos> candidates = new ArrayList<>();
        int minX = -margin;
        int maxX = this.homeArea.width() + margin - 1;
        int minZ = -margin;
        int maxZ = this.homeArea.depth() + margin - 1;
        for (int x = minX; x <= maxX; x++) {
            candidates.add(this.homeArea.origin().offset(x, 0, minZ));
            candidates.add(this.homeArea.origin().offset(x, 0, maxZ));
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            candidates.add(this.homeArea.origin().offset(minX, 0, z));
            candidates.add(this.homeArea.origin().offset(maxX, 0, z));
        }
        return candidates;
    }

    private boolean canPlaceBuildSiteCraftingTableAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !this.isInsideBuildFootprint(pos)
                && PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private boolean isInsideBuildSiteCraftingTableSearchArea(BlockPos pos) {
        if (this.homeArea == null) {
            return false;
        }

        return pos.getX() >= this.homeArea.origin().getX() - BUILD_SITE_CRAFTING_TABLE_MARGIN
                && pos.getX() < this.homeArea.origin().getX() + this.homeArea.width() + BUILD_SITE_CRAFTING_TABLE_MARGIN
                && pos.getZ() >= this.homeArea.origin().getZ() - BUILD_SITE_CRAFTING_TABLE_MARGIN
                && pos.getZ() < this.homeArea.origin().getZ() + this.homeArea.depth() + BUILD_SITE_CRAFTING_TABLE_MARGIN
                && pos.getY() >= this.homeArea.origin().getY() - BUILD_SITE_CRAFTING_TABLE_SCAN_BELOW
                && pos.getY() <= this.homeArea.origin().getY() + BUILD_SITE_CRAFTING_TABLE_SCAN_ABOVE;
    }

    private boolean isInsideBuildFootprint(BlockPos pos) {
        if (this.homeArea == null) {
            return false;
        }

        return pos.getX() >= this.homeArea.origin().getX()
                && pos.getX() < this.homeArea.origin().getX() + this.homeArea.width()
                && pos.getZ() >= this.homeArea.origin().getZ()
                && pos.getZ() < this.homeArea.origin().getZ() + this.homeArea.depth();
    }

    private BlockPos findCraftingTableStand(ServerLevel serverLevel, BlockPos tablePos) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(tablePos.relative(direction));
        }

        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!this.canStandAt(serverLevel, immutable)
                    || this.distanceToTableSqr(immutable, tablePos) > 2.25D * 2.25D) {
                continue;
            }
            if (immutable.equals(this.playerNpc.blockPosition())) {
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
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private boolean isAtCraftingTableStand() {
        return this.pendingCraftingTableStandPos != null
                && this.pendingCraftingTablePos != null
                && this.playerNpc.distanceToSqr(this.pendingCraftingTableStandPos.getX() + 0.5D, this.pendingCraftingTableStandPos.getY(), this.pendingCraftingTableStandPos.getZ() + 0.5D) <= 1.25D * 1.25D
                && this.distanceToTableSqr(this.playerNpc.blockPosition(), this.pendingCraftingTablePos) <= 2.25D * 2.25D + 1.0D;
    }

    private boolean moveToCraftingTableStand() {
        if (this.pendingCraftingTableStandPos == null) {
            return false;
        }
        Path path = this.playerNpc.getNavigation().createPath(this.pendingCraftingTableStandPos, 0);
        if (path == null || !path.canReach()) {
            return false;
        }
        return this.playerNpc.getNavigation().moveTo(path, 1.0D);
    }

    private double distanceToTableSqr(BlockPos standPos, BlockPos tablePos) {
        if (tablePos == null) {
            return Double.MAX_VALUE;
        }
        double dx = standPos.getX() + 0.5D - (tablePos.getX() + 0.5D);
        double dy = standPos.getY() + 0.5D - (tablePos.getY() + 0.5D);
        double dz = standPos.getZ() + 0.5D - (tablePos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
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
                && PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)
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

    private BlockPos findHomeChest(ServerLevel serverLevel) {
        BlockPos ownedChestPos = this.playerNpc.getOwnedChestPos();
        if (ownedChestPos != null) {
            if (serverLevel.getBlockState(ownedChestPos).is(Blocks.CHEST)) {
                return ownedChestPos.immutable();
            }
            ChatUtil.missingHomeChest(this.playerNpc);
            this.playerNpc.setOwnedChestPos(null);
        }

        BlockPos chestPos = this.findBlock(serverLevel, Blocks.CHEST);
        if (chestPos != null) {
            this.playerNpc.setOwnedChestPos(chestPos);
        }
        return chestPos;
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

    private boolean inventoryMoreThanHalfFull() {
        return this.usedInventorySlots() > this.playerNpc.getInventory().getContainerSize() / 2;
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

    private void equipAxeOrEmptyForRecovery() {
        if (this.playerNpc.getMainHandItem().getItem() instanceof AxeItem) {
            return;
        }

        ItemStack axe = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof AxeItem, 1)
                .orElse(ItemStack.EMPTY);
        if (!axe.isEmpty()) {
            this.setTemporaryMainHand(axe);
            return;
        }

        this.setTemporaryMainHand(ItemStack.EMPTY);
    }

    private void setTemporaryMainHand(ItemStack stack) {
        this.setTemporaryMainHand(stack, true);
    }

    private void showPlacementItem(ItemStack stack) {
        this.setTemporaryMainHand(stack, false);
    }

    private void setTemporaryMainHand(ItemStack stack, boolean returnCurrentOnRestore) {
        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryTool) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryTool = true;
            this.returnTemporaryMainHandOnRestore = returnCurrentOnRestore;
        } else if (!currentMainHand.isEmpty()
                && this.returnTemporaryMainHandOnRestore
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        ItemStack held = stack.copy();
        held.setCount(Math.min(1, held.getCount()));
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, held);
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryTool) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && this.returnTemporaryMainHandOnRestore
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryTool = false;
        this.returnTemporaryMainHandOnRestore = false;
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

    private void updateDepositDetail() {
        if (this.depositChestPos == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "depositing to chest @ %d %d %d",
                this.depositChestPos.getX(),
                this.depositChestPos.getY(),
                this.depositChestPos.getZ()
        ));
    }

    private void openChest(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        serverLevel.blockEvent(pos, state.getBlock(), 1, 1);
        serverLevel.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
    }

    private void closeChest(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.getBlockState(pos).is(Blocks.CHEST)) {
            return;
        }

        BlockState state = serverLevel.getBlockState(pos);
        serverLevel.blockEvent(pos, state.getBlock(), 1, 0);
        serverLevel.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
    }

    private void lookAndSound(ServerLevel serverLevel, BlockPos pos, net.minecraft.sounds.SoundEvent soundEvent) {
        this.playerNpc.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 40.0F, 40.0F);
        serverLevel.playSound(null, pos, soundEvent, SoundSource.BLOCKS, 0.8F, 1.0F);
    }
}
