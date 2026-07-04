package com.pla.player_npc.init;

import com.pla.player_npc.PlayerNpc;
import com.pla.player_npc.item.*;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.world.item.Item.Properties;

public class PlayerNpcModItems {
    public static final DeferredRegister<Item> REGISTRY = DeferredRegister.create(ForgeRegistries.ITEMS, PlayerNpc.MODID);

    public static final RegistryObject<Item> INVENTORY_VIEWER = PlayerNpcModItems.REGISTRY.register("player_npc_inspector", InventoryViewerItem::new);
    public static final RegistryObject<Item> PLAYER_NPC_SPAWN_EGG = PlayerNpcModItems.REGISTRY.register("player_npc_spawn_egg", () -> new ForgeSpawnEggItem(PlayerNpcModEntities.PLAYER_NPC, 0xFFF144, 0x69DFDA, (new Properties())));

}
