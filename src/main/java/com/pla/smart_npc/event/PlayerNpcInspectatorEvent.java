package com.pla.smart_npc.event;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.network.PlayerNpcInspectatorModePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SmartNpc.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerNpcInspectatorEvent {
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END
                && event.player instanceof ServerPlayer serverPlayer
                && PlayerNpcInspectatorModePacket.isInspectatorActive(serverPlayer)
                && !PlayerNpcInspectatorModePacket.hasValidInspectatorTarget(serverPlayer)) {
            PlayerNpcInspectatorModePacket.restorePlayerAndClearInspector(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            PlayerNpcInspectatorModePacket.restorePlayerAndClearInspector(serverPlayer);
        } else if (event.getEntity() instanceof PlayerNpcEntity playerNpc) {
            restorePlayersInspecting(playerNpc);
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof PlayerNpcEntity playerNpc) {
            restorePlayersInspecting(playerNpc);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            PlayerNpcInspectatorModePacket.restorePlayerAndClearInspector(serverPlayer);
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
            PlayerNpcInspectatorModePacket.restorePlayerAndClearInspector(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        if (event.getOriginal() instanceof ServerPlayer oldPlayer) {
            PlayerNpcInspectatorModePacket.restorePlayer(oldPlayer);
        }
        if (event.getEntity() instanceof ServerPlayer newPlayer) {
            PlayerNpcInspectatorModePacket.restorePlayerAndClearInspector(newPlayer);
        }
    }

    private static void restorePlayersInspecting(PlayerNpcEntity playerNpc) {
        MinecraftServer server = playerNpc.getServer();
        if (server == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (PlayerNpcInspectatorModePacket.isInspecting(player, playerNpc)) {
                PlayerNpcInspectatorModePacket.restorePlayerAndClearInspector(player);
            }
        }
    }
}
