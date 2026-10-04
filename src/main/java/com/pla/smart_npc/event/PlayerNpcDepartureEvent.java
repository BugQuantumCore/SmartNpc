package com.pla.smart_npc.event;

import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.ChatUtil;
import com.pla.smart_npc.util.ExternalChunkActivity;
import com.pla.smart_npc.util.PlayerNpcForceTickManager;
import com.pla.smart_npc.util.RemoteNpcDeparture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;

/**
 * Fabric port: the living-tick hook is dispatched from
 * {@code LivingEntityTickMixin} via {@code SmartNpcEvents#onLivingTick}.
 */
public final class PlayerNpcDepartureEvent {
    private PlayerNpcDepartureEvent() {}

    public static void onLivingTick(LivingEntity entity) {
        if (!(entity instanceof PlayerNpcEntity npc)
                || !(npc.level() instanceof ServerLevel) || !npc.isAlive() || npc.isRemoved()
                || Math.floorMod(npc.tickCount, 20) != Math.floorMod(npc.getUUID().hashCode(), 20)) return;
        if (RemoteNpcDeparture.tick(npc,
                PlayerNpcForceTickManager.isEnabled() && PlayerNpcForceTickManager.hasForceTicket(npc.getUUID()),
                SmartNpcConfig.REMOTE_NPC_DEPARTURE_ENABLED.get(),
                SmartNpcConfig.REMOTE_NPC_DEPARTURE_MIN_MINUTES.get(),
                SmartNpcConfig.REMOTE_NPC_DEPARTURE_MAX_MINUTES.get())) {
            ChatUtil.leaveGame(npc);
            // Existing leave handlers release tickets, tab entry and the persisted natural spawn cap.
            npc.discard();
        }
    }

    public static void stopping(MinecraftServer server) { ExternalChunkActivity.clear(); }
}
