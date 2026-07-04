package com.pla.player_npc.init;

import com.pla.player_npc.PlayerNpc;
import com.pla.player_npc.inventory.InventoryViewerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class PlayerNpcModMenus {
    public static final DeferredRegister<MenuType<?>> REGISTRY = DeferredRegister.create(ForgeRegistries.MENU_TYPES, PlayerNpc.MODID);

    public static final RegistryObject<MenuType<InventoryViewerMenu>> INVENTORY_VIEWER = REGISTRY.register(
            "inventory_viewer",
            () -> IForgeMenuType.create(InventoryViewerMenu::new)
    );
}
