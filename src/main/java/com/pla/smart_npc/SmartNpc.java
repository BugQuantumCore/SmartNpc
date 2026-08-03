package com.pla.smart_npc;

import com.mojang.serialization.Codec;
import com.pla.smart_npc.client.gui.InventoryViewerScreen;
import com.pla.smart_npc.compat.EpicFightCompat;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.event.NpcGearLoadEvent;
import com.pla.smart_npc.init.SmartNpcModCreativeTabs;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.init.SmartNpcModItems;
import com.pla.smart_npc.init.SmartNpcModMenus;
import com.pla.smart_npc.network.SmartNpcNetwork;
import com.pla.smart_npc.world.PlayerNpcMobSpawnBiomeModifier;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.BiomeModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(SmartNpc.MODID)
public class SmartNpc {
    public static final Logger LOGGER = LogManager.getLogger(SmartNpc.class);
    public static final String MODID = "smart_npc";

    public SmartNpc(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        SmartNpcModItems.REGISTRY.register(modEventBus);
        SmartNpcModMenus.REGISTRY.register(modEventBus);
        SmartNpcModEntities.REGISTRY.register(modEventBus);
        SmartNpcModCreativeTabs.register(modEventBus);
        SmartNpcNetwork.register();

        DeferredRegister<Codec<? extends BiomeModifier>> biomeModifiers =
                DeferredRegister.create(ForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, MODID);
        biomeModifiers.register(modEventBus);
        biomeModifiers.register("player_npc_spawns", PlayerNpcMobSpawnBiomeModifier::makeCodec);

        MinecraftForge.EVENT_BUS.register(new NpcGearLoadEvent());
        context.registerConfig(ModConfig.Type.COMMON, SmartNpcConfig.SPEC, "smart_npc-server.toml");
        EpicFightCompat.registerModEventHandlers(modEventBus);

        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(this::clientSetup);
        }
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        EpicFightCompat.enqueueRegisterArmatures(event);
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(SmartNpcModMenus.INVENTORY_VIEWER.get(), InventoryViewerScreen::new));
    }

    @Mod.EventBusSubscriber(modid = MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
        }
    }
}
