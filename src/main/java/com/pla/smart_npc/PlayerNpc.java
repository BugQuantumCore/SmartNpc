package com.pla.smart_npc;

import com.mojang.serialization.Codec;
import com.pla.smart_npc.client.gui.InventoryViewerScreen;
import com.pla.smart_npc.config.PlayerNpcConfig;
import com.pla.smart_npc.event.NpcGearLoadEvent;
import com.pla.smart_npc.init.PlayerNpcModCreativeTabs;
import com.pla.smart_npc.init.PlayerNpcModEntities;
import com.pla.smart_npc.init.PlayerNpcModItems;
import com.pla.smart_npc.init.PlayerNpcModMenus;
import com.pla.smart_npc.network.PlayerNpcNetwork;
import com.pla.smart_npc.world.PlayerNpcMobSpawnBiomeModifier;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(PlayerNpc.MODID)
public class PlayerNpc {
    public static final Logger LOGGER = LogManager.getLogger(PlayerNpc.class);
    public static final String MODID = "smart_npc";

    public PlayerNpc(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        PlayerNpcModItems.REGISTRY.register(modEventBus);
        PlayerNpcModMenus.REGISTRY.register(modEventBus);
        PlayerNpcModEntities.REGISTRY.register(modEventBus);
        PlayerNpcModCreativeTabs.register(modEventBus);
        PlayerNpcNetwork.register();

        DeferredRegister<Codec<? extends BiomeModifier>> biomeModifiers =
                DeferredRegister.create(ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, MODID);
        biomeModifiers.register(modEventBus);
        biomeModifiers.register("player_npc_spawns", PlayerNpcMobSpawnBiomeModifier::makeCodec);

        MinecraftForge.EVENT_BUS.register(new NpcGearLoadEvent());
        context.registerConfig(ModConfig.Type.COMMON, PlayerNpcConfig.SPEC, "smart_npc-server.toml");

        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(this::clientSetup);
        }
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(PlayerNpcModMenus.INVENTORY_VIEWER.get(), InventoryViewerScreen::new));
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
        }
    }
}
