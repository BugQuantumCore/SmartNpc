package com.pla.smart_npc.event;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.network.PlayerNpcInspectatorModePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * Fabric port: wired from {@code SmartNpcEvents}; the per-player tick loop is
 * driven by {@code ServerTickEvents.END_SERVER_TICK}.
 */
public class PlayerNpcInspectatorEvent {
    public static void onServerTick(MinecraftServer server) {
        for (ServerPlayer serverPlayer : server.getPlayerList().getPlayers()) {
            if (PlayerNpcInspectatorModePacket.isInspectatorActive(serverPlayer)
                    && !PlayerNpcInspectatorModePacket.hasValidInspectatorTarget(serverPlayer)) {
                PlayerNpcInspectatorModePacket.restorePlayerAndClearInspector(serverPlayer);
            }
        }
    }

    public static void onLivingDeath(LivingEntity entity, DamageSource source) {
        if (entity instanceof ServerPlayer serverPlayer) {
            PlayerNpcInspectatorModePacket.restorePlayerAndClearInspector(serverPlayer);
        } else if (entity instanceof PlayerNpcEntity playerNpc) {
            restorePlayersInspecting(playerNpc);
        }
    }

    public static void onEntityLeaveLevel(Entity entity, Level level) {
        if (!level.isClientSide() && entity instanceof PlayerNpcEntity playerNpc) {
            restorePlayersInspecting(playerNpc);
        }
    }

    public static void onPlayerLoggedIn(ServerPlayer serverPlayer) {
        PlayerNpcInspectatorModePacket.restorePlayerAndClearInspector(serverPlayer);
    }

    public static void onPlayerLoggedOut(ServerPlayer serverPlayer) {
        PlayerNpcInspectatorModePacket.restorePlayer(serverPlayer);
    }

    public static void onPlayerRespawn(ServerPlayer serverPlayer) {
        PlayerNpcInspectatorModePacket.restorePlayerAndClearInspector(serverPlayer);
    }

    public static void onPlayerClone(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
        PlayerNpcInspectatorModePacket.restorePlayer(oldPlayer);
        PlayerNpcInspectatorModePacket.restorePlayerAndClearInspector(newPlayer);
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
