package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.compat.epicfight.advancedmobpatch.AdvancedCombatBehaviors;
import com.pla.smart_npc.compat.epicfight.advancedmobpatch.AdvancedMobPatch;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.PathfinderMob;
import yesman.epicfight.api.animation.Animator;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.world.capabilities.entitypatch.Factions;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.capabilities.item.Style;

import java.util.List;

public class AdvancedPlayerNpcPatch<T extends PathfinderMob> extends AdvancedMobPatch<T> {
    public AdvancedPlayerNpcPatch() {
        super(Factions.NEUTRAL);
    }

    @Override
    protected void initAnimator(Animator animator) {
        super.initAnimator(animator);
        animator.addLivingAnimation(LivingMotions.IDLE, Animations.BIPED_IDLE);
        animator.addLivingAnimation(LivingMotions.WALK, Animations.BIPED_WALK);
        animator.addLivingAnimation(LivingMotions.RUN, Animations.BIPED_RUN);
        animator.addLivingAnimation(LivingMotions.CHASE, Animations.BIPED_RUN);
        animator.addLivingAnimation(LivingMotions.SNEAK, Animations.BIPED_SNEAK);
        animator.addLivingAnimation(LivingMotions.KNEEL, Animations.BIPED_KNEEL);
        animator.addLivingAnimation(LivingMotions.FALL, Animations.BIPED_FALL);
        animator.addLivingAnimation(LivingMotions.MOUNT, Animations.BIPED_MOUNT);
        animator.addLivingAnimation(LivingMotions.SLEEP, Animations.BIPED_SLEEPING);
        animator.addLivingAnimation(LivingMotions.DEATH, Animations.BIPED_DEATH);
        if (EpicFightCloneAnimations.DIG_MAINHAND != null) {
            animator.addLivingAnimation(LivingMotions.DIGGING, EpicFightCloneAnimations.DIG_MAINHAND);
        }
    }

