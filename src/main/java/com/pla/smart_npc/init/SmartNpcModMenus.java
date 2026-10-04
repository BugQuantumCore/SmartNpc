package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.inventory.InventoryViewerMenu;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;

/**
 * Fabric port of the Forge menu registry. {@code IForgeMenuType.create(...)}
 * is replaced by Fabric's {@link ExtendedScreenHandlerType}, which likewise
 * forwards the buffer written while opening the screen.
 */
public class SmartNpcModMenus {

    public static final MenuType<InventoryViewerMenu> INVENTORY_VIEWER = Registry.register(
            BuiltInRegistries.MENU,
            new ResourceLocation(SmartNpc.MODID, "inventory_viewer"),
            new ExtendedScreenHandlerType<>(InventoryViewerMenu::new)
    );

    public static void register() {
        // Registration happens during class initialization.
    }
}
