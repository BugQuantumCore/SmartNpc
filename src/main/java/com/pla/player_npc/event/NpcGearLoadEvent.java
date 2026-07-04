package com.pla.player_npc.event;

import com.pla.player_npc.PlayerNpc;
import com.pla.player_npc.util.EquipmentDataLoader;
import com.pla.player_npc.util.PlayerNpcBuildLayoutLoader;
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
