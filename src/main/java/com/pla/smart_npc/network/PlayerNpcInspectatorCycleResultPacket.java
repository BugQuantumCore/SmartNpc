package com.pla.smart_npc.network;

import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class PlayerNpcInspectatorCycleResultPacket {
    private final boolean handledByServer;
    private final int entityId;
    private final int direction;

    public PlayerNpcInspectatorCycleResultPacket(boolean handledByServer, int entityId, int direction) {
        this.handledByServer = handledByServer;
        this.entityId = entityId;
        this.direction = direction;
    }

    public static PlayerNpcInspectatorCycleResultPacket handled(int entityId) {
        return new PlayerNpcInspectatorCycleResultPacket(true, entityId, 0);
    }

    public static PlayerNpcInspectatorCycleResultPacket unhandled(int currentEntityId, int direction) {
        return new PlayerNpcInspectatorCycleResultPacket(false, currentEntityId, direction);
    }

    public static void encode(PlayerNpcInspectatorCycleResultPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.handledByServer);
        buffer.writeVarInt(packet.entityId);
        buffer.writeVarInt(packet.direction);
    }

    public static PlayerNpcInspectatorCycleResultPacket decode(FriendlyByteBuf buffer) {
        return new PlayerNpcInspectatorCycleResultPacket(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt());
    }

    public boolean handledByServer() {
        return handledByServer;
    }

    public int entityId() {
        return entityId;
    }

    public int direction() {
        return direction;
    }

    public static void handle(PlayerNpcInspectatorCycleResultPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> SmartNpcInspectorOverlay.handleInspectatorCycleResult(packet)
        ));
        context.setPacketHandled(true);
    }
}
