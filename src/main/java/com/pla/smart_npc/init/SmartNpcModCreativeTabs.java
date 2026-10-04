package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public class SmartNpcModCreativeTabs {

    public static final CreativeModeTab PLAYER_NPC_TAB = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            new ResourceLocation(SmartNpc.MODID, "player_npc_tab"),
            CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .icon(() -> new ItemStack(SmartNpcModItems.INVENTORY_VIEWER))
                    .title(Component.translatable("creativetab.player_npc_tab"))
                    .displayItems((parameters, output) -> {
                        output.accept(SmartNpcModItems.INVENTORY_VIEWER);
                        output.accept(SmartNpcModItems.PLAYER_NPC_SPAWN_EGG);
                    })
                    .build()
    );

    public static void register() {
        // Registration happens during class initialization.
    }
}
