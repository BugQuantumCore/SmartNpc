package com.pla.player_npc.network;

import com.pla.player_npc.PlayerNpc;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class PlayerNpcNetwork {
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(PlayerNpc.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private static int packetId;

    public static void register() {
        CHANNEL.registerMessage(
                packetId++,
                PlayerNpcInspectorPacket.class,
                PlayerNpcInspectorPacket::encode,
                PlayerNpcInspectorPacket::decode,
                PlayerNpcInspectorPacket::handle
        );
        CHANNEL.registerMessage(
                packetId++,
                PlayerNpcInspectorRequestPacket.class,
                PlayerNpcInspectorRequestPacket::encode,
                PlayerNpcInspectorRequestPacket::decode,
                PlayerNpcInspectorRequestPacket::handle
        );
    }
}
