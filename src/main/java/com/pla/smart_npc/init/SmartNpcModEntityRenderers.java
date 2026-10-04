package com.pla.smart_npc.init;

import com.pla.smart_npc.client.renderer.FakePlayerRenderer;
import com.pla.smart_npc.client.renderer.PlayerNpcFishingBobberRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

@Environment(EnvType.CLIENT)
public class SmartNpcModEntityRenderers {

    @Environment(EnvType.CLIENT)
    public static void registerEntityRenderers() {
        // Forge EntityRenderersEvent.RegisterRenderers equivalent.
        EntityRendererRegistry.register(SmartNpcModEntities.PLAYER_NPC, FakePlayerRenderer::new);
        EntityRendererRegistry.register(SmartNpcModEntities.PLAYER_NPC_FISHING_BOBBER, PlayerNpcFishingBobberRenderer::new);
    }
}
