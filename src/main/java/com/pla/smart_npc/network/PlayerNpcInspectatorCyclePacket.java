package com.pla.smart_npc.network;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcForceTickManager;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Optional;
import java.util.function.Supplier;

public class PlayerNpcInspectatorCyclePacket {
    private final int currentEntityId;
    private final int direction;
    private final boolean includeRequirements;

    public PlayerNpcInspectatorCyclePacket(int currentEntityId, int direction, boolean includeRequirements) {
        this.currentEntityId = currentEntityId;
        this.direction = direction;
        this.includeRequirements = includeRequirements;
    }

    public static void encode(PlayerNpcInspectatorCyclePacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.currentEntityId);
        buffer.writeVarInt(packet.direction);
        buffer.writeBoolean(packet.includeRequirements);
    }

    public static PlayerNpcInspectatorCyclePacket decode(FriendlyByteBuf buffer) {
        return new PlayerNpcInspectatorCyclePacket(buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean());
    }

    public static void handle(PlayerNpcInspectatorCyclePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }

            if (!PlayerNpcInspectatorModePacket.isInspectatorActive(sender)) {
                sendResult(sender, PlayerNpcInspectatorCycleResultPacket.handled(packet.currentEntityId));
                return;
            }

            if (!PlayerNpcForceTickManager.isEnabled()) {
                sendResult(sender, PlayerNpcInspectatorCycleResultPacket.unhandled(packet.currentEntityId, packet.direction));
                return;
            }

            Optional<PlayerNpcEntity> currentNpc = PlayerNpcForceTickManager.findTrackedByEntityId(
                    sender.server,
                    sender.serverLevel(),
                    packet.currentEntityId
            );
            Optional<PlayerNpcEntity> nextNpc = PlayerNpcForceTickManager.findNextForInspectator(
                    sender.server,
                    currentNpc.orElse(null),
                    packet.direction
            );
            if (nextNpc.isEmpty()) {
                sendResult(sender, PlayerNpcInspectatorCycleResultPacket.handled(packet.currentEntityId));
                return;
            }

            PlayerNpcEntity target = nextNpc.get();
            PlayerNpcInspectatorModePacket.beginInspectator(sender, target, true);
            sendResult(sender, PlayerNpcInspectatorCycleResultPacket.handled(target.getId()));
            SmartNpcNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> sender),
                    new PlayerNpcInspectorPacket(
                            target.getId(),
                            PlayerNpcInspectorData.createSnapshot(target),
                            PlayerNpcInspectorData.createBuildStatusText(target),
                            PlayerNpcInspectorData.createPerformanceText(),
                            PlayerNpcInspectorData.createDailyJobText(target),
                            packet.includeRequirements ? PlayerNpcInspectorData.createBuildRequirementsText(target) : "",
                            PlayerNpcGoalTraceLogger.isTracing(sender, target)
                    )
            );
        });
        context.setPacketHandled(true);
    }

    private static void sendResult(ServerPlayer player, PlayerNpcInspectatorCycleResultPacket packet) {
        SmartNpcNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
