package com.pla.smart_npc.event;

import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * Exact chat acceptance and friendly-fire cancellation for established TEAMUP
 * relationships. Wired in {@code SmartNpcEvents} through
 * {@code ServerMessageEvents.CHAT_MESSAGE}, {@code ServerLivingEntityEvents.ALLOW_DAMAGE}
 * and {@code ServerLivingEntityEvents.AFTER_DEATH}.
 */
public final class PlayerNpcTeamUpEvent {
    private PlayerNpcTeamUpEvent() {
    }

    public static void onServerChat(ServerPlayer player, String rawText) {
        PlayerNpcTeamUpManager.acceptPlayerResponse(player, rawText);
    }

    /**
     * Fabric port of the Forge LivingAttackEvent (LOWEST, cancellable).
     *
     * @return {@code true} to allow the damage, {@code false} to cancel it
     *         (Forge: {@code event.setCanceled(true)}).
     */
    public static boolean onLivingAttack(LivingEntity entity, DamageSource source, float amount) {
        net.minecraft.world.entity.Entity attacker = source.getEntity();
        if (PlayerNpcTeamUpManager.areTeamAllies(entity, attacker)) {
            return false;
        }
        if (attacker instanceof LivingEntity livingAttacker) {
            PlayerNpcTeamUpManager.alertAlliesOfAttack(entity, livingAttacker);
        }
        return true;
    }

    public static void onLivingDeath(LivingEntity entity, DamageSource source) {
        if (entity instanceof ServerPlayer player) {
            PlayerNpcTeamUpManager.onPlayerLeaderDeath(player);
        }
    }
}
