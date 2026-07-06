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
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;

public class UtilityCraftingGoal extends Goal {
    private static final int WATER_SCAN_RADIUS = 7;
    private static final int COOLDOWN_TICKS = 180;

    private final PlayerNpcEntity playerNpc;

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
                || this.playerNpc.getCraftCooldown() > 0
                || !this.isNearWater(serverLevel)
                || this.hasBoat()) {
            return false;
        }

        return PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()) >= 4;
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

        if (PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()) >= 5
                && PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 5, this.playerNpc.getRawLogReserveTarget())) {
            InventoryUtils.addItem(this.playerNpc, new ItemStack(Items.OAK_BOAT));
            this.playerNpc.triggerMainHandUseAnimation();
            serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        } else {
            BlockPos tablePos = this.findCraftingTablePlacement(serverLevel);
            if (tablePos != null && PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 4, this.playerNpc.getRawLogReserveTarget())) {
                serverLevel.setBlockAndUpdate(tablePos, Blocks.CRAFTING_TABLE.defaultBlockState());
                this.playerNpc.getLookControl().setLookAt(tablePos.getX() + 0.5D, tablePos.getY() + 0.5D, tablePos.getZ() + 0.5D, 40.0F, 40.0F);
                this.playerNpc.triggerMainHandUseAnimation();
                serverLevel.playSound(null, tablePos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
            }
        }

        this.playerNpc.setCraftCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(160));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
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
}
