package com.pla.smart_npc.network;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PlayerNpcInspectatorModePacket {
    private static final String ACTIVE_KEY = "PlayerNpcInspectatorActive";
    private static final String ORIGINAL_GAME_MODE_KEY = "PlayerNpcInspectatorOriginalGameMode";
    private static final double MAX_START_DISTANCE_SQR = 96.0D * 96.0D;

    private final boolean active;
    private final int entityId;

    public PlayerNpcInspectatorModePacket(boolean active, int entityId) {
        this.active = active;
        this.entityId = entityId;
    }

    public static void encode(PlayerNpcInspectatorModePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.active);
        buffer.writeVarInt(packet.entityId);
    }

    public static PlayerNpcInspectatorModePacket decode(FriendlyByteBuf buffer) {
        return new PlayerNpcInspectatorModePacket(buffer.readBoolean(), buffer.readVarInt());
    }

    public static void handle(PlayerNpcInspectatorModePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }

            if (!packet.active) {
                restorePlayer(sender);
                return;
            }

            Entity entity = sender.level().getEntity(packet.entityId);
            if (!(entity instanceof PlayerNpcEntity playerNpc)
                    || !playerNpc.isAlive()
                    || sender.distanceToSqr(playerNpc) > MAX_START_DISTANCE_SQR) {
                restorePlayer(sender);
                return;
            }

            beginInspectator(sender, playerNpc);
        });
        context.setPacketHandled(true);
    }

    public static void restorePlayer(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        boolean wasActive = data.getBoolean(ACTIVE_KEY);
        int originalGameMode = data.contains(ORIGINAL_GAME_MODE_KEY)
                ? data.getInt(ORIGINAL_GAME_MODE_KEY)
                : player.gameMode.getGameModeForPlayer().getId();
        data.remove(ACTIVE_KEY);
        data.remove(ORIGINAL_GAME_MODE_KEY);

        if (player.getVehicle() instanceof PlayerNpcEntity) {
            player.stopRiding();
        }

        if (wasActive) {
            player.setGameMode(GameType.byId(originalGameMode));
        }
    }

    public static boolean isInspectatorActive(Entity entity) {
        return entity != null && entity.getPersistentData().getBoolean(ACTIVE_KEY);
    }

    public static void beginInspectator(ServerPlayer player, PlayerNpcEntity playerNpc) {
        beginInspectator(player, playerNpc, false);
    }

    public static void beginInspectator(ServerPlayer player, PlayerNpcEntity playerNpc, boolean teleportToNpc) {
        if (teleportToNpc && playerNpc.level() instanceof ServerLevel serverLevel) {
            if (player.getVehicle() != null && player.getVehicle() != playerNpc) {
                player.stopRiding();
            }
            player.teleportTo(serverLevel, playerNpc.getX(), playerNpc.getY(), playerNpc.getZ(), playerNpc.getYRot(), playerNpc.getXRot());
        }

        PlayerNpcGoalTraceLogger.stopIfTracingDifferentNpc(player, playerNpc);

        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(ACTIVE_KEY)) {
            data.putInt(ORIGINAL_GAME_MODE_KEY, player.gameMode.getGameModeForPlayer().getId());
            data.putBoolean(ACTIVE_KEY, true);
        }

        player.setGameMode(GameType.SPECTATOR);
        if (player.getVehicle() != playerNpc) {
            player.stopRiding();
            player.startRiding(playerNpc, true);
        }
    }
}
