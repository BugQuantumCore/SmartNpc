package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class EatHealingFoodGoal extends Goal {
    private static final int EAT_TICKS = 32;
    private static final int PATH_RECALCULATE_TICKS = 8;
    private static final double EATING_COMBAT_SPEED = 0.65D;
    private static final double EATING_WANDER_SPEED = 0.55D;
    private static final double CLOSE_COMBAT_DISTANCE_SQR = 5.0D * 5.0D;
    private static final double CHASE_DISTANCE_SQR = 7.0D * 7.0D;
    private static final double CHASE_POWER_MARGIN = 4.0D;
    private static final float REGULAR_FOOD_HEAL_AMOUNT = 4.0F;

    private final PlayerNpcEntity playerNpc;
    private ItemStack foodStack = ItemStack.EMPTY;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int eatTicks;
    private int pathRecalculateTicks;
    private boolean usingTemporaryFood;
    private boolean finishedEating;
    private boolean wasSprinting;

    public EatHealingFoodGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean isInterruptable() {
        return false;
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getGapCooldown() > 0
                || this.playerNpc.getHealth() >= this.playerNpc.getMaxHealth() * 0.70F) {
            return false;
        }

        return InventoryUtils.selectHealingFood(this.playerNpc, this.playerNpc.getRandom())
                .filter(stack -> !stack.isEmpty())
                .map(stack -> {
                    this.foodStack = stack;
                    return true;
                })
                .orElse(false);
    }

    @Override
    public boolean canContinueToUse() {
        return this.eatTicks > 0
                && this.playerNpc.isAlive()
                && !this.foodStack.isEmpty()
                && this.playerNpc.getHealth() < this.playerNpc.getMaxHealth();
    }

    @Override
    public void start() {
        ItemStack equippedFood = InventoryUtils.consumeItem(this.playerNpc, stack -> ItemStack.isSameItemSameTags(stack, this.foodStack), 1)
                .orElse(ItemStack.EMPTY);
        if (equippedFood.isEmpty()) {
            this.foodStack = ItemStack.EMPTY;
            return;
        }

        this.eatTicks = EAT_TICKS;
        this.previousMainHand = this.playerNpc.getMainHandItem().copy();
        this.foodStack = equippedFood.copy();
        this.foodStack.setCount(1);
        this.usingTemporaryFood = true;
        this.finishedEating = false;
        this.pathRecalculateTicks = 0;
        this.wasSprinting = this.playerNpc.isSprinting();
        this.playerNpc.setHealing(true);
        this.playerNpc.setSprinting(false);
        this.playerNpc.setCurrentAiState("ai.player_npc.eating");
        this.playerNpc.setItemInHand(InteractionHand.MAIN_HAND, this.foodStack.copy());
        this.playerNpc.startUsingItem(InteractionHand.MAIN_HAND);
        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        this.updateEatingMovement();
    }

    @Override
    public void stop() {
        boolean shouldApplyCooldown = this.usingTemporaryFood || this.finishedEating;
        this.playerNpc.stopUsingItem();
        if (this.usingTemporaryFood) {
            ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
            if (!this.finishedEating && this.isSameFood(currentMainHand)) {
                this.giveOrDrop(currentMainHand);
            } else if (!currentMainHand.isEmpty()
                    && !this.isSameFood(currentMainHand)
                    && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)) {
                this.giveOrDrop(currentMainHand);
            }
            this.playerNpc.setItemInHand(InteractionHand.MAIN_HAND, this.previousMainHand.copy());
        }

        this.eatTicks = 0;
        this.pathRecalculateTicks = 0;
        this.foodStack = ItemStack.EMPTY;
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryFood = false;
        this.finishedEating = false;
        this.playerNpc.setSprinting(this.wasSprinting);
        this.wasSprinting = false;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setHealing(false);
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);

        if (shouldApplyCooldown) {
            this.playerNpc.setGapCooldown();
        }
    }

    @Override
    public void tick() {
        if (this.eatTicks <= 0) {
            return;
        }

        if (this.eatTicks % 8 == 0) {
            this.playerNpc.level().playSound(null, this.playerNpc.blockPosition(), SoundEvents.GENERIC_EAT, SoundSource.HOSTILE, 0.8F, 1.0F);
            this.playerNpc.startUsingItem(InteractionHand.MAIN_HAND);
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        }

        if (this.pathRecalculateTicks-- <= 0) {
            this.updateEatingMovement();
            this.pathRecalculateTicks = PATH_RECALCULATE_TICKS;
        }

        this.eatTicks--;
        if (this.eatTicks > 0) {
            return;
        }

        ItemStack heldFood = this.playerNpc.getMainHandItem();
        ItemStack eatenFood = this.foodStack.copy();
        eatenFood.setCount(1);
        if (this.isSameFood(heldFood)) {
            heldFood.shrink(1);
        }
        this.finishedEating = true;

        if (eatenFood.is(Items.GOLDEN_APPLE)) {
            this.playerNpc.heal(this.getHealAmount(eatenFood));
            this.playerNpc.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
            this.playerNpc.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, 0));
        } else if (eatenFood.is(Items.ENCHANTED_GOLDEN_APPLE)) {
            this.playerNpc.heal(this.getHealAmount(eatenFood));
            this.playerNpc.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 400, 1));
            this.playerNpc.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 2400, 3));
            this.playerNpc.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 6000, 0));
            this.playerNpc.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 6000, 0));
        } else {
            this.healDirectly(REGULAR_FOOD_HEAL_AMOUNT);
        }
        this.playerNpc.level().playSound(null, this.playerNpc.blockPosition(), SoundEvents.PLAYER_BURP, SoundSource.HOSTILE, 0.5F, 1.0F);
        this.stop();
    }

    private void healDirectly(float amount) {
        if (amount <= 0.0F) {
            return;
        }
        this.playerNpc.setHealth(Math.min(this.playerNpc.getMaxHealth(), this.playerNpc.getHealth() + amount));
    }

    private boolean isHoldingFood() {
        return this.isSameFood(this.playerNpc.getMainHandItem());
    }

    private boolean isSameFood(ItemStack stack) {
        return !stack.isEmpty() && ItemStack.isSameItemSameTags(stack, this.foodStack);
    }

    private void updateEatingMovement() {
        LivingEntity threat = this.playerNpc.getTarget();
        boolean hasThreat = threat != null && threat.isAlive();
        boolean chasing = hasThreat && this.shouldChaseWhileEating(threat);
        Vec3 movePos;
        if (chasing) {
            movePos = threat.position();
        } else if (hasThreat) {
            movePos = this.findFleePos(threat);
        } else {
            movePos = DefaultRandomPos.getPos(this.playerNpc, 8, 4);
        }

        if (movePos == null) {
            return;
        }

        this.playerNpc.setSprinting(false);
        this.playerNpc.getNavigation().moveTo(
                movePos.x,
                movePos.y,
                movePos.z,
                hasThreat ? EATING_COMBAT_SPEED : EATING_WANDER_SPEED
        );

        if (hasThreat) {
            this.playerNpc.getLookControl().setLookAt(threat, 60.0F, 60.0F);
        } else {
            this.playerNpc.getLookControl().setLookAt(movePos.x, movePos.y, movePos.z, 45.0F, 45.0F);
        }
    }

    private boolean shouldChaseWhileEating(LivingEntity threat) {
        double distanceSqr = this.playerNpc.distanceToSqr(threat);
        if (distanceSqr <= CLOSE_COMBAT_DISTANCE_SQR) {
            return false;
        }

        double playerNpcPower = this.combatPower(this.playerNpc) + this.getHealAmount(this.foodStack) * 0.45D;
        double threatPower = this.combatPower(threat);
        if (playerNpcPower < threatPower + CHASE_POWER_MARGIN) {
            return false;
        }

        return distanceSqr >= CHASE_DISTANCE_SQR || this.isThreatMovingAway(threat);
    }

    private boolean isThreatMovingAway(LivingEntity threat) {
        Vec3 movement = threat.getDeltaMovement();
        Vec3 fromNpcToThreat = threat.position().subtract(this.playerNpc.position());
        if (movement.lengthSqr() < 1.0E-4D || fromNpcToThreat.lengthSqr() < 1.0E-4D) {
            return false;
        }

        return movement.normalize().dot(fromNpcToThreat.normalize()) > 0.15D;
    }

    private double combatPower(LivingEntity entity) {
        double score = entity.getHealth() * 0.45D;
        score += entity.getArmorValue() * 0.9D;
        if (entity.getAttribute(Attributes.ATTACK_DAMAGE) != null) {
            score += entity.getAttributeValue(Attributes.ATTACK_DAMAGE) * 1.3D;
        }

        score += this.itemPower(entity.getMainHandItem()) * 1.4D;
        score += this.itemPower(entity.getOffhandItem()) * 0.6D;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.ARMOR) {
                score += this.itemPower(entity.getItemBySlot(slot));
            }
        }
        return score;
    }

    private double itemPower(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }

        double score = 0.0D;
        if (stack.getItem() instanceof SwordItem swordItem) {
            score += swordItem.getDamage();
        } else if (stack.getItem() instanceof AxeItem axeItem) {
            score += axeItem.getAttackDamage();
        } else if (stack.getItem() instanceof DiggerItem) {
            score += 3.0D;
        } else if (stack.getItem() instanceof TridentItem) {
            score += 8.0D;
        } else if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem || stack.getItem() instanceof ProjectileWeaponItem) {
            score += 5.0D;
        }

        if (stack.getItem() instanceof ArmorItem armorItem) {
            score += armorItem.getDefense() * 1.2D;
            score += armorItem.getToughness();
        }
        if (stack.isEnchanted()) {
            score += 2.0D;
        }
        return score;
    }

    private Vec3 findFleePos(LivingEntity threat) {
        Vec3 pos = DefaultRandomPos.getPosAway(this.playerNpc, 14, 6, threat.position());
        if (pos != null) {
            return pos;
        }

        Vec3 away = this.playerNpc.position().subtract(threat.position());
        if (away.lengthSqr() < 1.0E-4D) {
            away = Vec3.directionFromRotation(0.0F, this.playerNpc.getYRot());
        }
        return this.playerNpc.position().add(away.normalize().scale(10.0D));
    }

    private void giveOrDrop(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private float getHealAmount(ItemStack foodStack) {
        if (foodStack.is(Items.ENCHANTED_GOLDEN_APPLE)) {
            return 12.0F;
        }
        if (foodStack.is(Items.GOLDEN_APPLE)) {
            return 8.0F;
        }
        return REGULAR_FOOD_HEAL_AMOUNT;
    }
}
