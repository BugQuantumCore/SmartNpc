package com.pla.smart_npc.util;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class PlayerNpcBlockBreakUtil {
    private PlayerNpcBlockBreakUtil() {
    }

    public static boolean destroyBlock(ServerLevel serverLevel, BlockPos pos, BlockState state, PlayerNpcEntity playerNpc) {
        if (!shouldDropResources(state, playerNpc.getMainHandItem())) {
            return serverLevel.destroyBlock(pos, false, playerNpc);
        }

        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, blockEntity, playerNpc, playerNpc.getMainHandItem());
        if (!serverLevel.destroyBlock(pos, false, playerNpc)) {
            return false;
        }

        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            if (!InventoryUtils.addItem(playerNpc, drop)) {
                Block.popResource(serverLevel, pos, drop);
            }
        }
        return true;
    }

    public static boolean shouldDropResources(BlockState state, ItemStack heldStack) {
        return !state.requiresCorrectToolForDrops() || heldStack.isCorrectToolForDrops(state);
    }
}
