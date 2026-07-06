package com.pla.smart_npc.event;

import com.pla.smart_npc.PlayerNpc;
import com.pla.smart_npc.util.EquipmentDataLoader;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PlayerNpc.MODID)
public class NpcGearLoadEvent {
    @SubscribeEvent
    public void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new EquipmentDataLoader());
        event.addListener(new PlayerNpcBuildLayoutLoader());
    }
}
