package com.pla.smart_npc.event;

import com.pla.smart_npc.clazz.Difficulty;
import com.pla.smart_npc.util.ProgressionUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;

/**
 * Fabric port: wired from {@code SmartNpcEvents} (server lifecycle, ticks,
 * join events, damage/death) and the living-tick mixin dispatch.
 */
public final class ProgressionEvent {
    private ProgressionEvent() {
    }

    public static void onServerStarted(MinecraftServer server) {
        ProgressionUtil.reconcileHistoricalProgression(server);
    }

    public static void onServerTick(MinecraftServer server) {
        if (server.getTickCount() % 20 == 0) {
            ProgressionUtil.reconcileDragonFightProgression(server);
        }
    }

    public static void onPlayerLoggedIn(ServerPlayer player) {
        ProgressionUtil.reconcileHistoricalProgression(player);
    }

    public static void onPlayerChangedDimension(ServerPlayer player) {
        ProgressionUtil.increaseDifficulty(player.server, Difficulty.MEDIUM);
    }

    public static void onLivingDeath(LivingEntity entity, DamageSource source) {
        if (entity instanceof EnderDragon && entity.level() instanceof ServerLevel level) {
            ProgressionUtil.increaseDifficulty(level.getServer(), Difficulty.HARD);
        }
    }

    public static void onLivingTick(LivingEntity entity) {
        if (entity instanceof EnderDragon dragon
                && dragon.dragonDeathTime > 0
                && dragon.level() instanceof ServerLevel level) {
            ProgressionUtil.increaseDifficulty(level.getServer(), Difficulty.HARD);
        }
    }
}
