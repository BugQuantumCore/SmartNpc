package com.pla.smart_npc.util;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class PlayerNpcBlockBreakUtil {
    private PlayerNpcBlockBreakUtil() {
    }

    public static boolean destroyBlock(ServerLevel serverLevel, BlockPos pos, BlockState state, PlayerNpcEntity playerNpc) {
        ItemStack heldStack = playerNpc.getMainHandItem();
        if (!shouldDropResources(state, heldStack)) {
            return serverLevel.destroyBlock(pos, false, playerNpc);
        }

        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, blockEntity, playerNpc, heldStack);
        if (!serverLevel.destroyBlock(pos, false, playerNpc)) {
            return false;
        }

        awardExperienceDrop(serverLevel, pos, state, playerNpc, heldStack);

        boolean insertedDrop = false;
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            if (InventoryUtils.addItem(playerNpc, drop)) {
                insertedDrop = true;
            } else {
                Block.popResource(serverLevel, pos, drop);
            }
        }
        if (insertedDrop) {
            playerNpc.playInventoryPickupSound();
        }
        return true;
    }

    public static boolean shouldDropResources(BlockState state, ItemStack heldStack) {
        return !state.requiresCorrectToolForDrops() || heldStack.isCorrectToolForDrops(state);
    }

    private static void awardExperienceDrop(
            ServerLevel serverLevel,
            BlockPos pos,
            BlockState state,
            PlayerNpcEntity playerNpc,
            ItemStack heldStack
    ) {
        int fortune = EnchantmentHelper.getItemEnchantmentLevel(Enchantments.BLOCK_FORTUNE, heldStack);
        int silkTouch = EnchantmentHelper.hasSilkTouch(heldStack) ? 1 : 0;
        int xp = state.getExpDrop(serverLevel, serverLevel.getRandom(), pos, fortune, silkTouch);
        if (xp <= 0) {
            return;
        }

        playerNpc.awardStoredExperience(xp);
        playerNpc.playExperiencePickupSound();
    }
}
