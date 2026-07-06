package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.List;

public class PlayerNpcFishingGoal extends Goal {
    private static final int WATER_SCAN_RADIUS = 8;
    private static final double FISHING_DISTANCE_SQR = 6.0D * 6.0D;
    private static final int COOLDOWN_TICKS = 20 * 25;

    private final PlayerNpcEntity playerNpc;
    private BlockPos waterPos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private boolean usingTemporaryRod;
    private int fishingTicks;

    public PlayerNpcFishingGoal(PlayerNpcEntity playerNpc) {
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
                || this.playerNpc.getFishingCooldown() > 0
                || !this.hasFishingRod()) {
            return false;
        }

        this.waterPos = this.findWater(serverLevel);
        return this.waterPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.fishingTicks > 0
                && this.waterPos != null
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null;
    }

    @Override
    public void start() {
        if (!this.equipRodIfNeeded()) {
            this.waterPos = null;
            return;
        }

        this.fishingTicks = 100 + this.playerNpc.getRandom().nextInt(80);
        this.playerNpc.setCurrentAiState("ai.player_npc.fishing");
        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        this.playerNpc.playSound(SoundEvents.FISHING_BOBBER_THROW, 1.0F, 1.0F);
        this.moveNearWater();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.waterPos == null) {
            this.stop();
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.waterPos.getX() + 0.5D, this.waterPos.getY() + 0.1D, this.waterPos.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.playerNpc.distanceToSqr(Vec3.atCenterOf(this.waterPos)) > FISHING_DISTANCE_SQR) {
            this.moveNearWater();
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.fishingTicks--;
        if (this.fishingTicks > 0) {
            if (this.fishingTicks % 20 == 0) {
                serverLevel.sendParticles(ParticleTypes.FISHING, this.waterPos.getX() + 0.5D, this.waterPos.getY() + 0.15D, this.waterPos.getZ() + 0.5D, 2, 0.25D, 0.0D, 0.25D, 0.01D);
            }
            return;
        }

        this.catchFish(serverLevel);
        this.stop();
    }

    @Override
    public void stop() {
        if (this.usingTemporaryRod) {
            ItemStack rod = this.playerNpc.getMainHandItem().copy();
            if (!rod.isEmpty() && rod.getItem() instanceof FishingRodItem && !InventoryUtils.addItem(this.playerNpc, rod)) {
                this.playerNpc.spawnAtLocation(rod);
            }
            this.playerNpc.setItemInHand(InteractionHand.MAIN_HAND, this.previousMainHand.copy());
        }

        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setFishingCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 20));
        }
        this.waterPos = null;
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryRod = false;
        this.fishingTicks = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void catchFish(ServerLevel serverLevel) {
        ItemStack rod = this.playerNpc.getMainHandItem();
        if (!(rod.getItem() instanceof FishingRodItem) || this.waterPos == null) {
            return;
        }

        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        serverLevel.playSound(null, this.waterPos, SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 1.0F, 1.0F);
        this.playerNpc.hurtMainHandItem(1);
        LootParams lootParams = new LootParams.Builder(serverLevel)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(this.waterPos))
                .withParameter(LootContextParams.TOOL, rod)
                .withOptionalParameter(LootContextParams.THIS_ENTITY, this.playerNpc)
                .create(LootContextParamSets.FISHING);
        LootTable lootTable = serverLevel.getServer().getLootData().getLootTable(BuiltInLootTables.FISHING);
        List<ItemStack> loot = lootTable.getRandomItems(lootParams);
        for (ItemStack stack : loot) {
            if (!InventoryUtils.addItem(this.playerNpc, stack)) {
                this.playerNpc.spawnAtLocation(stack);
            }
        }
    }

    private boolean hasFishingRod() {
        return this.playerNpc.getMainHandItem().getItem() instanceof FishingRodItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof FishingRodItem);
    }

    private boolean equipRodIfNeeded() {
        if (this.playerNpc.getMainHandItem().getItem() instanceof FishingRodItem) {
            return true;
        }

        ItemStack rod = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof FishingRodItem, 1)
                .orElse(ItemStack.EMPTY);
        if (rod.isEmpty()) {
            return false;
        }

        this.previousMainHand = this.playerNpc.getMainHandItem().copy();
        this.usingTemporaryRod = true;
        this.playerNpc.setItemInHand(InteractionHand.MAIN_HAND, rod);
        return true;
    }

    private BlockPos findWater(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-WATER_SCAN_RADIUS, -3, -WATER_SCAN_RADIUS), center.offset(WATER_SCAN_RADIUS, 1, WATER_SCAN_RADIUS))) {
            if (serverLevel.getFluidState(pos).is(FluidTags.WATER)
                    && serverLevel.getBlockState(pos.above()).isAir()) {
                return pos.immutable();
            }
        }
        return null;
    }

    private void moveNearWater() {
        if (this.waterPos != null) {
            this.playerNpc.getNavigation().moveTo(this.waterPos.getX() + 0.5D, this.waterPos.getY(), this.waterPos.getZ() + 0.5D, 1.0D);
        }
    }
}
