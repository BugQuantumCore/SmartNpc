package com.pla.player_npc.network;

import com.pla.player_npc.client.gui.PlayerNpcInspectorOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class PlayerNpcInspectorPacket {
    private final int entityId;
    private final List<ItemStack> items;

    public PlayerNpcInspectorPacket(int entityId, List<ItemStack> items) {
        this.entityId = entityId;
        this.items = List.copyOf(items);
    }

    public static PlayerNpcInspectorPacket clear() {
        return new PlayerNpcInspectorPacket(-1, List.of());
    }

    public int entityId() {
        return entityId;
    }

    public List<ItemStack> items() {
        return items;
    }

    public static void encode(PlayerNpcInspectorPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.entityId);
        buffer.writeVarInt(packet.items.size());
        for (ItemStack stack : packet.items) {
            buffer.writeItem(stack);
        }
    }

    public static PlayerNpcInspectorPacket decode(FriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        int size = buffer.readVarInt();
        List<ItemStack> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(buffer.readItem());
        }
        return new PlayerNpcInspectorPacket(entityId, items);
    }

    public static void handle(PlayerNpcInspectorPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> PlayerNpcInspectorOverlay.handlePacket(packet)
        ));
        context.setPacketHandled(true);
    }
}
