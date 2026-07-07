package com.pla.smart_npc.util;

import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.task.DelayedTask;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public class ChatUtil {
    private static final int CALL_FOR_HELP_MESSAGES = 5;
    private static final int WARN_DEATH_MESSAGES = 5;
    private static final int MISSING_HOME_CHEST_MESSAGES = 4;
    private static final int KILLER_TAUNT_MESSAGES = 20;
    private static final int DEATH_REACTION_MESSAGES = 20;

    public static void joinGame(Entity entity) {
        joinGame(entity, entity.getDisplayName());
    }

    public static void joinGame(Entity entity, String name) {
        joinGame(entity, Component.literal(name));
    }

    public static void leaveGame(Entity entity) {
        broadcastSystemMessage(entity, Component.translatable("chat.player_npc.left", entity.getDisplayName()).withStyle(ChatFormatting.YELLOW));
    }

    public static void callForHelp(PlayerNpcEntity speaker, LivingEntity threat) {
        broadcastNpcChat(speaker, randomKey(speaker, "chat.player_npc.call_help", CALL_FOR_HELP_MESSAGES), threat.getDisplayName());
    }

    public static void warnDeath(PlayerNpcEntity victim, Entity threat) {
        if (!isPlayerLikeThreat(threat)) {
            return;
        }

        broadcastNpcChat(victim, randomKey(victim, "chat.player_npc.warn_death", WARN_DEATH_MESSAGES), threat.getDisplayName());
    }

    public static void missingHomeChest(PlayerNpcEntity speaker) {
        broadcastNpcChat(speaker, randomKey(speaker, "chat.player_npc.missing_home_chest", MISSING_HOME_CHEST_MESSAGES));
    }

    public static void broadcastDeathSummary(PlayerNpcEntity victim, Entity killer) {
        broadcastSystemMessage(victim, Component.translatable("chat.player_npc.death_summary", victim.getDisplayName(), killer.getDisplayName()));
    }

    public static void scheduleKillerTaunt(PlayerNpcEntity killer, Entity victim) {
        if (!canChat(killer)) {
            return;
        }

        new DelayedTask(Mth.nextInt(RandomSource.create(), 70, 100)) {
            @Override
            public void run() {
                broadcastNpcChat(killer, randomKey(killer, "chat.player_npc.killer_taunt", KILLER_TAUNT_MESSAGES), victim.getDisplayName());
            }
        };
    }

    public static void scheduleDeathReaction(PlayerNpcEntity victim, Entity killer) {
        if (!isPlayerLikeThreat(killer) || !canChat(victim)) {
            return;
        }

        new DelayedTask(Mth.nextInt(RandomSource.create(), 40, 80)) {
            @Override
            public void run() {
                broadcastNpcChat(victim, randomKey(victim, "chat.player_npc.death_reaction", DEATH_REACTION_MESSAGES), killer.getDisplayName());
                scheduleLeaveGame(victim);
            }
        };
    }

    public static boolean isPlayerLikeThreat(Entity threat) {
        return threat instanceof Player || threat instanceof PlayerNpcEntity;
    }

    public static boolean shouldPlayerNpcTauntKill(PlayerNpcEntity killer, Entity victim) {
        return killer != null && isPlayerLikeThreat(victim);
    }

    public static boolean shouldReportPlayerNpcDeath(PlayerNpcEntity victim, Entity killer) {
        return victim != null && isPlayerLikeThreat(killer);
    }

    private static void joinGame(Entity entity, Component name) {
        broadcastSystemMessage(entity, Component.translatable("chat.player_npc.joined", name).withStyle(ChatFormatting.YELLOW));
    }

    private static void scheduleLeaveGame(Entity entity) {
        new DelayedTask(Mth.nextInt(RandomSource.create(), 25, 100)) {
            @Override
            public void run() {
                leaveGame(entity);
            }
        };
    }

    private static void broadcastNpcChat(Entity speaker, String translationKey, Object... args) {
        if (!canChat(speaker)) {
            return;
        }

        Component message = Component.literal("<")
                .append(speaker.getDisplayName())
                .append("> ")
                .append(Component.translatable(translationKey, args));
        broadcastSystemMessage(speaker, message);
    }

    private static void broadcastSystemMessage(Entity entity, Component message) {
        MinecraftServer server = entity.level().getServer();
        if (SmartNpcConfig.TURN_ON_NPC_CHAT.get() && server != null) {
            server.getPlayerList().broadcastSystemMessage(message, false);
        }
    }

    private static boolean canChat(Entity entity) {
        return SmartNpcConfig.TURN_ON_NPC_CHAT.get() && entity.level().getServer() != null;
    }

    private static String randomKey(Entity entity, String prefix, int messageCount) {
        return prefix + "." + (entity.level().random.nextInt(messageCount) + 1);
    }
}
