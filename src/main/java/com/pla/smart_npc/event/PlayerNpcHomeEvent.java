package com.pla.smart_npc.event;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.ChatUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

/** Fabric port: wired from {@code SmartNpcEvents} via {@code PlayerBlockBreakEvents.BEFORE}. */
public final class PlayerNpcHomeEvent {
    private static final double SLEEPING_BED_REACTION_RADIUS = 24.0D;

    private PlayerNpcHomeEvent() {
    }

    /**
     * @return {@code true} to let the block break proceed (the Forge edition never
     *         cancelled this event either).
     */
    public static boolean onBlockBreak(Level level, Player player, BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel serverLevel)
                || !(state.getBlock() instanceof BedBlock)
                || player == null) {
            return true;
        }

        BlockPos brokenFoot = normalizeBedFoot(serverLevel, pos, state);
        if (brokenFoot == null) {
            return true;
        }

        Player breaker = player;
        AABB searchArea = new AABB(brokenFoot).inflate(SLEEPING_BED_REACTION_RADIUS, 8.0D, SLEEPING_BED_REACTION_RADIUS);
        for (PlayerNpcEntity playerNpc : serverLevel.getEntitiesOfClass(
                PlayerNpcEntity.class,
                searchArea,
                playerNpc -> playerNpc.isAlive() && playerNpc.isSleeping())) {
            if (!isSleepingInBed(serverLevel, playerNpc, brokenFoot)) {
                continue;
            }

            playerNpc.stopSleeping();
            playerNpc.setSleepCooldown(20 * 90 + playerNpc.getRandom().nextInt(20 * 120));
            playerNpc.wakeUpIdleWork();
            if (!playerNpc.isTeamAlliedWith(breaker)) {
                playerNpc.setTarget(breaker);
                playerNpc.setCurrentAiState("ai.player_npc.retaliating");
            }
            playerNpc.setCurrentAiDetail("bed broken by " + breaker.getDisplayName().getString());
            ChatUtil.brokenBedWhileSleeping(playerNpc, breaker);
        }
        return true;
    }

    private static boolean isSleepingInBed(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos bedFoot) {
        Optional<BlockPos> sleepingPos = playerNpc.getSleepingPos();
        if (sleepingPos.isEmpty()) {
            return false;
        }

        BlockState sleepingState = serverLevel.getBlockState(sleepingPos.get());
        BlockPos sleepingFoot = normalizeBedFoot(serverLevel, sleepingPos.get(), sleepingState);
        return bedFoot.equals(sleepingFoot);
    }

    private static BlockPos normalizeBedFoot(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof BedBlock)
                || !state.hasProperty(BedBlock.PART)
                || !state.hasProperty(BedBlock.FACING)) {
            return null;
        }
        if (state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return pos.immutable();
        }

        Direction facing = state.getValue(BedBlock.FACING);
        BlockPos foot = pos.relative(facing.getOpposite());
        BlockState footState = serverLevel.getBlockState(foot);
        if (footState.getBlock() != state.getBlock()
                || !footState.hasProperty(BedBlock.PART)
                || footState.getValue(BedBlock.PART) != BedPart.FOOT) {
            return null;
        }
        return foot.immutable();
    }
}
