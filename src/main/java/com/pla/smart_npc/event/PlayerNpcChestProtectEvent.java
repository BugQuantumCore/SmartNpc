package com.pla.smart_npc.event;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Server-side reactions to another living entity disturbing an NPC's tracked chest. */
@Mod.EventBusSubscriber(modid = SmartNpc.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerNpcChestProtectEvent {
    private PlayerNpcChestProtectEvent() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()
                || !(event.getLevel() instanceof ServerLevel serverLevel)
                || !serverLevel.getBlockState(event.getPos()).is(Blocks.CHEST)) {
            return;
        }
        reportOffense(serverLevel, event.getPos(), event.getEntity(), "opened");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()
                || !(event.getLevel() instanceof ServerLevel serverLevel)
                || !event.getState().is(Blocks.CHEST)) {
            return;
        }
        reportOffense(serverLevel, event.getPos(), event.getPlayer(), "broke");
    }

    public static void reportOffense(ServerLevel serverLevel, BlockPos chestPos, LivingEntity offender, String action) {
        if (serverLevel == null || chestPos == null || offender == null || !offender.isAlive()) {
            return;
        }

        // Never load chunks for protection, but do not impose an arbitrary distance limit on an
        // owner that is already loaded and ticking in this level.
        for (var entity : serverLevel.getAllEntities()) {
            if (!(entity instanceof PlayerNpcEntity owner)
                    || !owner.isAlive()
                    || owner == offender
                    || !owner.hasInterest(PlayerNpcInterest.CHEST_PROTECT)
                    || !owner.isOwnedChest(chestPos)) {
                continue;
            }
            owner.setChestProtectionTarget(offender);
            owner.wakeUpIdleWork();
            owner.setCurrentAiState("ai.player_npc.protecting_chest");
            owner.setCurrentAiDetail("owned chest " + action + " by " + offender.getDisplayName().getString());
        }
    }
}
