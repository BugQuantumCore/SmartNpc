package com.pla.smart_npc.event;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Server-side reactions to another living entity disturbing an NPC's tracked
 * chest. Fabric port: wired from {@code SmartNpcEvents} via
 * {@code UseBlockCallback} and {@code PlayerBlockBreakEvents.BEFORE}.
 */
public final class PlayerNpcChestProtectEvent {
    private PlayerNpcChestProtectEvent() {
    }

    public static InteractionResult onRightClickBlock(Player player, Level level, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide()
                || !(level instanceof ServerLevel serverLevel)
                || !serverLevel.getBlockState(hitResult.getBlockPos()).is(Blocks.CHEST)) {
            return InteractionResult.PASS;
        }
        reportOffense(serverLevel, hitResult.getBlockPos(), player, "opened");
        return InteractionResult.PASS;
    }

    public static boolean onBlockBreak(Level level, Player player, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel)
                || !state.is(Blocks.CHEST)) {
            return true;
        }
        reportOffense(serverLevel, pos, player, "broke");
        return true;
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
                    || owner.isTeamFollower()
                    || owner.isTeamAlliedWith(offender)
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
