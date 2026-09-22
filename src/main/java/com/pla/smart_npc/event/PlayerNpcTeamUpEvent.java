package com.pla.smart_npc.event;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Exact chat acceptance and friendly-fire cancellation for established TEAMUP relationships. */
@Mod.EventBusSubscriber(modid = SmartNpc.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerNpcTeamUpEvent {
    private PlayerNpcTeamUpEvent() {
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        PlayerNpcTeamUpManager.acceptPlayerResponse(player, event.getRawText());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        if (event.isCanceled()) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        if (PlayerNpcTeamUpManager.areTeamAllies(event.getEntity(), attacker)) {
            event.setCanceled(true);
            return;
        }
        if (attacker instanceof LivingEntity livingAttacker) {
            PlayerNpcTeamUpManager.alertAlliesOfAttack(event.getEntity(), livingAttacker);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PlayerNpcTeamUpManager.onPlayerLeaderDeath(player);
        }
    }
}
