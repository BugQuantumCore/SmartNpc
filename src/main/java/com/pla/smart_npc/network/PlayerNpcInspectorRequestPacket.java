package com.pla.smart_npc.network;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class PlayerNpcInspectorRequestPacket {
    private static final double MAX_REFRESH_DISTANCE_SQR = 64.0D * 64.0D;

    private final int entityId;

    public PlayerNpcInspectorRequestPacket(int entityId) {
        this.entityId = entityId;
    }

    public static void encode(PlayerNpcInspectorRequestPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.entityId);
    }

    public static PlayerNpcInspectorRequestPacket decode(FriendlyByteBuf buffer) {
        return new PlayerNpcInspectorRequestPacket(buffer.readVarInt());
    }

    public static void handle(PlayerNpcInspectorRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }

            Entity entity = sender.level().getEntity(packet.entityId);
            if (!(entity instanceof PlayerNpcEntity playerNpc)
                    || !playerNpc.isAlive()
                    || sender.distanceToSqr(playerNpc) > MAX_REFRESH_DISTANCE_SQR) {
                PlayerNpcNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> sender),
                        PlayerNpcInspectorPacket.clear()
                );
                return;
            }

            PlayerNpcNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> sender),
                    new PlayerNpcInspectorPacket(
                            playerNpc.getId(),
                            PlayerNpcInspectorData.createSnapshot(playerNpc),
                            PlayerNpcInspectorData.createBuildStatusText(playerNpc)
                    )
            );
        });
        context.setPacketHandled(true);
    }
}
