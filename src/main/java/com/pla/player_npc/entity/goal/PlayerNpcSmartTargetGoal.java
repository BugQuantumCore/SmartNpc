package com.pla.player_npc.entity.goal;

import com.pla.player_npc.clazz.PlayerNpcTarget;
import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.EnumSet;

public class PlayerNpcSmartTargetGoal extends TargetGoal {
    private static final int SCAN_INTERVAL_TICKS = 20;
    private static final double STRONGER_TARGET_MARGIN = 7.0D;
    private static final double HEALTHY_RATIO = 0.55D;
    private static final float RARE_VILLAGER_ATTACK_CHANCE = 0.02F;

    private final PlayerNpcEntity playerNpc;
    private final TargetingConditions targetConditions;
    @Nullable
    private LivingEntity nextTarget;
    private String nextState = PlayerNpcEntity.AI_IDLE;

    public PlayerNpcSmartTargetGoal(PlayerNpcEntity playerNpc) {
        super(playerNpc, true, false);
        this.playerNpc = playerNpc;
        this.targetConditions = TargetingConditions.forCombat().range(this.getFollowDistance());
        this.setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (this.playerNpc.level().isClientSide
                || this.playerNpc.isNoAi()
                || this.playerNpc.isHealing()) {
            return false;
        }

        LivingEntity currentTarget = this.playerNpc.getTarget();
        if (currentTarget != null && currentTarget.isAlive() && this.canAttack(currentTarget, this.targetConditions)) {
            return false;
        }
        if (currentTarget != null) {
            this.playerNpc.setTarget(null);
            if (this.isTargetCombatState(this.playerNpc.getCurrentAiState())) {
                this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
            }
        }

        if (this.playerNpc.tickCount % SCAN_INTERVAL_TICKS != 0) {
            return false;
        }

        this.nextTarget = null;
        this.nextState = PlayerNpcEntity.AI_IDLE;
        this.nextTarget = this.findTarget();
        return this.nextTarget != null;
    }

    @Override
    public void start() {
        this.playerNpc.setTarget(this.nextTarget);
        this.playerNpc.setCurrentAiState(this.nextState);
        if (this.nextTarget != null) {
            this.playerNpc.setCurrentAiDetail("target: " + this.nextTarget.getDisplayName().getString());
        }
        super.start();
    }

    @Override
    public boolean canContinueToUse() {
        return false;
    }

    @Override
    public void stop() {
        this.nextTarget = null;
        this.nextState = PlayerNpcEntity.AI_IDLE;
    }

    @Nullable
    private LivingEntity findTarget() {
        double followDistance = this.getFollowDistance();
        AABB searchBox = this.playerNpc.getBoundingBox().inflate(followDistance, 6.0D, followDistance);
        LivingEntity bestTarget = null;
        double bestScore = 0.0D;
        String bestState = PlayerNpcEntity.AI_IDLE;

        for (LivingEntity candidate : this.playerNpc.level().getEntitiesOfClass(LivingEntity.class, searchBox, this::isCandidate)) {
            double score = this.scoreTarget(candidate);
            if (score <= 0.0D || score <= bestScore) {
                continue;
            }
            if (!this.canAttack(candidate, this.targetConditions)) {
                continue;
            }

            bestTarget = candidate;
            bestScore = score;
            bestState = this.stateFor(candidate);
        }

        this.nextState = bestState;
        return bestTarget;
    }

    private boolean isCandidate(LivingEntity candidate) {
        return candidate != this.playerNpc
                && candidate.isAlive()
                && !candidate.isSpectator()
                && !this.playerNpc.isAlliedTo(candidate)
                && !candidate.isAlliedTo(this.playerNpc)
                && (candidate instanceof Player
                || candidate instanceof PlayerNpcEntity
                || candidate instanceof Monster
                || candidate instanceof AbstractIllager
                || candidate instanceof Villager
                || candidate instanceof Animal);
    }

