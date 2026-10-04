package com.pla.smart_npc.util.compat;

import net.minecraft.server.MinecraftServer;

/**
 * Fabric port of Forge's {@code com.pla.smart_npc.util.compat.FabricServerHolder.getCurrentServer()}.
 * Populated by {@code SmartNpcEvents} from the server lifecycle events.
 */
public final class FabricServerHolder {
    private static volatile MinecraftServer currentServer;

    private FabricServerHolder() {
    }

    public static void onServerStarting(MinecraftServer server) {
        currentServer = server;
    }

    public static void onServerStopped(MinecraftServer server) {
        if (currentServer == server) {
            currentServer = null;
        }
    }

    public static MinecraftServer getCurrentServer() {
        return currentServer;
    }
}
