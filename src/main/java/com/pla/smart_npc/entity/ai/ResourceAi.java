package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Predicate;

public final class ResourceAi {
    private static final int[] LOG_SUPPLY_GOALS = {4, 5, 6, 7, 8, 9, 10, 11, 12};
    private static final int[] STONE_SUPPLY_GOALS = {12, 16, 20, 24};
    private static final int MIN_ADDITIONAL_SUPPLY = 3;
    private static final int MAX_ADDITIONAL_SUPPLY = 12;

    private ResourceAi() {
    }

    public static int randomLogSupplyGoal(RandomSource random) {
        return LOG_SUPPLY_GOALS[random.nextInt(LOG_SUPPLY_GOALS.length)];
    }

    public static int randomStoneSupplyGoal(RandomSource random) {
        return STONE_SUPPLY_GOALS[random.nextInt(STONE_SUPPLY_GOALS.length)];
    }

    public static int randomAdditionalSupplyAmount(RandomSource random) {
        return MIN_ADDITIONAL_SUPPLY
                + random.nextInt(MAX_ADDITIONAL_SUPPLY - MIN_ADDITIONAL_SUPPLY + 1);
    }

    public static int countLogs(PlayerNpcEntity playerNpc) {
        return countHeldAndInventoryItems(playerNpc, stack -> stack.is(ItemTags.LOGS));
    }

    public static int countStone(PlayerNpcEntity playerNpc) {
        return countHeldAndInventoryItems(playerNpc, stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    public static int countDirt(PlayerNpcEntity playerNpc) {
        return countHeldAndInventoryItems(playerNpc, stack -> stack.is(Items.DIRT));
    }

    public static int countHeldAndInventoryItems(PlayerNpcEntity playerNpc, Predicate<ItemStack> matcher) {
        int count = 0;
        ItemStack mainHand = playerNpc.getMainHandItem();
        if (!mainHand.isEmpty() && matcher.test(mainHand)) {
            count += mainHand.getCount();
        }
        ItemStack offhand = playerNpc.getOffhandItem();
        if (!offhand.isEmpty() && matcher.test(offhand)) {
            count += offhand.getCount();
        }
        for (int i = 0; i < playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = playerNpc.getInventory().getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