    private double scoreTarget(LivingEntity candidate) {
        PlayerNpcTarget personality = this.playerNpc.getNpcTargetPersonality();
        double healthRatio = this.playerNpc.getHealth() / this.playerNpc.getMaxHealth();
        double distancePenalty = this.playerNpc.distanceTo(candidate) * 0.35D;
        double score = this.playerNpc.getRandom().nextDouble() * 3.0D - distancePenalty;

        if (candidate instanceof Player || candidate instanceof PlayerNpcEntity) {
            if (this.isClearlyOutmatched(candidate)) {
                return 0.0D;
            }
            score += 8.0D;
            if (personality == PlayerNpcTarget.PLAYER_HUNTER || personality == PlayerNpcTarget.HOSTILE_HUNTER) {
                score += 7.0D;
            }
            if (healthRatio < HEALTHY_RATIO) {
                score -= 6.0D;
            }
        } else if (candidate instanceof Monster || candidate instanceof AbstractIllager) {
            if (healthRatio < 0.45D && this.powerScore(candidate) > this.powerScore(this.playerNpc)) {
                return 0.0D;
            }
            score += 9.0D;
            if (personality == PlayerNpcTarget.MONSTER_HUNTER || personality == PlayerNpcTarget.HOSTILE_HUNTER) {
                score += 8.0D;
            }
        } else if (candidate instanceof Animal) {
            if (this.playerNpc.hasAnimalLootPriority()) {
                return 0.0D;
            }
            if (this.playerNpc.shouldPrioritizeLogGathering()) {
                return 0.0D;
            }
            boolean needsFood = !InventoryUtils.hasHealingFood(this.playerNpc);
            if (!needsFood) {
                return 0.0D;
            }
            if (this.hasNearbyCollectableSupplyDrop()) {
                return 0.0D;
            }
            score += 12.0D;
            if (personality == PlayerNpcTarget.ANIMAL_HUNTER || personality == PlayerNpcTarget.HOSTILE_HUNTER) {
                score += 5.0D;
            }
        } else if (candidate instanceof Villager) {
            if (personality != PlayerNpcTarget.VILLAGER_HUNTER && personality != PlayerNpcTarget.HOSTILE_HUNTER) {
                return 0.0D;
            }
            if (this.playerNpc.getRandom().nextFloat() > RARE_VILLAGER_ATTACK_CHANCE) {
                return 0.0D;
            }
            score += 3.0D;
        }

        if (personality == PlayerNpcTarget.PASSIVE_HUNTER) {
            score -= 5.0D;
        }

        return Math.max(0.0D, score);
    }

    private boolean hasNearbyCollectableSupplyDrop() {
        return this.playerNpc.hasCollectableSupplyDropNearby(24.0D);
    }

    private boolean isClearlyOutmatched(LivingEntity candidate) {
        return this.powerScore(candidate) > this.powerScore(this.playerNpc) + STRONGER_TARGET_MARGIN;
    }

    private double powerScore(LivingEntity entity) {
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

    private String stateFor(LivingEntity candidate) {
        if (candidate instanceof Player || candidate instanceof PlayerNpcEntity) {
            return "ai.player_npc.engaging_player_like";
        }
        if (candidate instanceof Monster || candidate instanceof AbstractIllager) {
            return "ai.player_npc.engaging_monster";
        }
        if (candidate instanceof Animal) {
            return "ai.player_npc.hunting_animal";
        }
        if (candidate instanceof Villager) {
            return "ai.player_npc.engaging_villager";
        }
        return "ai.player_npc.engaging";
    }

    private boolean isTargetCombatState(String state) {
        return "ai.player_npc.engaging".equals(state)
                || "ai.player_npc.engaging_player_like".equals(state)
                || "ai.player_npc.engaging_monster".equals(state)
                || "ai.player_npc.hunting_animal".equals(state)
                || "ai.player_npc.engaging_villager".equals(state)
                || "ai.player_npc.melee_attacking".equals(state)
                || "ai.player_npc.ranged_bow".equals(state)
                || "ai.player_npc.throwing_ender_pearl".equals(state)
                || "ai.player_npc.combat_fishing".equals(state)
                || "ai.player_npc.shield_guarding".equals(state)
                || "ai.player_npc.troll_hit".equals(state)
                || "ai.player_npc.using_lava_bucket".equals(state)
                || "ai.player_npc.blocking_projectile".equals(state);
    }
}
