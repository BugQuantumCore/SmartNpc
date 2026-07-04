package com.pla.player_npc.util;

import com.pla.player_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

public final class PlayerNpcHomeUtil {
    private static final String HOME_X = "PlayerNpcHomeX";
    private static final String HOME_Y = "PlayerNpcHomeY";
    private static final String HOME_Z = "PlayerNpcHomeZ";
    private static final String HOME_WIDTH = "PlayerNpcHomeWidth";
    private static final String HOME_DEPTH = "PlayerNpcHomeDepth";

    private PlayerNpcHomeUtil() {
    }

    public static Optional<HomeArea> getHome(PlayerNpcEntity playerNpc) {
        if (!playerNpc.getPersistentData().contains(HOME_WIDTH) || !playerNpc.getPersistentData().contains(HOME_DEPTH)) {
            return Optional.empty();
        }

        BlockPos origin = new BlockPos(
                playerNpc.getPersistentData().getInt(HOME_X),
                playerNpc.getPersistentData().getInt(HOME_Y),
                playerNpc.getPersistentData().getInt(HOME_Z)
        );
        int width = Math.max(3, playerNpc.getPersistentData().getInt(HOME_WIDTH));
        int depth = Math.max(3, playerNpc.getPersistentData().getInt(HOME_DEPTH));
        return Optional.of(new HomeArea(origin, width, depth));
    }

    public static HomeArea getOrCreateHome(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return getHome(playerNpc).orElseGet(() -> {
            HomeArea homeArea = findHomeArea(playerNpc, serverLevel).orElseGet(() -> {
                BlockPos origin = playerNpc.blockPosition().offset(-1, 0, -1);
                return new HomeArea(origin, 3, 3);
            });
            setHome(playerNpc, homeArea);
            return homeArea;
        });
    }

    public static void setHome(PlayerNpcEntity playerNpc, HomeArea homeArea) {
        playerNpc.getPersistentData().putInt(HOME_X, homeArea.origin().getX());
        playerNpc.getPersistentData().putInt(HOME_Y, homeArea.origin().getY());
        playerNpc.getPersistentData().putInt(HOME_Z, homeArea.origin().getZ());
        playerNpc.getPersistentData().putInt(HOME_WIDTH, homeArea.width());
        playerNpc.getPersistentData().putInt(HOME_DEPTH, homeArea.depth());
    }

    public static boolean isInside(HomeArea homeArea, BlockPos pos) {
        return pos.getX() >= homeArea.origin().getX()
                && pos.getX() < homeArea.origin().getX() + homeArea.width()
                && pos.getZ() >= homeArea.origin().getZ()
                && pos.getZ() < homeArea.origin().getZ() + homeArea.depth()
                && pos.getY() >= homeArea.origin().getY()
                && pos.getY() <= homeArea.origin().getY() + 3;
    }

    public static BlockPos interiorPos(HomeArea homeArea, int x, int z) {
        int safeX = Math.max(1, Math.min(homeArea.width() - 2, x));
        int safeZ = Math.max(1, Math.min(homeArea.depth() - 2, z));
        return homeArea.origin().offset(safeX, 1, safeZ);
    }

    private static Optional<HomeArea> findHomeArea(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        BlockPos center = playerNpc.blockPosition();
        int width = 3 + playerNpc.getRandom().nextInt(3);
        int depth = 3 + playerNpc.getRandom().nextInt(3);

        for (BlockPos originCandidate : BlockPos.betweenClosed(center.offset(-8, -1, -8), center.offset(8, 1, 8))) {
            HomeArea homeArea = new HomeArea(originCandidate.immutable(), width, depth);
            if (canUseArea(serverLevel, homeArea)) {
                return Optional.of(homeArea);
            }
        }
        return Optional.empty();
    }

    private static boolean canUseArea(ServerLevel serverLevel, HomeArea homeArea) {
        for (int x = 0; x < homeArea.width(); x++) {
            for (int z = 0; z < homeArea.depth(); z++) {
                BlockPos floor = homeArea.origin().offset(x, 0, z);
                if (!serverLevel.getBlockState(floor.below()).isSolidRender(serverLevel, floor.below())) {
                    return false;
                }
                for (int y = 0; y <= 3; y++) {
                    if (!serverLevel.getBlockState(homeArea.origin().offset(x, y, z)).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public record HomeArea(BlockPos origin, int width, int depth) {}
}
