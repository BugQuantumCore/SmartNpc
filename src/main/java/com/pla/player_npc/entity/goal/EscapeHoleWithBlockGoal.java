package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class EscapeHoleWithBlockGoal extends Goal {
    private static final int COOLDOWN_TICKS = 40;
    private static final int PLACE_DELAY_TICKS = 4;
    private static final int MAX_PLACE_WAIT_TICKS = 12;

    private final PlayerNpcEntity playerNpc;
    private BlockPos placePos;
    private int placeDelayTicks;
    private int placeWaitTicks;
    private boolean placedBlock;

    public EscapeHoleWithBlockGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isInWaterOrBubble()
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || !InventoryUtils.hasItem(this.playerNpc, this::isEscapeBlock)) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (!serverLevel.getBlockState(feet).isAir()
                || !serverLevel.getBlockState(feet.above()).isAir()
                || !serverLevel.getBlockState(feet.below()).isSolidRender(serverLevel, feet.below())) {
            return false;
        }

        int walls = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!serverLevel.getBlockState(feet.relative(direction)).isAir()
                    || !serverLevel.getBlockState(feet.relative(direction).above()).isAir()) {
                walls++;
            }
        }

        if (walls < 3) {
            return false;
        }

        this.placePos = feet.immutable();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.placePos != null
                && !this.placedBlock
                && this.placeWaitTicks < MAX_PLACE_WAIT_TICKS
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && this.playerNpc.level() instanceof ServerLevel;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel) || this.placePos == null) {
            return;
        }

        this.playerNpc.setCurrentAiState("ai.player_npc.escaping_hole");
        this.playerNpc.setDeltaMovement(this.playerNpc.getDeltaMovement().add(0.0D, 0.42D, 0.0D));
        this.playerNpc.hasImpulse = true;
        this.placeDelayTicks = PLACE_DELAY_TICKS;
        this.placeWaitTicks = 0;
        this.placedBlock = false;
        this.playerNpc.setHoleEscapeCooldown(COOLDOWN_TICKS);
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.placePos == null) {
            return;
        }

        if (this.placeDelayTicks > 0) {
            this.placeDelayTicks--;
            return;
        }

        this.placeWaitTicks++;
        if (this.playerNpc.getBoundingBox().minY < this.placePos.getY() + 0.95D) {
            return;
        }

        ItemStack blockStack = InventoryUtils.consumeItem(this.playerNpc, this::isEscapeBlock, 1).orElse(ItemStack.EMPTY);
        if (blockStack.isEmpty() || !(blockStack.getItem() instanceof BlockItem blockItem) || !serverLevel.getBlockState(this.placePos).canBeReplaced()) {
            this.placePos = null;
            return;
        }

        serverLevel.setBlockAndUpdate(this.placePos, blockItem.getBlock().defaultBlockState());
        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        serverLevel.playSound(null, this.placePos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        this.placedBlock = true;
        this.placePos = null;
    }

    @Override
    public void stop() {
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.placePos = null;
        this.placeDelayTicks = 0;
        this.placeWaitTicks = 0;
        this.placedBlock = false;
    }

    private boolean isEscapeBlock(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem
                && !stack.is(Items.CRAFTING_TABLE)
                && !stack.is(Items.CHEST)
                && !stack.is(Items.FURNACE)
                && !(stack.getItem() instanceof BedItem)
                && !((BlockItem) stack.getItem()).getBlock().defaultBlockState().is(Blocks.TORCH);
    }
}
