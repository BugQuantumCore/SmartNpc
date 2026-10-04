package com.pla.smart_npc.network;

import com.pla.smart_npc.SmartNpc;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric port of the Forge {@code SimpleChannel} networking.
 *
 * <p>Forge multiplexed all packets over one {@code smart_npc:main} channel with
 * numeric discriminators. Fabric play networking uses one resource location per
 * packet type, so each message class now owns its dedicated channel id.</p>
 *
 * <p>Send helpers keep the original call shape: server code calls
 * {@code SmartNpcNetwork.sendToPlayer(player, packet)} instead of
 * {@code CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet)}.</p>
 */
public final class SmartNpcNetwork {
    private static final ResourceLocation INSPECTOR = id("inspector");
    private static final ResourceLocation INSPECTOR_REQUEST = id("inspector_request");
    private static final ResourceLocation INSPECTATOR_MODE = id("inspectator_mode");
    private static final ResourceLocation INSPECTATOR_CYCLE = id("inspectator_cycle");
    private static final ResourceLocation INSPECTATOR_CYCLE_RESULT = id("inspectator_cycle_result");
    private static final ResourceLocation GOAL_TRACE = id("goal_trace");

    private SmartNpcNetwork() {
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(SmartNpc.MODID, path);
    }

    // ------------------------------------------------------------------ registration

    /** Registers all server-bound packet receivers. Called from the common entrypoint. */
    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(INSPECTATOR_MODE, (server, player, handler, buf, responseSender) -> {
            PlayerNpcInspectatorModePacket packet = PlayerNpcInspectatorModePacket.decode(buf);
            server.execute(() -> PlayerNpcInspectatorModePacket.handle(packet, server, player));
        });

        ServerPlayNetworking.registerGlobalReceiver(INSPECTATOR_CYCLE, (server, player, handler, buf, responseSender) -> {
            PlayerNpcInspectatorCyclePacket packet = PlayerNpcInspectatorCyclePacket.decode(buf);
            server.execute(() -> PlayerNpcInspectatorCyclePacket.handle(packet, server, player));
        });

        ServerPlayNetworking.registerGlobalReceiver(INSPECTOR_REQUEST, (server, player, handler, buf, responseSender) -> {
            PlayerNpcInspectorRequestPacket packet = PlayerNpcInspectorRequestPacket.decode(buf);
            server.execute(() -> PlayerNpcInspectorRequestPacket.handle(packet, server, player));
        });

        ServerPlayNetworking.registerGlobalReceiver(GOAL_TRACE, (server, player, handler, buf, responseSender) -> {
            PlayerNpcGoalTracePacket packet = PlayerNpcGoalTracePacket.decode(buf);
            server.execute(() -> PlayerNpcGoalTracePacket.handle(packet, server, player));
        });
    }

    /** Registers all client-bound packet receivers. Called from the client entrypoint. */
    @Environment(EnvType.CLIENT)
    public static void registerClientReceivers() {
        ClientPlayNetworking.registerGlobalReceiver(INSPECTOR, (client, handler, buf, responseSender) -> {
            PlayerNpcInspectorPacket packet = PlayerNpcInspectorPacket.decode(buf);
            client.execute(() -> com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay.handlePacket(packet));
        });

        ClientPlayNetworking.registerGlobalReceiver(INSPECTATOR_CYCLE_RESULT, (client, handler, buf, responseSender) -> {
            PlayerNpcInspectatorCycleResultPacket packet = PlayerNpcInspectatorCycleResultPacket.decode(buf);
            client.execute(() -> com.pla.smart_npc.client.gui.SmartNpcInspectorOverlay.handleInspectatorCycleResult(packet));
        });
    }

    // ------------------------------------------------------------------ server -> client senders

    public static void sendToPlayer(ServerPlayer player, PlayerNpcInspectorPacket packet) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        PlayerNpcInspectorPacket.encode(packet, buf);
        ServerPlayNetworking.send(player, INSPECTOR, buf);
    }

    public static void sendToPlayer(ServerPlayer player, PlayerNpcInspectatorCycleResultPacket packet) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        PlayerNpcInspectatorCycleResultPacket.encode(packet, buf);
        ServerPlayNetworking.send(player, INSPECTATOR_CYCLE_RESULT, buf);
    }

    // ------------------------------------------------------------------ client -> server senders

    public static void sendToServer(PlayerNpcInspectatorModePacket packet) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        PlayerNpcInspectatorModePacket.encode(packet, buf);
        ClientPlayNetworking.send(INSPECTATOR_MODE, buf);
    }

    public static void sendToServer(PlayerNpcInspectatorCyclePacket packet) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        PlayerNpcInspectatorCyclePacket.encode(packet, buf);
        ClientPlayNetworking.send(INSPECTATOR_CYCLE, buf);
    }

    public static void sendToServer(PlayerNpcInspectorRequestPacket packet) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        PlayerNpcInspectorRequestPacket.encode(packet, buf);
        ClientPlayNetworking.send(INSPECTOR_REQUEST, buf);
    }

    public static void sendToServer(PlayerNpcGoalTracePacket packet) {
        FriendlyByteBuf buf = PacketByteBufs.create();
        PlayerNpcGoalTracePacket.encode(packet, buf);
        ClientPlayNetworking.send(GOAL_TRACE, buf);
    }
}
