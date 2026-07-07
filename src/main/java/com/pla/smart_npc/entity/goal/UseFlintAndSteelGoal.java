package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;

public class UseFlintAndSteelGoal extends Goal {
    private static final String AI_STATE = "ai.player_npc.using_flint_and_steel";
    private static final double MAX_PLACE_DISTANCE_SQR = 5.5D * 5.5D;

    private final PlayerNpcEntity playerNpc;
    private BlockPos firePos;

    public UseFlintAndSteelGoal(PlayerNpcEntity playerNpc) {
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
                || this.playerNpc.getFlintAndSteelCooldown() > 0
                || !this.hasFlintAndSteel()) {
            return false;
        }

        LivingEntity target = this.playerNpc.getTarget();
        if (target == null
                || !target.isAlive()
                || target == this.playerNpc
                || target.isOnFire()
                || target.fireImmune()
                || target.isInWaterOrBubble()
                || this.playerNpc.distanceToSqr(target) > MAX_PLACE_DISTANCE_SQR) {
            return false;
        }

        this.firePos = this.findFirePlacement(serverLevel, target);
        return this.firePos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.firePos == null) {
            this.firePos = null;
            return;
        }

        ItemStack previousMainHand = ItemStack.EMPTY;
        boolean usingTemporaryTool = false;
        if (!this.isFlintAndSteel(this.playerNpc.getMainHandItem())) {
            ItemStack flintAndSteel = this.playerNpc.consumeInventoryItem(this::isFlintAndSteel, 1).orElse(ItemStack.EMPTY);
            if (flintAndSteel.isEmpty()) {
                this.firePos = null;
                return;
            }

            previousMainHand = this.playerNpc.getMainHandItem().copy();
            usingTemporaryTool = true;
            this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, flintAndSteel);
        }

        if (!this.canPlaceFire(serverLevel, this.firePos)) {
            this.restoreMainHand(previousMainHand, usingTemporaryTool);
            this.firePos = null;
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.firePos.getX() + 0.5D,
                this.firePos.getY() + 0.5D,
                this.firePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        this.playerNpc.setCurrentAiState(AI_STATE);
        this.playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "placing fire @ %d %d %d",
                this.firePos.getX(),
                this.firePos.getY(),
                this.firePos.getZ()
        ));
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.setBlockAndUpdate(this.firePos, Blocks.FIRE.defaultBlockState());
        serverLevel.playSound(null, this.firePos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        this.playerNpc.hurtMainHandItem(1);
        this.playerNpc.markCombatProgress();
        this.playerNpc.setFlintAndSteelCooldown();
        this.restoreMainHand(previousMainHand, usingTemporaryTool);
        this.firePos = null;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private BlockPos findFirePlacement(ServerLevel serverLevel, LivingEntity target) {
        BlockPos feet = target.blockPosition();
        Direction facing = Direction.fromYRot(target.getYRot());
        BlockPos[] candidates = {
                feet,
                feet.relative(facing.getOpposite()),
                feet.relative(facing.getClockWise()),
                feet.relative(facing.getCounterClockWise())
        };

        for (BlockPos candidate : candidates) {
            if (this.canPlaceFire(serverLevel, candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private boolean canPlaceFire(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(pos);
        return state.getFluidState().isEmpty()
                && (state.isAir() || state.canBeReplaced())
                && Blocks.FIRE.defaultBlockState().canSurvive(serverLevel, pos);
    }

    private boolean hasFlintAndSteel() {
        return this.isFlintAndSteel(this.playerNpc.getMainHandItem())
                || InventoryUtils.hasItem(this.playerNpc, this::isFlintAndSteel);
    }

    private boolean isFlintAndSteel(ItemStack stack) {
        return !stack.isEmpty()
                && stack.is(Items.FLINT_AND_STEEL)
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage());
    }

    private void restoreMainHand(ItemStack previousMainHand, boolean usingTemporaryTool) {
        if (!usingTemporaryTool) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, previousMainHand.copy());
        if (!currentMainHand.isEmpty()
                && this.isFlintAndSteel(currentMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }
    }
}
