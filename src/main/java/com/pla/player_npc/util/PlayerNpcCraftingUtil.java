package com.pla.player_npc.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Predicate;

public final class PlayerNpcCraftingUtil {
    private PlayerNpcCraftingUtil() {
    }

    public static int countPlankEquivalent(SimpleContainer inventory) {
        return countItem(inventory, PlayerNpcCraftingUtil::isPlanks)
                + countItem(inventory, PlayerNpcCraftingUtil::isLogs) * 4;
    }

    public static int countSticks(SimpleContainer inventory) {
        return countItem(inventory, stack -> stack.is(Items.STICK));
    }

    public static boolean canProvidePlanksAndSticks(SimpleContainer inventory, int planksNeeded, int sticksNeeded) {
        int availablePlanks = countPlankEquivalent(inventory);
        int availableSticks = countSticks(inventory);
        int missingSticks = Math.max(0, sticksNeeded - availableSticks);
        int planksForSticks = ((missingSticks + 3) / 4) * 2;
        return availablePlanks >= planksNeeded + planksForSticks;
    }

    public static boolean canCraftCraftingTable(SimpleContainer inventory) {
        return countPlankEquivalent(inventory) >= 4;
    }

    public static boolean canCraftChest(SimpleContainer inventory) {
        return countPlankEquivalent(inventory) >= 8;
    }

    public static boolean canCraftFurnace(SimpleContainer inventory) {
        return countFurnaceStone(inventory) >= 8;
    }

    public static boolean canCraftBed(SimpleContainer inventory) {
        return getCraftableBed(inventory) != null
                && countPlankEquivalent(inventory) >= 3;
    }

    public static boolean tryConsumePlanksAndSticks(SimpleContainer inventory, int planksNeeded, int sticksNeeded) {
        if (!canProvidePlanksAndSticks(inventory, planksNeeded, sticksNeeded)) {
            return false;
        }

        while (countSticks(inventory) < sticksNeeded) {
            if (!tryConsumePlanks(inventory, 2)) {
                return false;
            }
            InventoryUtils.addItem(inventory, new ItemStack(Items.STICK, 4));
        }

        return consumeItem(inventory, stack -> stack.is(Items.STICK), sticksNeeded)
                && tryConsumePlanks(inventory, planksNeeded);
    }

    public static boolean tryConsumePlanks(SimpleContainer inventory, int count) {
        if (count <= 0) {
            return true;
        }

        while (countItem(inventory, PlayerNpcCraftingUtil::isPlanks) < count) {
            if (!convertOneLogToPlanks(inventory)) {
                return false;
            }
        }

        return consumeItem(inventory, PlayerNpcCraftingUtil::isPlanks, count);
    }

    public static boolean tryCraftChest(SimpleContainer inventory) {
        if (!tryConsumePlanks(inventory, 8)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.CHEST));
    }

    public static boolean tryCraftFurnace(SimpleContainer inventory) {
        if (!consumeFurnaceStone(inventory, 8)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.FURNACE));
    }

    public static boolean tryCraftBed(SimpleContainer inventory) {
        Item wool = getCraftableWool(inventory);
        Item bed = wool == null ? null : getBedForWool(wool);
        if (bed == null || !tryConsumePlanks(inventory, 3)) {
            return false;
        }
        if (!consumeItem(inventory, stack -> stack.is(wool), 3)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(bed));
    }

    public static int countItem(SimpleContainer inventory, Predicate<ItemStack> matcher) {
        int count = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static int countFurnaceStone(SimpleContainer inventory) {
        return countItem(inventory, stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    public static boolean consumeFurnaceStone(SimpleContainer inventory, int count) {
        return consumeItem(inventory, stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE), count);
    }

    public static boolean consumeItem(SimpleContainer inventory, Predicate<ItemStack> matcher, int count) {
        int remaining = count;
        for (int i = 0; i < inventory.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !matcher.test(stack)) {
                continue;
            }

            int used = Math.min(remaining, stack.getCount());
            stack.shrink(used);
            remaining -= used;
            if (stack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }
        inventory.setChanged();
        return remaining == 0;
    }

    public static boolean isLogs(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.LOGS) && getPlanksForLog(stack) != null;
    }

    public static boolean isPlanks(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.PLANKS);
    }

    private static boolean convertOneLogToPlanks(SimpleContainer inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.is(ItemTags.LOGS)) {
                continue;
            }

            Item planks = getPlanksForLog(stack);
            if (planks == null) {
                continue;
            }

            stack.shrink(1);
            if (stack.isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
            }
            inventory.setChanged();
            return InventoryUtils.addItem(inventory, new ItemStack(planks, 4));
        }
        return false;
    }

    private static Item getCraftableWool(SimpleContainer inventory) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty() || !stack.is(ItemTags.WOOL) || getBedForWool(stack.getItem()) == null) {
                continue;
            }
            Item wool = stack.getItem();
            if (countItem(inventory, candidate -> candidate.is(wool)) >= 3) {
                return wool;
            }
        }
        return null;
    }

    private static Item getCraftableBed(SimpleContainer inventory) {
        Item wool = getCraftableWool(inventory);
        return wool == null ? null : getBedForWool(wool);
    }

    private static Item getPlanksForLog(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (key == null) {
            return null;
        }

        String path = key.getPath();
        if (path.startsWith("stripped_")) {
            path = path.substring("stripped_".length());
        }

        String plankPath = null;
        if (path.endsWith("_log")) {
            plankPath = path.substring(0, path.length() - "_log".length()) + "_planks";
        } else if (path.endsWith("_wood")) {
            plankPath = path.substring(0, path.length() - "_wood".length()) + "_planks";
        } else if (path.endsWith("_stem")) {
            plankPath = path.substring(0, path.length() - "_stem".length()) + "_planks";
        } else if (path.endsWith("_hyphae")) {
            plankPath = path.substring(0, path.length() - "_hyphae".length()) + "_planks";
        } else if (path.equals("bamboo_block")) {
            plankPath = "bamboo_planks";
        }

        if (plankPath == null) {
            return null;
        }

        return ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath(key.getNamespace(), plankPath));
    }

    private static Item getBedForWool(Item wool) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(wool);
        if (key == null || !key.getPath().endsWith("_wool")) {
            return null;
        }

        String bedPath = key.getPath().substring(0, key.getPath().length() - "_wool".length()) + "_bed";
        return ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath(key.getNamespace(), bedPath));
    }
}
