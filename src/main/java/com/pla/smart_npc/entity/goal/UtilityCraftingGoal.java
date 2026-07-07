package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;

public class UtilityCraftingGoal extends Goal {
    private static final int WATER_SCAN_RADIUS = 7;
    private static final int COOLDOWN_TICKS = 180;
    private static final int MIN_ARROW_COUNT = 16;

    private final PlayerNpcEntity playerNpc;
    private CraftAction action = CraftAction.NONE;

    public UtilityCraftingGoal(PlayerNpcEntity playerNpc) {
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
                || this.playerNpc.getCraftCooldown() > 0) {
            return false;
        }

        this.action = this.chooseAction(serverLevel);
        return this.action != CraftAction.NONE;
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
        this.playerNpc.setCurrentAiState("ai.player_npc.crafting");

        switch (this.action) {
            case FLINT_AND_STEEL -> {
                if (PlayerNpcCraftingUtil.tryCraftFlintAndSteel(this.playerNpc.getInventory())) {
                    this.playCraftSound(serverLevel, "crafted flint and steel", this.playerNpc.blockPosition());
                }
            }
            case ARROWS -> {
                if (PlayerNpcCraftingUtil.tryCraftArrows(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
                    this.playCraftSound(serverLevel, "crafted arrows", this.playerNpc.blockPosition());
                }
            }
            case BOAT -> {
                if (PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 5, this.playerNpc.getRawLogReserveTarget())) {
                    InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.OAK_BOAT));
                    this.playCraftSound(serverLevel, "crafted boat", this.playerNpc.blockPosition());
                }
            }
            case CRAFTING_TABLE -> {
                BlockPos tablePos = this.findCraftingTablePlacement(serverLevel);
                if (tablePos != null && PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 4, this.playerNpc.getRawLogReserveTarget())) {
                    serverLevel.setBlockAndUpdate(tablePos, Blocks.CRAFTING_TABLE.defaultBlockState());
                    this.playerNpc.getLookControl().setLookAt(tablePos.getX() + 0.5D, tablePos.getY() + 0.5D, tablePos.getZ() + 0.5D, 40.0F, 40.0F);
                    this.playCraftSound(serverLevel, "placed crafting table", tablePos);
                }
            }
            case NONE -> {
            }
        }

        this.playerNpc.setCraftCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(160));
        this.action = CraftAction.NONE;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private CraftAction chooseAction(ServerLevel serverLevel) {
        if (!this.hasFlintAndSteel()
                && PlayerNpcCraftingUtil.canCraftFlintAndSteel(this.playerNpc.getInventory())) {
            return CraftAction.FLINT_AND_STEEL;
        }

        if (this.hasBow()
                && this.countArrows() < MIN_ARROW_COUNT
                && PlayerNpcCraftingUtil.canCraftArrows(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
            return CraftAction.ARROWS;
        }

        if (this.isNearWater(serverLevel) && !this.hasBoat()) {
            int plankEquivalent = PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget());
            if (plankEquivalent >= 5) {
                return CraftAction.BOAT;
            }
            if (plankEquivalent >= 4) {
                return CraftAction.CRAFTING_TABLE;
            }
        }

        return CraftAction.NONE;
    }

    private boolean isNearWater(ServerLevel serverLevel) {
        if (this.playerNpc.isInWaterOrBubble()) {
            return true;
        }

        BlockPos center = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-WATER_SCAN_RADIUS, -2, -WATER_SCAN_RADIUS), center.offset(WATER_SCAN_RADIUS, 1, WATER_SCAN_RADIUS))) {
            if (serverLevel.getFluidState(pos).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBoat() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof BoatItem);
    }

    private boolean hasFlintAndSteel() {
        return this.playerNpc.getMainHandItem().is(Items.FLINT_AND_STEEL)
                || InventoryUtils.hasItem(this.playerNpc, Items.FLINT_AND_STEEL);
    }

    private boolean hasBow() {
        return this.playerNpc.getMainHandItem().getItem() instanceof BowItem
                || this.playerNpc.getOffhandItem().getItem() instanceof BowItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem);
    }

    private int countArrows() {
        int count = this.playerNpc.getMainHandItem().getItem() instanceof ArrowItem ? this.playerNpc.getMainHandItem().getCount() : 0;
        count += this.playerNpc.getOffhandItem().getItem() instanceof ArrowItem ? this.playerNpc.getOffhandItem().getCount() : 0;
        count += PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.getItem() instanceof ArrowItem);
        return count;
    }

    private void playCraftSound(ServerLevel serverLevel, String detail, BlockPos soundPos) {
        this.playerNpc.setCurrentAiDetail(detail);
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(null, soundPos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
    }

    private BlockPos findCraftingTablePlacement(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        BlockPos[] candidates = {
                origin.relative(this.playerNpc.getDirection()),
                origin.relative(this.playerNpc.getDirection().getClockWise()),
                origin.relative(this.playerNpc.getDirection().getCounterClockWise()),
                origin.relative(this.playerNpc.getDirection().getOpposite())
        };

        for (BlockPos candidate : candidates) {
            if (serverLevel.getBlockState(candidate).isAir()
                    && serverLevel.getBlockState(candidate.below()).isSolidRender(serverLevel, candidate.below())) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private enum CraftAction {
        NONE,
        FLINT_AND_STEEL,
        ARROWS,
        BOAT,
        CRAFTING_TABLE
    }
}
