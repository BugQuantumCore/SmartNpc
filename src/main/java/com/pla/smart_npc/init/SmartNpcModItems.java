package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.item.*;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.world.item.Item.Properties;

public class SmartNpcModItems {
    public static final DeferredRegister<Item> REGISTRY = DeferredRegister.create(ForgeRegistries.ITEMS, SmartNpc.MODID);

    public static final RegistryObject<Item> INVENTORY_VIEWER = SmartNpcModItems.REGISTRY.register("player_npc_inspector", InventoryViewerItem::new);
    public static final RegistryObject<Item> PLAYER_NPC_SPAWN_EGG = SmartNpcModItems.REGISTRY.register("player_npc_spawn_egg", () -> new ForgeSpawnEggItem(SmartNpcModEntities.PLAYER_NPC, 0xFFF144, 0x69DFDA, (new Properties())));

}
