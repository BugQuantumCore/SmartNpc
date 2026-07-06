package com.pla.smart_npc.network;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcBuildStatusUtil;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class PlayerNpcInspectorData {
    private PlayerNpcInspectorData() {
    }

    public static List<ItemStack> createSnapshot(PlayerNpcEntity playerNpc) {
        SimpleContainer inventory = playerNpc.getInventory();
        List<ItemStack> items = new ArrayList<>(6 + inventory.getContainerSize());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.MAINHAND).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.OFFHAND).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.HEAD).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.CHEST).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.LEGS).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.FEET).copy());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            items.add(inventory.getItem(i).copy());
        }
        return items;
    }

    public static String createBuildStatusText(PlayerNpcEntity playerNpc) {
        return PlayerNpcBuildStatusUtil.describe(playerNpc);
    }
}
