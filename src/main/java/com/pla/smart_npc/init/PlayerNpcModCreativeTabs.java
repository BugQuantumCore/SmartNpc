package com.pla.smart_npc.init;

import com.pla.smart_npc.PlayerNpc;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class PlayerNpcModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, PlayerNpc.MODID);

    public static final RegistryObject<CreativeModeTab> PLAYER_NPC_TAB = CREATIVE_MODE_TABS.register("player_npc_tab",
            () -> CreativeModeTab.builder()
                    .icon(() -> new ItemStack(PlayerNpcModItems.INVENTORY_VIEWER.get()))
                    .title(Component.translatable("creativetab.player_npc_tab"))
                    .displayItems((pParameters, pOutput) -> {
                        pOutput.accept(PlayerNpcModItems.INVENTORY_VIEWER.get());
                        pOutput.accept(PlayerNpcModItems.PLAYER_NPC_SPAWN_EGG.get());
                    })
                    .build());

    public static void register(IEventBus eventBus) {
        CREATIVE_MODE_TABS.register(eventBus);
    }
}
