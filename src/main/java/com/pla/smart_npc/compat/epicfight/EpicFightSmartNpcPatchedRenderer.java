package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.init.SmartNpcModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import yesman.epicfight.api.client.forgeevent.PatchedRenderersEvent;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.client.renderer.patched.entity.PHumanoidRenderer;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD, value = {Dist.CLIENT})
public class EpicFightSmartNpcPatchedRenderer {
    @SubscribeEvent
    public static void onPatchedRenderer(PatchedRenderersEvent.Add add) {
        add.addPatchedEntityRenderer(SmartNpcModEntities.PLAYER_NPC.get(),
                (entitytype) -> (new PHumanoidRenderer<>(Meshes.BIPED, add.getContext(), entitytype))
                        .initLayerLast(add.getContext(), entitytype));
    }
}
