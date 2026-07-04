package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.task.DelayedTask;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.util.EnumSet;
import java.util.Optional;

public class LootNearbyChestGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 35;
    private static final int SEARCH_RADIUS = 10;
    private static final double LOOT_DISTANCE_SQR = 3.5D * 3.5D;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private BlockPos chestPos;

    public LootNearbyChestGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
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
                || this.playerNpc.getLootChestCooldown() > 0
                || !this.hasFreeInventorySpace()) {
            return false;
        }

        this.chestPos = this.findChest(serverLevel);
        return this.chestPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.chestPos != null
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && serverLevel.getBlockState(this.chestPos).is(Blocks.CHEST);
    }

    @Override
    public void start() {
        this.playerNpc.setCurrentAiState("ai.player_npc.looting_chest");
        if (this.chestPos != null) {
            this.playerNpc.setCurrentAiDetail(this.chestPos.getX() + " " + this.chestPos.getY() + " " + this.chestPos.getZ());
            this.playerNpc.getNavigation().moveTo(this.chestPos.getX() + 0.5D, this.chestPos.getY(), this.chestPos.getZ() + 0.5D, this.speed);
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.chestPos == null) {
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.chestPos.getX() + 0.5D, this.chestPos.getY() + 0.5D, this.chestPos.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.playerNpc.distanceToSqr(this.chestPos.getX() + 0.5D, this.chestPos.getY() + 0.5D, this.chestPos.getZ() + 0.5D) > LOOT_DISTANCE_SQR) {
            if (this.playerNpc.getNavigation().isDone()) {
                this.playerNpc.getNavigation().moveTo(this.chestPos.getX() + 0.5D, this.chestPos.getY(), this.chestPos.getZ() + 0.5D, this.speed);
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (serverLevel.getBlockEntity(this.chestPos) instanceof ChestBlockEntity chest && this.hasUsefulLoot(chest)) {
            this.openChest(serverLevel, this.chestPos);
            boolean looted = this.lootChest(chest);
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
            if (looted) {
                serverLevel.playSound(null, this.chestPos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
            }
            this.closeChestLater(serverLevel, this.chestPos);
        }
        this.chestPos = null;
    }

    @Override
    public void stop() {
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setLootChestCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 30));
        }
        this.chestPos = null;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private BlockPos findChest(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(this.playerNpc);
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-SEARCH_RADIUS, -3, -SEARCH_RADIUS), center.offset(SEARCH_RADIUS, 3, SEARCH_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), immutable)) {
                continue;
            }
            if (serverLevel.getBlockState(immutable).is(Blocks.CHEST)
                    && serverLevel.getBlockEntity(immutable) instanceof ChestBlockEntity chest
                    && this.hasUsefulLoot(chest)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean lootChest(Container chest) {
        int movedStacks = 0;
        for (int i = 0; i < chest.getContainerSize() && movedStacks < 4; i++) {
            ItemStack stack = chest.getItem(i);
            if (stack.isEmpty() || !this.isUsefulLoot(stack) || !this.canAccept(stack)) {
                continue;
            }

            int amount = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), 16));
            ItemStack moved = chest.removeItem(i, amount);
            if (moved.isEmpty()) {
                continue;
            }

            if (!InventoryUtils.addItem(this.playerNpc, moved)) {
                this.playerNpc.spawnAtLocation(moved);
            }
            movedStacks++;
        }
        chest.setChanged();
        return movedStacks > 0;
    }

    private void openChest(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        serverLevel.blockEvent(pos, state.getBlock(), 1, 1);
        serverLevel.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
    }

    private void closeChestLater(ServerLevel serverLevel, BlockPos pos) {
        BlockPos chestPos = pos.immutable();
        new DelayedTask(12) {
            @Override
            public void run() {
                if (!serverLevel.getBlockState(chestPos).is(Blocks.CHEST)) {
                    return;
                }

                BlockState state = serverLevel.getBlockState(chestPos);
                serverLevel.blockEvent(chestPos, state.getBlock(), 1, 0);
                serverLevel.playSound(null, chestPos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
            }
        };
    }

    private boolean hasUsefulLoot(Container chest) {
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (!stack.isEmpty() && this.isUsefulLoot(stack) && this.canAccept(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean canAccept(ItemStack incoming) {
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameTags(stack, incoming) && stack.getCount() < stack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFreeInventorySpace() {
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isUsefulLoot(ItemStack stack) {
        return stack.isEdible()
                || stack.is(Items.ARROW)
                || stack.is(Items.ENDER_PEARL)
                || stack.is(Items.WATER_BUCKET)
                || stack.is(Items.LAVA_BUCKET)
                || stack.is(Items.BUCKET)
                || stack.is(Items.COAL)
                || stack.is(Items.CHARCOAL)
                || stack.is(Items.IRON_INGOT)
                || stack.is(Items.GOLD_INGOT)
                || stack.is(Items.DIAMOND)
                || stack.is(Items.EMERALD)
                || stack.is(Items.WHEAT)
                || stack.is(Items.STICK)
                || stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof DiggerItem
                || stack.getItem() instanceof ArmorItem
                || stack.getItem() instanceof BowItem
                || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof ProjectileWeaponItem
                || stack.getItem() instanceof ShieldItem
                || stack.getItem() instanceof BlockItem;
    }
}
