package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class ToolAi {
    private final PlayerNpcEntity playerNpc;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private boolean swappedMainHand;

    public ToolAi(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
    }

    public boolean equipBestToolFor(BlockState state) {
        Class<?> toolClass = preferredToolFor(state);
        if (toolClass == null) {
            return true;
        }
        if (this.equipTool(toolClass)) {
            return true;
        }
        if (toolClass == ShovelItem.class) {
            this.equipEmptyMainHand();
        }
        return false;
    }

    public boolean hasPreferredToolFor(BlockState state) {
        Class<?> toolClass = preferredToolFor(state);
        return toolClass == null || this.hasTool(toolClass);
    }

    public boolean hasTool(Class<?> toolClass) {
        if (toolClass.isInstance(this.playerNpc.getMainHandItem().getItem())
                || toolClass.isInstance(this.playerNpc.getOffhandItem().getItem())) {
            return true;
        }
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (!stack.isEmpty() && toolClass.isInstance(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    public static Class<?> preferredToolFor(BlockState state) {
        if (state.is(BlockTags.LOGS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return AxeItem.class;
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)) {
            return ShovelItem.class;
        }
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return PickaxeItem.class;
        }
        return null;
    }

    public boolean equipTool(Class<?> toolClass) {
        if (toolClass.isInstance(this.playerNpc.getMainHandItem().getItem())) {
            return true;
        }
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (stack.isEmpty() || !toolClass.isInstance(stack.getItem())) {
                continue;
            }

            this.swapMainHandWithSlot(i, stack);
            return true;
        }
        return false;
    }

    public boolean equipItem(ItemLike itemLike) {
        if (this.playerNpc.getMainHandItem().is(itemLike.asItem())) {
            return true;
        }
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.is(itemLike.asItem())) {
                continue;
            }

            this.swapMainHandWithSlot(i, stack);
            return true;
        }
        return false;
    }

    public void restoreMainHand() {
        if (!this.swappedMainHand) {
            return;
        }
        ItemStack current = this.playerNpc.getMainHandItem().copy();
        this.playerNpc.setItemInHand(InteractionHand.MAIN_HAND, this.previousMainHand);
        if (!current.isEmpty()) {
            InventoryUtils.addItem(this.playerNpc.getInventory(), current);
        }
        this.swappedMainHand = false;
        this.previousMainHand = ItemStack.EMPTY;
    }

    private void equipEmptyMainHand() {
        ItemStack current = this.playerNpc.getMainHandItem().copy();
        if (current.isEmpty()) {
            return;
        }
        if (!this.swappedMainHand) {
            this.previousMainHand = current;
            this.swappedMainHand = true;
        } else if (!InventoryUtils.addItem(this.playerNpc.getInventory(), current)) {
            this.playerNpc.spawnAtLocation(current);
        }
        this.playerNpc.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
    }

    private void swapMainHandWithSlot(int slot, ItemStack stack) {
        ItemStack current = this.playerNpc.getMainHandItem().copy();
        if (!this.swappedMainHand) {
            this.previousMainHand = current;
            this.swappedMainHand = true;
        } else if (!current.isEmpty() && !InventoryUtils.addItem(this.playerNpc.getInventory(), current)) {
            this.playerNpc.spawnAtLocation(current);
        }
        this.playerNpc.setItemInHand(InteractionHand.MAIN_HAND, stack.copy());
        this.playerNpc.getInventory().setItem(slot, ItemStack.EMPTY);
    }
}
