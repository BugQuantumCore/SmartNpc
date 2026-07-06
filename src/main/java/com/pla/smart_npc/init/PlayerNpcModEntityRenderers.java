package com.pla.smart_npc.init;

import com.pla.smart_npc.client.renderer.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;

@EventBusSubscriber(bus = Bus.MOD, value = {Dist.CLIENT})
public class PlayerNpcModEntityRenderers {

    @SubscribeEvent
    public static void registerEntityRenderers(RegisterRenderers registerrenderers) {
        registerrenderers.registerEntityRenderer(PlayerNpcModEntities.PLAYER_NPC.get(), FakePlayerRenderer::new);
    }
}
