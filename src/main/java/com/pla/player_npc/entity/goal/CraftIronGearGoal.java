package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;

public class CraftIronGearGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 10;
    private static final int CRAFTING_TABLE_SCAN_RADIUS = 5;

    private final PlayerNpcEntity playerNpc;

    public CraftIronGearGoal(PlayerNpcEntity playerNpc) {
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
                || this.playerNpc.getIronGearCooldown() > 0
                || !this.hasNearbyCraftingTable(serverLevel)) {
            return false;
        }

        return this.canCraftAnyIronGear();
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState("ai.player_npc.crafting_iron_gear");
        boolean crafted = this.tryCraftIronGear();
        if (crafted) {
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
            serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.35F, 1.4F);
        }
        this.playerNpc.setIronGearCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 12));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean canCraftAnyIronGear() {
        return this.canCraftTool(Items.IRON_PICKAXE, 3, 2)
                || this.canCraftTool(Items.IRON_AXE, 3, 2)
                || this.canCraftTool(Items.IRON_SWORD, 2, 1)
                || this.canCraftTool(Items.IRON_SHOVEL, 1, 2)
                || this.canImproveArmor(EquipmentSlot.HEAD, Items.IRON_HELMET, 5)
                || this.canImproveArmor(EquipmentSlot.CHEST, Items.IRON_CHESTPLATE, 8)
                || this.canImproveArmor(EquipmentSlot.LEGS, Items.IRON_LEGGINGS, 7)
                || this.canImproveArmor(EquipmentSlot.FEET, Items.IRON_BOOTS, 4);
    }

    private boolean tryCraftIronGear() {
        return this.tryCraftTool(Items.IRON_PICKAXE, 3, 2)
                || this.tryCraftTool(Items.IRON_AXE, 3, 2)
                || this.tryCraftTool(Items.IRON_SWORD, 2, 1)
                || this.tryCraftTool(Items.IRON_SHOVEL, 1, 2)
                || this.tryCraftArmor(EquipmentSlot.CHEST, Items.IRON_CHESTPLATE, 8)
                || this.tryCraftArmor(EquipmentSlot.LEGS, Items.IRON_LEGGINGS, 7)
                || this.tryCraftArmor(EquipmentSlot.HEAD, Items.IRON_HELMET, 5)
                || this.tryCraftArmor(EquipmentSlot.FEET, Items.IRON_BOOTS, 4);
    }

    private boolean canCraftTool(ItemLike result, int ironNeeded, int sticksNeeded) {
        return !this.hasItem(result)
                && this.countIronIngots() >= ironNeeded
                && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, sticksNeeded);
    }

    private boolean tryCraftTool(ItemLike result, int ironNeeded, int sticksNeeded) {
        if (!this.canCraftTool(result, ironNeeded, sticksNeeded)
                || !this.consumeIronAndSticks(ironNeeded, sticksNeeded)) {
            return false;
        }
        return InventoryUtils.addItem(this.playerNpc, new ItemStack(result));
    }

    private boolean canImproveArmor(EquipmentSlot slot, ItemLike result, int ironNeeded) {
        return this.countIronIngots() >= ironNeeded && this.isArmorUpgrade(slot, result.asItem());
    }

    private boolean tryCraftArmor(EquipmentSlot slot, ItemLike result, int ironNeeded) {
        if (!this.canImproveArmor(slot, result, ironNeeded)
                || !PlayerNpcCraftingUtil.consumeItem(this.playerNpc.getInventory(), stack -> stack.is(Items.IRON_INGOT), ironNeeded)) {
            return false;
        }

        ItemStack previous = this.playerNpc.getItemBySlot(slot);
        if (!previous.isEmpty() && !InventoryUtils.addItem(this.playerNpc, previous.copy())) {
            this.playerNpc.spawnAtLocation(previous.copy());
        }
        this.playerNpc.setItemSlot(slot, new ItemStack(result));
        return true;
    }

    private boolean consumeIronAndSticks(int ironNeeded, int sticksNeeded) {
        return PlayerNpcCraftingUtil.consumeItem(this.playerNpc.getInventory(), stack -> stack.is(Items.IRON_INGOT), ironNeeded)
                && PlayerNpcCraftingUtil.tryConsumePlanksAndSticks(this.playerNpc.getInventory(), 0, sticksNeeded);
    }

    private boolean hasItem(ItemLike itemLike) {
        return this.playerNpc.getMainHandItem().is(itemLike.asItem())
                || this.playerNpc.getOffhandItem().is(itemLike.asItem())
                || InventoryUtils.hasItem(this.playerNpc, itemLike);
    }

    private boolean isArmorUpgrade(EquipmentSlot slot, Item item) {
        if (!(item instanceof ArmorItem newArmor)) {
            return false;
        }

        ItemStack currentStack = this.playerNpc.getItemBySlot(slot);
        if (currentStack.isEmpty() || !(currentStack.getItem() instanceof ArmorItem currentArmor)) {
            return true;
        }

        return newArmor.getDefense() + newArmor.getToughness() > currentArmor.getDefense() + currentArmor.getToughness();
    }

    private int countIronIngots() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.IRON_INGOT));
    }

    private boolean hasNearbyCraftingTable(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-CRAFTING_TABLE_SCAN_RADIUS, -2, -CRAFTING_TABLE_SCAN_RADIUS),
                origin.offset(CRAFTING_TABLE_SCAN_RADIUS, 2, CRAFTING_TABLE_SCAN_RADIUS))) {
            if (serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                return true;
            }
        }
        return false;
    }
}
