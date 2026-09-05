package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.ChatUtil;
import com.pla.smart_npc.util.PlayerNpcTrashUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Brief, bounded personal maintenance, deliberately not a job/worker-slot goal. */
public final class ThrowTrashItemsGoal extends Goal {
    public static final int OCCUPIED_SLOT_THRESHOLD = 25;
    private final PlayerNpcEntity npc;
    private final CanUseThrottle throttle = new CanUseThrottle(100);
    private final List<ItemEntity> discarded = new ArrayList<>();
    private Vec3 disposalPoint;
    private int elapsed;

    public ThrowTrashItemsGoal(PlayerNpcEntity npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!isSafeToPause() || !throttle.canCheck(npc)) return false;
        SimpleContainer inventory = npc.getInventory();
        if (PlayerNpcTrashUtil.occupiedSlots(inventory) < OCCUPIED_SLOT_THRESHOLD) return false;
        boolean hasTrash = false;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isTrash(inventory.getItem(slot))) {
                hasTrash = true;
                break;
            }
        }
        disposalPoint = hasTrash ? findDisposalPoint() : null;
        return disposalPoint != null;
    }

    private boolean isSafeToPause() {
        return npc.level() instanceof ServerLevel && npc.isAlive() && !npc.isNoAi()
                && npc.onGround() && !npc.isPassenger() && !npc.isSleeping()
                && !npc.isInWaterOrBubble() && !npc.isInLava() && !npc.isOnFire()
                && npc.getTarget() == null && !npc.isHealing()
                && npc.getUpwardEscapeTarget() == null && !npc.isTeamUpRequestPending();
    }

    private boolean isTrash(ItemStack stack) {
        return PlayerNpcTrashUtil.isTrash(stack, npc.getInventory(), npc.getMainHandItem(), npc.getOffhandItem());
    }

    private Vec3 findDisposalPoint() {
        // Four nearby loaded cells only: no pathfinding or terrain-wide inventory cleanup search.
        Direction forward = npc.getDirection();
        Direction[] directions = {forward, forward.getClockWise(), forward.getCounterClockWise(), forward.getOpposite()};
        for (Direction direction : directions) {
            BlockPos pos = npc.blockPosition().relative(direction, 2);
            if (!npc.level().hasChunkAt(pos) || !npc.level().hasChunkAt(pos.below())
                    || !npc.level().getBlockState(pos).isAir()
                    || !npc.level().getBlockState(pos.above()).isAir()
                    || !npc.level().getBlockState(pos.below()).isFaceSturdy(npc.level(), pos.below(), Direction.UP)
                    || !npc.level().getFluidState(pos.below()).isEmpty()) continue;
            Vec3 point = Vec3.atBottomCenterOf(pos).add(0, 0.15D, 0);
            if (npc.level().clip(new ClipContext(npc.getEyePosition(), point,
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, npc)).getType() == HitResult.Type.MISS) return point;
        }
        return null;
    }

    @Override
    public void start() {
        elapsed = 0;
        discarded.clear();
        npc.getNavigation().stop();
        npc.setCurrentAiState("ai.player_npc.throwing_trash");
        throttle.retryIn(npc, 20 * 30);
    }

    @Override
    public boolean canContinueToUse() {
        return disposalPoint != null && elapsed < 40 && isSafeToPause();
    }

    @Override
    public boolean requiresUpdateEveryTick() { return true; }

    @Override
    public void tick() {
        npc.getLookControl().setLookAt(disposalPoint.x, disposalPoint.y, disposalPoint.z, 30, 30);
        elapsed++;
        if (elapsed == 10) throwBatch();
        if (elapsed == 35) burnBatch();
    }

    private void throwBatch() {
        SimpleContainer inventory = npc.getInventory();
        // Classify before removal so the result cannot depend on inventory slot ordering.
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isTrash(inventory.getItem(slot))) slots.add(slot);
        }
        Vec3 origin = npc.getEyePosition().add(0, -0.3D, 0);
        Vec3 velocity = disposalPoint.subtract(origin).multiply(0.16D, 0, 0.16D).add(0, 0.12D, 0);
        for (int slot : slots) {
            ItemStack stack = inventory.getItem(slot);
            ItemEntity drop = new ItemEntity(npc.level(), origin.x, origin.y, origin.z,
                    PlayerNpcTrashUtil.discardedCopy(stack));
            drop.setDeltaMovement(velocity);
            drop.setPickUpDelay(40);
            // Do not lose inventory contents if spawning is rejected by the world/another mod.
            if (npc.level().addFreshEntity(drop)) {
                inventory.setItem(slot, ItemStack.EMPTY);
                discarded.add(drop);
            }
        }
        if (!discarded.isEmpty()) {
            npc.swing(InteractionHand.MAIN_HAND);
            ChatUtil.throwTrash(npc);
        }
    }

    private ItemStack findFlintAndSteel() {
        if (npc.getMainHandItem().is(Items.FLINT_AND_STEEL)) return npc.getMainHandItem();
        if (npc.getOffhandItem().is(Items.FLINT_AND_STEEL)) return npc.getOffhandItem();
        for (int slot = 0; slot < npc.getInventory().getContainerSize(); slot++) {
            ItemStack stack = npc.getInventory().getItem(slot);
            if (stack.is(Items.FLINT_AND_STEEL)) return stack;
        }
        return ItemStack.EMPTY;
    }

    private void burnBatch() {
        ItemStack flint = findFlintAndSteel();
        if (flint.isEmpty() || !(npc.level() instanceof ServerLevel level)) return;
        boolean burned = false;
        // Only this batch: no world fire, no nearby loot queries, no damage to players or buildings.
        for (ItemEntity drop : discarded) {
            if (!drop.isAlive() || !drop.onGround() || drop.isInWaterOrBubble()
                    || npc.distanceToSqr(drop) > 16 || !PlayerNpcTrashUtil.isDiscarded(drop.getItem())) continue;
            level.sendParticles(ParticleTypes.FLAME, drop.getX(), drop.getY() + 0.1D, drop.getZ(), 6, 0.1, 0.1, 0.1, 0.01);
            level.sendParticles(ParticleTypes.SMOKE, drop.getX(), drop.getY() + 0.2D, drop.getZ(), 4, 0.1, 0.1, 0.1, 0.01);
            drop.setSecondsOnFire(2);
            drop.hurt(level.damageSources().inFire(), 5.0F);
            burned = true;
        }
        if (burned) {
            level.playSound(null, npc.blockPosition(), SoundEvents.FLINTANDSTEEL_USE, SoundSource.NEUTRAL, 0.7F, 1.0F);
            flint.hurtAndBreak(1, npc, owner -> {
                if (flint == owner.getMainHandItem()) owner.broadcastBreakEvent(InteractionHand.MAIN_HAND);
                else if (flint == owner.getOffhandItem()) owner.broadcastBreakEvent(InteractionHand.OFF_HAND);
            });
            npc.getInventory().setChanged();
        }
    }

    @Override
    public void stop() {
        discarded.clear();
        disposalPoint = null;
        npc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }
}
