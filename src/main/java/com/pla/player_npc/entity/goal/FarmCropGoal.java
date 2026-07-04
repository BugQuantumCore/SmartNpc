package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.Optional;

public class FarmCropGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 35;
    private static final int HARVEST_RADIUS = 10;
    private static final double FARM_DISTANCE_SQR = 3.5D * 3.5D;

    private final PlayerNpcEntity playerNpc;
    private BlockPos targetPos;
    private Action action = Action.NONE;

    public FarmCropGoal(PlayerNpcEntity playerNpc) {
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
                || this.playerNpc.getFarmCooldown() > 0) {
            return false;
        }

        this.targetPos = this.findMatureCrop(serverLevel);
        if (this.targetPos != null) {
            this.action = Action.HARVEST;
            return true;
        }

        this.targetPos = this.findFarmPlacement(serverLevel);
        if (this.targetPos != null) {
            this.action = Action.PLANT;
            return true;
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return this.targetPos != null
                && this.action != Action.NONE
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null;
    }

    @Override
    public void start() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.targetPos == null) {
            return;
        }

        this.playerNpc.setCurrentAiState("ai.player_npc.farming");
        this.playerNpc.setCurrentAiDetail(this.targetPos.getX() + " " + this.targetPos.getY() + " " + this.targetPos.getZ());
        this.moveToTarget();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.targetPos == null) {
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D) > FARM_DISTANCE_SQR) {
            if (this.playerNpc.getNavigation().isDone()) {
                this.moveToTarget();
            }
            return;
        }

        this.playerNpc.getNavigation().stop();

        if (this.action == Action.HARVEST) {
            serverLevel.destroyBlock(this.targetPos, true, this.playerNpc);
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
            serverLevel.playSound(null, this.targetPos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 0.7F, 1.0F);
        } else if (this.action == Action.PLANT && this.consumeSeed()) {
            serverLevel.setBlockAndUpdate(this.targetPos, Blocks.FARMLAND.defaultBlockState());
            serverLevel.setBlockAndUpdate(this.targetPos.above(), Blocks.WHEAT.defaultBlockState());
            this.playerNpc.hurtHeldOrInventoryItem(stack -> stack.getItem() instanceof HoeItem, 1);
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
            serverLevel.playSound(null, this.targetPos, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 0.8F, 1.0F);
        }

        this.targetPos = null;
        this.action = Action.NONE;
    }

    @Override
    public void stop() {
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setFarmCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 40));
        }
        this.targetPos = null;
        this.action = Action.NONE;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void moveToTarget() {
        if (this.targetPos != null) {
            this.playerNpc.getNavigation().moveTo(this.targetPos.getX() + 0.5D, this.targetPos.getY(), this.targetPos.getZ() + 0.5D, 1.0D);
        }
    }

    private BlockPos findMatureCrop(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-HARVEST_RADIUS, -2, -HARVEST_RADIUS), center.offset(HARVEST_RADIUS, 2, HARVEST_RADIUS))) {
            BlockState state = serverLevel.getBlockState(pos);
            if (state.getBlock() instanceof CropBlock cropBlock && cropBlock.isMaxAge(state)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findFarmPlacement(ServerLevel serverLevel) {
        if (!this.hasHoe() || !InventoryUtils.hasItem(this.playerNpc, Items.WHEAT_SEEDS)) {
            return null;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return null;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        int maxWidth = Math.min(5, Math.max(3, homeArea.width()));
        int maxDepth = Math.min(5, Math.max(3, homeArea.depth()));
        BlockPos farmOrigin = homeArea.origin().offset(homeArea.width() + 1, 0, 0);
        for (int x = 0; x < maxWidth; x++) {
            for (int z = 0; z < maxDepth; z++) {
                BlockPos pos = farmOrigin.offset(x, 0, z);
                if ((serverLevel.getBlockState(pos).is(Blocks.DIRT) || serverLevel.getBlockState(pos).is(Blocks.GRASS_BLOCK))
                        && serverLevel.getBlockState(pos.above()).isAir()) {
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    private boolean hasHoe() {
        return this.playerNpc.getMainHandItem().getItem() instanceof HoeItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof HoeItem);
    }

    private boolean consumeSeed() {
        return this.playerNpc.consumeInventoryItem(Items.WHEAT_SEEDS, 1).isPresent();
    }

    private enum Action {
        NONE,
        HARVEST,
        PLANT
    }
}
