package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.Optional;

public class LootNearbyChestGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 35;
    private static final int SEARCH_RADIUS = 10;
    private static final double STAND_DISTANCE_SQR = 1.5D * 1.5D;
    private static final int TAKE_INTERVAL_TICKS = 6;
    private static final int REPATH_INTERVAL_TICKS = 20;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(40);
    private BlockPos chestPos;
    private BlockPos standPos;
    private boolean chestOpen;
    private boolean finishedLooting;
    private int nextLootSlot;
    private int takeDelayTicks;
    private int repathTicks;

    public LootNearbyChestGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = Math.min(speed, 1.0D);
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
                || this.playerNpc.getLootChestCooldown() > 0) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        this.chestPos = this.findChest(serverLevel);
        return this.chestPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.chestPos != null
                && this.standPos != null
                && !this.finishedLooting
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && serverLevel.getBlockState(this.chestPos).is(Blocks.CHEST);
    }

    @Override
    public void start() {
        this.chestOpen = false;
        this.finishedLooting = false;
        this.nextLootSlot = 0;
        this.takeDelayTicks = 0;
        this.repathTicks = REPATH_INTERVAL_TICKS;
        this.playerNpc.setCurrentAiState("ai.player_npc.looting_chest");
        if (this.chestPos != null && this.standPos != null) {
            this.playerNpc.setCurrentAiDetail(this.chestPos.getX() + " " + this.chestPos.getY() + " " + this.chestPos.getZ());
            this.moveToStandPos();
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.chestPos == null || this.standPos == null) {
            return;
        }

        this.lookAtChest();
        if (!this.canInteractWithChest(serverLevel)) {
            if (this.chestOpen) {
                this.closeChest(serverLevel, this.chestPos);
                this.chestOpen = false;
            }
            if (this.repathTicks-- <= 0) {
                this.moveToStandPos();
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (serverLevel.getBlockEntity(this.chestPos) instanceof ChestBlockEntity chest && this.hasLoot(chest)) {
            if (!this.chestOpen) {
                this.openChest(serverLevel, this.chestPos);
                this.chestOpen = true;
            }

            if (this.takeDelayTicks > 0) {
                this.takeDelayTicks--;
                return;
            }

            int moved = this.lootNextStack(chest);
            if (moved > 0) {
                this.takeDelayTicks = TAKE_INTERVAL_TICKS;
                this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
                this.playerNpc.equipBetterGearFromInventory();
                serverLevel.playSound(null, this.chestPos, SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.4F, 1.0F);
                return;
            }
        }

        this.finishedLooting = true;
        if (this.chestOpen) {
            this.closeChest(serverLevel, this.chestPos);
            this.chestOpen = false;
        }
    }

    @Override
    public void stop() {
        if (this.chestOpen
                && this.chestPos != null
                && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.closeChest(serverLevel, this.chestPos);
        }
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setLootChestCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 30));
        }
        this.chestPos = null;
        this.standPos = null;
        this.chestOpen = false;
        this.finishedLooting = false;
        this.nextLootSlot = 0;
        this.takeDelayTicks = 0;
        this.repathTicks = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void lookAtChest() {
        if (this.chestPos == null) {
            return;
        }
        this.playerNpc.getLookControl().setLookAt(
                this.chestPos.getX() + 0.5D,
                this.chestPos.getY() + 0.5D,
                this.chestPos.getZ() + 0.5D,
                50.0F,
                50.0F
        );
    }

    private BlockPos findChest(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(this.playerNpc);
        BlockPos bestChest = null;
        BlockPos bestStand = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-SEARCH_RADIUS, -3, -SEARCH_RADIUS), center.offset(SEARCH_RADIUS, 3, SEARCH_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (this.playerNpc.isOwnedChest(immutable)
                    || (homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), immutable))) {
                continue;
            }
            if (serverLevel.getBlockState(immutable).is(Blocks.CHEST)
                    && serverLevel.getBlockEntity(immutable) instanceof ChestBlockEntity chest
                    && this.hasLoot(chest)) {
                BlockPos stand = this.findStandPos(serverLevel, immutable);
                if (stand == null) {
                    continue;
                }
                double distance = this.playerNpc.distanceToSqr(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestChest = immutable;
                    bestStand = stand;
                }
            }
        }
        this.standPos = bestStand;
        return bestChest;
    }

    private int lootNextStack(Container chest) {
        int size = chest.getContainerSize();
        for (int checked = 0; checked < size; checked++) {
            int slot = (this.nextLootSlot + checked) % size;
            ItemStack stack = chest.getItem(slot);
            if (stack.isEmpty() || !this.canAccept(stack)) {
                continue;
            }

            int moved = this.transferSlotToInventory(chest, slot);
            this.nextLootSlot = (slot + 1) % size;
            if (moved > 0) {
                return moved;
            }
        }
        return 0;
    }

    private int transferSlotToInventory(Container chest, int chestSlot) {
        ItemStack source = chest.getItem(chestSlot);
        if (source.isEmpty()) {
            return 0;
        }

        int moved = 0;
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize() && !source.isEmpty(); i++) {
            ItemStack existing = inventory.getItem(i);
            if (existing.isEmpty()
                    || !ItemStack.isSameItemSameTags(existing, source)
                    || existing.getCount() >= existing.getMaxStackSize()) {
                continue;
            }

            int transfer = Math.min(source.getCount(), existing.getMaxStackSize() - existing.getCount());
            existing.grow(transfer);
            source.shrink(transfer);
            moved += transfer;
        }

        for (int i = 0; i < inventory.getContainerSize() && !source.isEmpty(); i++) {
            if (!inventory.getItem(i).isEmpty()) {
                continue;
            }

            ItemStack inserted = source.copy();
            inserted.setCount(Math.min(source.getCount(), source.getMaxStackSize()));
            inventory.setItem(i, inserted);
            source.shrink(inserted.getCount());
            moved += inserted.getCount();
        }

        if (source.isEmpty()) {
            chest.setItem(chestSlot, ItemStack.EMPTY);
        }
        if (moved > 0) {
            inventory.setChanged();
            chest.setChanged();
        }
        return moved;
    }

    private void openChest(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        serverLevel.blockEvent(pos, state.getBlock(), 1, 1);
        serverLevel.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 1.0F);
    }

    private void closeChest(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.getBlockState(pos).is(Blocks.CHEST)) {
            return;
        }

        BlockState state = serverLevel.getBlockState(pos);
        serverLevel.blockEvent(pos, state.getBlock(), 1, 0);
        serverLevel.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 1.0F);
    }

    private boolean hasLoot(Container chest) {
        for (int i = 0; i < chest.getContainerSize(); i++) {
            ItemStack stack = chest.getItem(i);
            if (!stack.isEmpty() && this.canAccept(stack)) {
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

    private void moveToStandPos() {
        if (this.standPos != null) {
            this.playerNpc.getNavigation().moveTo(this.standPos.getX() + 0.5D, this.standPos.getY(), this.standPos.getZ() + 0.5D, this.speed);
        }
    }

    private boolean canInteractWithChest(ServerLevel serverLevel) {
        return this.standPos != null
                && this.chestPos != null
                && this.canStandAt(serverLevel, this.standPos)
                && this.standPos.distManhattan(this.chestPos) == 1
                && this.playerNpc.distanceToSqr(this.standPos.getX() + 0.5D, this.standPos.getY(), this.standPos.getZ() + 0.5D) <= STAND_DISTANCE_SQR;
    }

    private BlockPos findStandPos(ServerLevel serverLevel, BlockPos chestPos) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = chestPos.relative(direction).immutable();
            if (!this.canStandAt(serverLevel, candidate)) {
                continue;
            }
            double distance = this.playerNpc.distanceToSqr(candidate.getX() + 0.5D, candidate.getY(), candidate.getZ() + 0.5D);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

}
