package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.init.SmartNpcModEntities;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import yesman.epicfight.api.forgeevent.EntityPatchRegistryEvent;

public final class EpicFightSmartNpcPatches {
    private EpicFightSmartNpcPatches() {
    }

    @SubscribeEvent
    public static void setPatch(EntityPatchRegistryEvent event) {
        event.getTypeEntry().put(SmartNpcModEntities.PLAYER_NPC.get(), entity -> BasicPlayerNpcPatch::new);
    }
}
