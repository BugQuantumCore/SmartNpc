package com.pla.player_npc.event;

import com.pla.player_npc.PlayerNpc;
import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.network.PlayerNpcInspectatorModePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = PlayerNpc.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerNpcInspectatorEvent {
    @SubscribeEvent
    public static void onEntityMount(EntityMountEvent event) {
        if (event.isDismounting()
                && event.getEntityMounting() instanceof ServerPlayer serverPlayer
                && event.getEntityBeingMounted() instanceof PlayerNpcEntity
                && serverPlayer.isAlive()
                && PlayerNpcInspectatorModePacket.isInspectatorActive(serverPlayer)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            PlayerNpcInspectatorModePacket.restorePlayer(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            PlayerNpcInspectatorModePacket.restorePlayer(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            PlayerNpcInspectatorModePacket.restorePlayer(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            PlayerNpcInspectatorModePacket.restorePlayer(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer oldPlayer) {
            PlayerNpcInspectatorModePacket.restorePlayer(oldPlayer);
        }
        if (event.getEntity() instanceof ServerPlayer newPlayer) {
            PlayerNpcInspectatorModePacket.restorePlayer(newPlayer);
        }
    }
}
