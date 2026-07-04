package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcCraftingUtil;
import com.pla.player_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

import java.util.EnumSet;
import java.util.function.Predicate;

public class CookFoodGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 20;

    private final PlayerNpcEntity playerNpc;
    private PlayerNpcHomeUtil.HomeArea homeArea;

    public CookFoodGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getCookFoodCooldown() > 0) {
            return false;
        }

        this.homeArea = PlayerNpcHomeUtil.getOrCreateHome(this.playerNpc, serverLevel);
        BlockPos furnace = this.findFurnace(serverLevel);
        if (furnace == null) {
            return (InventoryUtils.hasItem(this.playerNpc, Items.FURNACE)
                    || PlayerNpcCraftingUtil.canCraftFurnace(this.playerNpc.getInventory()))
                    && this.findFurnacePlacement(serverLevel) != null;
        }

        return (this.hasCookableFood() || this.hasSmeltableMaterial())
                && this.hasFuel()
                || serverLevel.getBlockEntity(furnace) instanceof FurnaceBlockEntity furnaceBlockEntity
                && !furnaceBlockEntity.getItem(2).isEmpty();
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.homeArea == null) {
            return;
        }

        this.playerNpc.setCurrentAiState("ai.player_npc.cooking");
        boolean acted = this.placeFurnace(serverLevel)
                || this.takeCookedOutput(serverLevel)
                || this.fillFurnace(serverLevel);
        if (acted) {
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        }

        this.playerNpc.setCookFoodCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 20));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.homeArea = null;
    }

    private boolean placeFurnace(ServerLevel serverLevel) {
        if (this.findFurnace(serverLevel) != null) {
            return false;
        }

        ItemStack furnace = this.playerNpc.consumeInventoryItem(Items.FURNACE, 1).orElse(ItemStack.EMPTY);
        if (furnace.isEmpty()) {
            if (!PlayerNpcCraftingUtil.tryCraftFurnace(this.playerNpc.getInventory())) {
                return false;
            }
            furnace = this.playerNpc.consumeInventoryItem(Items.FURNACE, 1).orElse(ItemStack.EMPTY);
        }
        if (furnace.isEmpty()) {
            return false;
        }

        BlockPos pos = this.findFurnacePlacement(serverLevel);
        if (pos == null) {
            this.returnStack(furnace);
            return false;
        }

        serverLevel.setBlockAndUpdate(pos, Blocks.FURNACE.defaultBlockState());
        this.playerNpc.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 40.0F, 40.0F);
        serverLevel.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    private boolean takeCookedOutput(ServerLevel serverLevel) {
        BlockPos furnacePos = this.findFurnace(serverLevel);
        if (furnacePos == null || !(serverLevel.getBlockEntity(furnacePos) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }

        ItemStack output = furnace.getItem(2);
        if (output.isEmpty()) {
            return false;
        }

        ItemStack moved = output.copy();
        furnace.setItem(2, ItemStack.EMPTY);
        furnace.setChanged();
        if (!InventoryUtils.addItem(this.playerNpc, moved)) {
            this.playerNpc.spawnAtLocation(moved);
        }
        this.playerNpc.getLookControl().setLookAt(furnacePos.getX() + 0.5D, furnacePos.getY() + 0.5D, furnacePos.getZ() + 0.5D, 40.0F, 40.0F);
        serverLevel.playSound(null, furnacePos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
        return true;
    }

    private boolean fillFurnace(ServerLevel serverLevel) {
        BlockPos furnacePos = this.findFurnace(serverLevel);
        if (furnacePos == null || !(serverLevel.getBlockEntity(furnacePos) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }

        boolean movedAny = false;
        if (furnace.getItem(0).isEmpty()) {
            ItemStack input = this.playerNpc.consumeInventoryItem(this::isCookableFood, 1)
                    .or(() -> this.playerNpc.consumeInventoryItem(this::isSmeltableMaterial, 1))
                    .orElse(ItemStack.EMPTY);
            if (!input.isEmpty()) {
                furnace.setItem(0, input);
                movedAny = true;
            }
        }

        if (furnace.getItem(1).isEmpty()) {
            ItemStack fuel = this.playerNpc.consumeInventoryItem(this::isFuel, 1).orElse(ItemStack.EMPTY);
            if (!fuel.isEmpty()) {
                furnace.setItem(1, fuel);
                movedAny = true;
            }
        }

        if (movedAny) {
            furnace.setChanged();
            this.playerNpc.getLookControl().setLookAt(furnacePos.getX() + 0.5D, furnacePos.getY() + 0.5D, furnacePos.getZ() + 0.5D, 40.0F, 40.0F);
            serverLevel.playSound(null, furnacePos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.5F, 1.0F);
        }
        return movedAny;
    }

    private BlockPos findFurnace(ServerLevel serverLevel) {
        for (BlockPos pos : BlockPos.betweenClosed(
                this.homeArea.origin(),
                this.homeArea.origin().offset(this.homeArea.width() - 1, 3, this.homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).is(Blocks.FURNACE)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findFurnacePlacement(ServerLevel serverLevel) {
        BlockPos preferred = PlayerNpcHomeUtil.interiorPos(this.homeArea, this.homeArea.width() - 2, this.homeArea.depth() - 2);
        if (this.canPlaceAt(serverLevel, preferred)) {
            return preferred;
        }

        for (int x = 1; x < this.homeArea.width() - 1; x++) {
            for (int z = 1; z < this.homeArea.depth() - 1; z++) {
                BlockPos pos = PlayerNpcHomeUtil.interiorPos(this.homeArea, x, z);
                if (this.canPlaceAt(serverLevel, pos)) {
                    return pos;
                }
            }
        }
        return null;
    }

    private boolean canPlaceAt(ServerLevel serverLevel, BlockPos pos) {
        return PlayerNpcHomeUtil.isInside(this.homeArea, pos)
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private boolean hasCookableFood() {
        return InventoryUtils.hasItem(this.playerNpc, this::isCookableFood);
    }

    private boolean hasSmeltableMaterial() {
        return InventoryUtils.hasItem(this.playerNpc, this::isSmeltableMaterial);
    }

    private boolean hasFuel() {
        return InventoryUtils.hasItem(this.playerNpc, this::isFuel);
    }

    private boolean isCookableFood(ItemStack stack) {
        return stack.is(Items.BEEF)
                || stack.is(Items.PORKCHOP)
                || stack.is(Items.CHICKEN)
                || stack.is(Items.MUTTON)
                || stack.is(Items.COD)
                || stack.is(Items.SALMON)
                || stack.is(Items.POTATO);
    }

    private boolean isSmeltableMaterial(ItemStack stack) {
        return stack.is(Items.RAW_IRON)
                || stack.is(Items.RAW_COPPER)
                || stack.is(Items.RAW_GOLD)
                || stack.is(Items.IRON_ORE)
                || stack.is(Items.DEEPSLATE_IRON_ORE)
                || stack.is(Items.COPPER_ORE)
                || stack.is(Items.DEEPSLATE_COPPER_ORE)
                || stack.is(Items.GOLD_ORE)
                || stack.is(Items.DEEPSLATE_GOLD_ORE);
    }

    private boolean isFuel(ItemStack stack) {
        return !stack.isEmpty() && AbstractFurnaceBlockEntity.isFuel(stack);
    }

    private void returnStack(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }
}
