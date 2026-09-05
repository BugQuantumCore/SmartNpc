package com.pla.smart_npc.util;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;

/** Conservative inventory cleanup; a stack marker also prevents merging with ordinary loot. */
public final class PlayerNpcTrashUtil {
    private static final String DISCARDED = "SmartNpcDiscardedTrash";

    private PlayerNpcTrashUtil() {}

    public static boolean isDiscarded(ItemStack stack) {
        return stack.hasTag() && stack.getTag().getBoolean(DISCARDED);
    }

    public static ItemStack discardedCopy(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.getOrCreateTag().putBoolean(DISCARDED, true);
        return copy;
    }

    public static int occupiedSlots(Container inventory) {
        int occupied = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (!inventory.getItem(slot).isEmpty()) occupied++;
        }
        return occupied;
    }

    public static boolean isTrash(ItemStack stack, Container inventory, ItemStack mainHand, ItemStack offHand) {
        if (stack.isEmpty() || stack.hasCustomHoverName() || stack.isEnchanted() || stack.isEdible()) return false;
        if (stack.is(ItemTags.SAPLINGS) || stack.is(ItemTags.FLOWERS) || isDecorativePlant(stack)) return true;
        if (toolKind(stack) == 0) return false;
        if (isUpgrade(stack, mainHand) || isUpgrade(stack, offHand)) return true;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isUpgrade(stack, inventory.getItem(slot))) return true;
        }
        return false;
    }

    private static boolean isDecorativePlant(ItemStack stack) {
        return stack.is(Items.GRASS) || stack.is(Items.TALL_GRASS)
                || stack.is(Items.FERN) || stack.is(Items.LARGE_FERN)
                || stack.is(Items.DEAD_BUSH) || stack.is(Items.VINE)
                || stack.is(Items.GLOW_LICHEN) || stack.is(Items.HANGING_ROOTS)
                || stack.is(Items.SEAGRASS);
    }

    private static boolean isUpgrade(ItemStack oldStack, ItemStack replacement) {
        if (replacement.isEmpty() || isDiscarded(replacement) || toolKind(oldStack) != toolKind(replacement)
                || !(oldStack.getItem() instanceof TieredItem oldTool)
                || !(replacement.getItem() instanceof TieredItem newTool)) return false;
        // Never throw away a working tool for a nearly broken replacement, or guess modded tier ordering.
        if (!(oldTool.getTier() instanceof Tiers) || !(newTool.getTier() instanceof Tiers)) return false;
        if (replacement.getMaxDamage() - replacement.getDamageValue() < Math.max(16, replacement.getMaxDamage() / 10)) return false;
        return newTool.getTier().getLevel() > oldTool.getTier().getLevel()
                && newTool.getTier().getUses() > oldTool.getTier().getUses();
    }

    private static int toolKind(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof PickaxeItem) return 1;
        if (item instanceof AxeItem) return 2;
        if (item instanceof ShovelItem) return 3;
        if (item instanceof HoeItem) return 4;
        if (item instanceof SwordItem) return 5;
        return 0;
    }
}
