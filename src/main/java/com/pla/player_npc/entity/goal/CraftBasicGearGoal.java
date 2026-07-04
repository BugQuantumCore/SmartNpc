package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;

public class CraftBasicGearGoal extends Goal {
    public static final String TEMP_TABLE_X = "PlayerNpcTemporaryCraftingTableX";
    public static final String TEMP_TABLE_Y = "PlayerNpcTemporaryCraftingTableY";
    public static final String TEMP_TABLE_Z = "PlayerNpcTemporaryCraftingTableZ";
    private static final int COOLDOWN_TICKS = 20 * 4;
    private static final int CRAFTING_TABLE_SCAN_RADIUS = 5;

    private final PlayerNpcEntity playerNpc;

    public CraftBasicGearGoal(PlayerNpcEntity playerNpc) {
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
                || this.playerNpc.getCraftGearCooldown() > 0) {
            return false;
        }

        return this.canCraftUsefulGear(serverLevel);
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
        this.playerNpc.setCurrentAiState("ai.player_npc.crafting_gear");
        boolean crafted = this.tryPlaceCraftingTable(serverLevel) || this.tryCraftTool();
        if (crafted) {
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
            serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 0.6F, 1.2F);
        }
        this.playerNpc.setCraftGearCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 15));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean canCraftUsefulGear(ServerLevel serverLevel) {
        return this.shouldPlaceCraftingTable(serverLevel)
                || this.hasNearbyCraftingTable(serverLevel) && this.canCraftTool();
    }

    private boolean canCraftTool() {
        return !this.hasTool(AxeItem.class) && (this.canCraftStoneTool(3, 2) || PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 3, 2))
                || !this.hasTool(PickaxeItem.class) && (this.canCraftStoneTool(3, 2) || PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 3, 2))
                || !this.hasTool(ShovelItem.class) && (this.canCraftStoneTool(1, 2) || PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 1, 2))
                || !this.hasTool(SwordItem.class) && (this.canCraftStoneTool(2, 1) || PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 2, 1))
                || !this.hasTool(FishingRodItem.class)
                && PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.STRING)) >= 2
                && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, 3);
    }

    private boolean shouldPlaceCraftingTable(ServerLevel serverLevel) {
        return this.needsBasicGear()
                && PlayerNpcCraftingUtil.canCraftCraftingTable(this.playerNpc.getInventory())
                && !this.hasNearbyCraftingTable(serverLevel)
                && this.findCraftingTablePlacement(serverLevel) != null;
    }

    private boolean needsBasicGear() {
        return !this.hasTool(PickaxeItem.class)
                || !this.hasTool(AxeItem.class)
                || !this.hasTool(SwordItem.class)
                || !this.hasTool(ShovelItem.class);
    }

    private boolean tryPlaceCraftingTable(ServerLevel serverLevel) {
        if (!this.shouldPlaceCraftingTable(serverLevel)) {
            return false;
        }

        BlockPos tablePos = this.findCraftingTablePlacement(serverLevel);
        if (tablePos == null || !PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 4)) {
            return false;
        }

        serverLevel.setBlockAndUpdate(tablePos, Blocks.CRAFTING_TABLE.defaultBlockState());
        this.playerNpc.getPersistentData().putInt(TEMP_TABLE_X, tablePos.getX());
        this.playerNpc.getPersistentData().putInt(TEMP_TABLE_Y, tablePos.getY());
        this.playerNpc.getPersistentData().putInt(TEMP_TABLE_Z, tablePos.getZ());
        this.playerNpc.getLookControl().setLookAt(tablePos.getX() + 0.5D, tablePos.getY() + 0.5D, tablePos.getZ() + 0.5D, 40.0F, 40.0F);
        serverLevel.playSound(null, tablePos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        return true;
    }

    private boolean tryCraftTool() {
        if (!this.hasTool(PickaxeItem.class)) {
            if (this.tryConsumeStoneToolIngredients(3, 2)) {
                return InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.STONE_PICKAXE));
            }
            if (PlayerNpcCraftingUtil.tryConsumePlanksAndSticks(this.playerNpc.getInventory(), 3, 2)) {
                return InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.WOODEN_PICKAXE));
            }
        }

        if (!this.hasTool(AxeItem.class)) {
            if (this.tryConsumeStoneToolIngredients(3, 2)) {
                return InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.STONE_AXE));
            }
            if (PlayerNpcCraftingUtil.tryConsumePlanksAndSticks(this.playerNpc.getInventory(), 3, 2)) {
                return InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.WOODEN_AXE));
            }
        }

        if (!this.hasTool(ShovelItem.class)) {
            if (this.tryConsumeStoneToolIngredients(1, 2)) {
                return InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.STONE_SHOVEL));
            }
            if (PlayerNpcCraftingUtil.tryConsumePlanksAndSticks(this.playerNpc.getInventory(), 1, 2)) {
                return InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.WOODEN_SHOVEL));
            }
        }

        if (!this.hasTool(SwordItem.class)) {
            if (this.tryConsumeStoneToolIngredients(2, 1)) {
                return InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.STONE_SWORD));
            }
            if (PlayerNpcCraftingUtil.tryConsumePlanksAndSticks(this.playerNpc.getInventory(), 2, 1)) {
                return InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.WOODEN_SWORD));
            }
        }

        if (!this.hasTool(FishingRodItem.class)
                && PlayerNpcCraftingUtil.tryConsumePlanksAndSticks(this.playerNpc.getInventory(), 0, 3)
                && PlayerNpcCraftingUtil.consumeItem(this.playerNpc.getInventory(), stack -> stack.is(Items.STRING), 2)) {
            return InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.FISHING_ROD));
        }

        return false;
    }

    private boolean hasTool(Class<?> toolClass) {
        if (toolClass.isInstance(this.playerNpc.getMainHandItem().getItem())) {
            return true;
        }
        return InventoryUtils.hasItem(this.playerNpc, stack -> toolClass.isInstance(stack.getItem()));
    }

    private int countStone() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    private boolean consumeStone(int count) {
        return PlayerNpcCraftingUtil.consumeItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE), count);
    }

    private boolean canCraftStoneTool(int stoneNeeded, int sticksNeeded) {
        return this.countStone() >= stoneNeeded
                && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, sticksNeeded);
    }

    private boolean tryConsumeStoneToolIngredients(int stoneNeeded, int sticksNeeded) {
        if (!this.canCraftStoneTool(stoneNeeded, sticksNeeded)) {
            return false;
        }
        return this.consumeStone(stoneNeeded)
                && PlayerNpcCraftingUtil.tryConsumePlanksAndSticks(this.playerNpc.getInventory(), 0, sticksNeeded);
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

    private BlockPos findCraftingTablePlacement(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        BlockPos[] candidates = {
                origin.relative(this.playerNpc.getDirection()),
                origin.relative(this.playerNpc.getDirection().getClockWise()),
                origin.relative(this.playerNpc.getDirection().getCounterClockWise()),
                origin.relative(this.playerNpc.getDirection().getOpposite()),
                origin
        };

        for (BlockPos candidate : candidates) {
            if (serverLevel.getBlockState(candidate).isAir()
                    && serverLevel.getBlockState(candidate.below()).isSolidRender(serverLevel, candidate.below())) {
                return candidate.immutable();
            }
        }
        return null;
    }
}
