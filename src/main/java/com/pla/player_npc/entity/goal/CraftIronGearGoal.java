package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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

        return this.canCraftAnyGear(serverLevel);
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
        boolean crafted = this.tryCraftGear(serverLevel);
        if (crafted) {
            this.playerNpc.triggerMainHandUseAnimation();
            serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.35F, 1.4F);
        }
        this.playerNpc.setIronGearCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 12));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean canCraftAnyGear(ServerLevel serverLevel) {
        return this.canCraftTool(serverLevel, Items.DIAMOND_PICKAXE, Items.DIAMOND, 3, 2)
                || this.canCraftTool(serverLevel, Items.DIAMOND_SWORD, Items.DIAMOND, 2, 1)
                || this.canCraftTool(serverLevel, Items.DIAMOND_AXE, Items.DIAMOND, 3, 2)
                || this.canCraftTool(serverLevel, Items.DIAMOND_SHOVEL, Items.DIAMOND, 1, 2)
                || this.canImproveArmor(serverLevel, EquipmentSlot.HEAD, Items.DIAMOND_HELMET, Items.DIAMOND, 5)
                || this.canImproveArmor(serverLevel, EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE, Items.DIAMOND, 8)
                || this.canImproveArmor(serverLevel, EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS, Items.DIAMOND, 7)
                || this.canImproveArmor(serverLevel, EquipmentSlot.FEET, Items.DIAMOND_BOOTS, Items.DIAMOND, 4)
                || this.canCraftTool(serverLevel, Items.IRON_PICKAXE, Items.IRON_INGOT, 3, 2)
                || this.canCraftTool(serverLevel, Items.IRON_AXE, Items.IRON_INGOT, 3, 2)
                || this.canCraftTool(serverLevel, Items.IRON_SWORD, Items.IRON_INGOT, 2, 1)
                || this.canCraftTool(serverLevel, Items.IRON_SHOVEL, Items.IRON_INGOT, 1, 2)
                || this.canImproveArmor(serverLevel, EquipmentSlot.HEAD, Items.IRON_HELMET, Items.IRON_INGOT, 5)
                || this.canImproveArmor(serverLevel, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE, Items.IRON_INGOT, 8)
                || this.canImproveArmor(serverLevel, EquipmentSlot.LEGS, Items.IRON_LEGGINGS, Items.IRON_INGOT, 7)
                || this.canImproveArmor(serverLevel, EquipmentSlot.FEET, Items.IRON_BOOTS, Items.IRON_INGOT, 4);
    }

    private boolean tryCraftGear(ServerLevel serverLevel) {
        return this.tryCraftTool(serverLevel, Items.DIAMOND_PICKAXE, Items.DIAMOND, 3, 2)
                || this.tryCraftTool(serverLevel, Items.DIAMOND_SWORD, Items.DIAMOND, 2, 1)
                || this.tryCraftTool(serverLevel, Items.DIAMOND_AXE, Items.DIAMOND, 3, 2)
                || this.tryCraftTool(serverLevel, Items.DIAMOND_SHOVEL, Items.DIAMOND, 1, 2)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE, Items.DIAMOND, 8)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS, Items.DIAMOND, 7)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.HEAD, Items.DIAMOND_HELMET, Items.DIAMOND, 5)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.FEET, Items.DIAMOND_BOOTS, Items.DIAMOND, 4)
                || this.tryCraftTool(serverLevel, Items.IRON_PICKAXE, Items.IRON_INGOT, 3, 2)
                || this.tryCraftTool(serverLevel, Items.IRON_AXE, Items.IRON_INGOT, 3, 2)
                || this.tryCraftTool(serverLevel, Items.IRON_SWORD, Items.IRON_INGOT, 2, 1)
                || this.tryCraftTool(serverLevel, Items.IRON_SHOVEL, Items.IRON_INGOT, 1, 2)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE, Items.IRON_INGOT, 8)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.LEGS, Items.IRON_LEGGINGS, Items.IRON_INGOT, 7)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.HEAD, Items.IRON_HELMET, Items.IRON_INGOT, 5)
                || this.tryCraftArmor(serverLevel, EquipmentSlot.FEET, Items.IRON_BOOTS, Items.IRON_INGOT, 4);
    }

    private boolean canCraftTool(ServerLevel serverLevel, ItemLike result, ItemLike material, int materialNeeded, int sticksNeeded) {
        return !this.hasItem(result)
                && this.countMaterial(material) >= materialNeeded
                && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, sticksNeeded, this.playerNpc.getRawLogReserveTarget())
                && (PlayerNpcCraftingUtil.countSticks(this.playerNpc.getInventory()) < sticksNeeded
                || PlayerNpcCraftingUtil.canCraft(serverLevel, this.playerNpc.getInventory(), result, true));
    }

    private boolean tryCraftTool(ServerLevel serverLevel, ItemLike result, ItemLike material, int materialNeeded, int sticksNeeded) {
        if (!this.canCraftTool(serverLevel, result, material, materialNeeded, sticksNeeded)
                || !this.ensureSticks(serverLevel, sticksNeeded)) {
            return false;
        }
        return PlayerNpcCraftingUtil.craftItem(serverLevel, this.playerNpc.getInventory(), result, true)
                .map(stack -> InventoryUtils.addItem(this.playerNpc, stack))
                .orElse(false);
    }

    private boolean canImproveArmor(ServerLevel serverLevel, EquipmentSlot slot, ItemLike result, ItemLike material, int materialNeeded) {
        return this.countMaterial(material) >= materialNeeded
                && this.isArmorUpgrade(slot, result.asItem())
                && PlayerNpcCraftingUtil.canCraft(serverLevel, this.playerNpc.getInventory(), result, true);
    }

    private boolean tryCraftArmor(ServerLevel serverLevel, EquipmentSlot slot, ItemLike result, ItemLike material, int materialNeeded) {
        if (!this.canImproveArmor(serverLevel, slot, result, material, materialNeeded)) {
            return false;
        }

        ItemStack crafted = PlayerNpcCraftingUtil.craftItem(serverLevel, this.playerNpc.getInventory(), result, true).orElse(ItemStack.EMPTY);
        if (crafted.isEmpty()) {
            return false;
        }

        ItemStack previous = this.playerNpc.getItemBySlot(slot);
        if (!previous.isEmpty() && !InventoryUtils.addItem(this.playerNpc, previous.copy())) {
            this.playerNpc.spawnAtLocation(previous.copy());
        }
        ItemStack equipped = crafted.copy();
        equipped.setCount(1);
        this.playerNpc.setItemSlot(slot, equipped);
        return true;
    }

    private boolean ensureSticks(ServerLevel serverLevel, int sticksNeeded) {
        while (PlayerNpcCraftingUtil.countSticks(this.playerNpc.getInventory()) < sticksNeeded) {
            if (PlayerNpcCraftingUtil.countPlanks(this.playerNpc.getInventory()) < 2
                    && PlayerNpcCraftingUtil.countLogs(this.playerNpc.getInventory()) > this.playerNpc.getRawLogReserveTarget()
                    && PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
                continue;
            }
            if (!PlayerNpcCraftingUtil.tryCraftSticks(serverLevel, this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
                return false;
            }
        }
        return true;
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

    private int countMaterial(ItemLike material) {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(material.asItem()));
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