    @Override
    protected void addCustomBehaviorRoots(AdvancedCombatBehaviors.Builder<MobPatch<?>> builder,
                                          CapabilityItem mainHandCap,
                                          CapabilityItem offHandCap, Style style) {
        builder
                .newBehaviorRoot(
                        AdvancedCombatBehaviors.BehaviorRoot.builder()
                                .priority(4.0D)
                                .weight(1000.0D)
                                .maxCooldown(0)
                                .waitForAnimationCompletion()
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .custom(CombatEvolution::canExecute)
                                                .withinDistance(0.0D, 5.0D)
                                                .animationBehavior(Animations.BIPED_SNEAK, 0.0F)
                                                .addExBehavior(CombatEvolution::performExecute)
                                )
                )
                .newBehaviorRoot(
                        AdvancedCombatBehaviors.BehaviorRoot.builder()
                                .priority(1.0D)
                                .weight(10.0D)
                                .maxCooldown(80)
                                .waitForAnimationCompletion()
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .withinDistance(0.0D, 5.0D)
                                                .animationBehavior(Animations.BIPED_ROLL_BACKWARD, 0.0F)
                                )
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .withinDistance(0.0D, 5.0D)
                                                .animationBehavior(Animations.BIPED_ROLL_BACKWARD, 0.0F)
                                )
                )
                .newBehaviorRoot(
                        AdvancedCombatBehaviors.BehaviorRoot.builder()
                                .priority(1.0D)
                                .weight(5.0D)
                                .maxCooldown(200)
                                .waitForAnimationCompletion()
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .custom(EFKick::isEfKickInstalled)
                                                .withinDistance(0.0D, 3.0D)
                                                .animationBehavior(EFKick.getEFKick1(), 0.0F)
                                )
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .custom(EFKick::isEfKickInstalled)
                                                .withinDistance(0.0D, 3.0D)
                                                .animationBehavior(EFKick.getEFKick2(), 0.0F)
                                )
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .custom(EFKick::isEfKickInstalled)
                                                .withinDistance(0.0D, 3.0D)
                                                .animationBehavior(EFKick.getEFKick3(), 0.0F)
                                )
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .custom(EFKick::isEfKickInstalled)
                                                .withinDistance(0.0D, 3.0D)
                                                .animationBehavior(EFKick.getEFKick4(), 0.0F)
                                )
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .custom(EFKick::isEfKickInstalled)
                                                .withinDistance(0.0D, 3.0D)
                                                .animationBehavior(EFKick.getEFKickH(), 0.0F)
                                )
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .custom(EFKick::isEfKickInstalled)
                                                .withinDistance(0.0D, 3.0D)
                                                .animationBehavior(EFKick.getEFKickC(), 0.0F)
                                )
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .custom(EFKick::isEfKickInstalled)
                                                .withinDistance(0.0D, 3.0D)
                                                .animationBehavior(EFKick.getEFKickRush(), 0.0F)
                                )
                                .addFirstBehavior(
                                        AdvancedCombatBehaviors.Behavior.builder()
                                                .custom(EFKick::isEfKickInstalled)
                                                .withinDistance(0.0D, 3.0D)
                                                .animationBehavior(EFKick.getEFKickCombo(), 0.0F)
                                )
                );
    }

    @Override
    public boolean canGuard() {
        return true;
    }

    @Override
    public int getGuardChance() {
        return 12;
    }

    @Override
    protected List<AdditionalAttackGroup> getAdditionalAttackGroups(CapabilityItem mainHandCap, CapabilityItem offHandCap, Style style) {
        if (mainHandCap.getWeaponCategory() == CapabilityItem.WeaponCategories.SWORD) {
            return style == CapabilityItem.Styles.TWO_HAND
                    ? List.of(AdditionalAttackGroup.random(0.25F, Animations.SWEEPING_EDGE))
                    : List.of(AdditionalAttackGroup.random(0.25F, Animations.DANCING_EDGE)
            );
        }
        if (mainHandCap.getWeaponCategory() == CapabilityItem.WeaponCategories.AXE) {
            return List.of(AdditionalAttackGroup.random(0.25F, Animations.THE_GUILLOTINE));
        }
        if (mainHandCap.getWeaponCategory() == CapabilityItem.WeaponCategories.SPEAR) {
            return style == CapabilityItem.Styles.TWO_HAND
                    ? List.of(AdditionalAttackGroup.random(0.25F, Animations.GRASPING_SPIRAL_FIRST, Animations.GRASPING_SPIRAL_SECOND))
                    : List.of(AdditionalAttackGroup.random(0.25F, Animations.HEARTPIERCER)
            );
        }
        if (mainHandCap.getWeaponCategory() == CapabilityItem.WeaponCategories.GREATSWORD) {
            return List.of(AdditionalAttackGroup.random(0.25F, Animations.STEEL_WHIRLWIND));
        }
        if (mainHandCap.getWeaponCategory() == CapabilityItem.WeaponCategories.UCHIGATANA) {
            return List.of(AdditionalAttackGroup.random(0.25F, Animations.BATTOJUTSU, Animations.BATTOJUTSU_DASH));
        }
        if (mainHandCap.getWeaponCategory() == CapabilityItem.WeaponCategories.LONGSWORD) {
            return List.of(AdditionalAttackGroup.random(0.25F, Animations.SHARP_STAB));
        }
        if (mainHandCap.getWeaponCategory() == CapabilityItem.WeaponCategories.DAGGER) {
            return style == CapabilityItem.Styles.TWO_HAND
                    ? List.of(AdditionalAttackGroup.random(0.25F, Animations.BLADE_RUSH_COMBO1, Animations.BLADE_RUSH_COMBO2, Animations.BLADE_RUSH_COMBO3))
                    : List.of(AdditionalAttackGroup.random(0.25F, Animations.EVISCERATE_FIRST, Animations.EVISCERATE_SECOND)
            );
        }
        if (mainHandCap.getWeaponCategory() == CapabilityItem.WeaponCategories.FIST) {
            return List.of(AdditionalAttackGroup.random(0.25F, Animations.RELENTLESS_COMBO));
        }
        return super.getAdditionalAttackGroups(mainHandCap, offHandCap, style);
    }

    @Override
    public void updateMotion(boolean considerInaction) {
        super.updateMotion(considerInaction);
        if (this.getOriginal() instanceof PlayerNpcEntity playerNpc) {
            if (playerNpc.isSleeping()) {
                this.currentLivingMotion = LivingMotions.SLEEP;
                this.currentCompositeMotion = LivingMotions.SLEEP;
            } else if (playerNpc.isEpicFightDigging()) {
                this.currentLivingMotion = LivingMotions.DIGGING;
                this.currentCompositeMotion = LivingMotions.DIGGING;
            } else if ((playerNpc.isShiftKeyDown() || playerNpc.isCrouching()) && canApplyCrouchMotion()) {
                this.currentLivingMotion = isMovingMotion() ? LivingMotions.SNEAK : LivingMotions.KNEEL;
                this.currentCompositeMotion = this.currentLivingMotion;
            } else if (playerNpc.isSprinting() && isMovingMotion()) {
                this.currentLivingMotion = LivingMotions.RUN;
                this.currentCompositeMotion = LivingMotions.RUN;
            }
        }
    }

    private boolean canApplyCrouchMotion() {
        return this.currentLivingMotion == LivingMotions.IDLE
                || this.currentLivingMotion == LivingMotions.WALK
                || this.currentLivingMotion == LivingMotions.RUN
                || this.currentLivingMotion == LivingMotions.CHASE;
    }

    private boolean isMovingMotion() {
        return this.currentLivingMotion == LivingMotions.WALK
                || this.currentLivingMotion == LivingMotions.RUN
                || this.currentLivingMotion == LivingMotions.CHASE;
    }
}
