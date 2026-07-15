package com.pla.smart_npc.network;

import com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay;
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
    private final String buildStatusText;
    private final String performanceText;
    private final String dailyJobText;
    private final String requirementsText;
    private final boolean traceEnabled;

    public PlayerNpcInspectorPacket(int entityId, List<ItemStack> items) {
        this(entityId, items, "");
    }

    public PlayerNpcInspectorPacket(int entityId, List<ItemStack> items, String buildStatusText) {
        this(entityId, items, buildStatusText, "");
    }

    public PlayerNpcInspectorPacket(int entityId, List<ItemStack> items, String buildStatusText, String performanceText) {
        this(entityId, items, buildStatusText, performanceText, false);
    }

    public PlayerNpcInspectorPacket(int entityId, List<ItemStack> items, String buildStatusText, String performanceText, boolean traceEnabled) {
        this(entityId, items, buildStatusText, performanceText, "", traceEnabled);
    }

    public PlayerNpcInspectorPacket(
            int entityId,
            List<ItemStack> items,
            String buildStatusText,
            String performanceText,
            String requirementsText,
            boolean traceEnabled
    ) {
        this(entityId, items, buildStatusText, performanceText, "", requirementsText, traceEnabled);
    }

    public PlayerNpcInspectorPacket(
            int entityId,
            List<ItemStack> items,
            String buildStatusText,
            String performanceText,
            String dailyJobText,
            String requirementsText,
            boolean traceEnabled
    ) {
        this.entityId = entityId;
        this.items = List.copyOf(items);
        this.buildStatusText = buildStatusText == null ? "" : buildStatusText;
        this.performanceText = performanceText == null ? "" : performanceText;
        this.dailyJobText = dailyJobText == null ? "" : dailyJobText;
        this.requirementsText = requirementsText == null ? "" : requirementsText;
        this.traceEnabled = traceEnabled;
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

    public String buildStatusText() {
        return buildStatusText;
    }

    public String performanceText() {
        return performanceText;
    }

    public String dailyJobText() {
        return dailyJobText;
    }

    public String requirementsText() {
        return requirementsText;
    }

    public boolean traceEnabled() {
        return traceEnabled;
    }

    public static void encode(PlayerNpcInspectorPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.entityId);
        buffer.writeVarInt(packet.items.size());
        for (ItemStack stack : packet.items) {
            buffer.writeItem(stack);
        }
        buffer.writeUtf(packet.buildStatusText);
        buffer.writeUtf(packet.performanceText);
        buffer.writeUtf(packet.dailyJobText);
        buffer.writeUtf(packet.requirementsText);
        buffer.writeBoolean(packet.traceEnabled);
    }

    public static PlayerNpcInspectorPacket decode(FriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        int size = buffer.readVarInt();
        List<ItemStack> items = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            items.add(buffer.readItem());
        }
        String buildStatusText = buffer.readUtf();
        String performanceText = buffer.readUtf();
        String dailyJobText = buffer.readUtf();
        String requirementsText = buffer.readUtf();
        boolean traceEnabled = buffer.readBoolean();
        return new PlayerNpcInspectorPacket(entityId, items, buildStatusText, performanceText, dailyJobText, requirementsText, traceEnabled);
    }

    public static void handle(PlayerNpcInspectorPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> SmartNpcInspectorOverlay.handlePacket(packet)
        ));
        context.setPacketHandled(true);
    }
}
