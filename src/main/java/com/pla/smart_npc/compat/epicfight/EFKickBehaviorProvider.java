package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.compat.epicfight.advancedmobpatch.AdvancedCombatBehaviors;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;

/** Builds EFKick behaviors only when EFKick is loaded. */
final class EFKickBehaviorProvider {
    private EFKickBehaviorProvider() {
    }

    static void addTo(AdvancedCombatBehaviors.Builder<MobPatch<?>> builder) {
        builder.newBehaviorRoot(
                AdvancedCombatBehaviors.BehaviorRoot.builder()
                        .priority(1.0D)
                        .weight(5.0D)
                        .maxCooldown(200)
                        .waitForAnimationCompletion()
                        .addFirstBehavior(
                                AdvancedCombatBehaviors.Behavior.builder()
                                        .withinDistance(0.0D, 3.0D)
                                        .animationBehavior(EFKick.getEFKick1(), 0.0F)
                        )
                        .addFirstBehavior(
                                AdvancedCombatBehaviors.Behavior.builder()
                                        .withinDistance(0.0D, 3.0D)
                                        .animationBehavior(EFKick.getEFKick2(), 0.0F)
                        )
                        .addFirstBehavior(
                                AdvancedCombatBehaviors.Behavior.builder()
                                        .withinDistance(0.0D, 3.0D)
                                        .animationBehavior(EFKick.getEFKick3(), 0.0F)
                        )
                        .addFirstBehavior(
                                AdvancedCombatBehaviors.Behavior.builder()
                                        .withinDistance(0.0D, 3.0D)
                                        .animationBehavior(EFKick.getEFKick4(), 0.0F)
                        )
                        .addFirstBehavior(
                                AdvancedCombatBehaviors.Behavior.builder()
                                        .withinDistance(0.0D, 3.0D)
                                        .animationBehavior(EFKick.getEFKickH(), 0.0F)
                        )
                        .addFirstBehavior(
                                AdvancedCombatBehaviors.Behavior.builder()
                                        .withinDistance(0.0D, 3.0D)
                                        .animationBehavior(EFKick.getEFKickC(), 0.0F)
                        )
                        .addFirstBehavior(
                                AdvancedCombatBehaviors.Behavior.builder()
                                        .withinDistance(0.0D, 3.0D)
                                        .animationBehavior(EFKick.getEFKickRush(), 0.0F)
                        )
                        .addFirstBehavior(
                                AdvancedCombatBehaviors.Behavior.builder()
                                        .withinDistance(0.0D, 3.0D)
                                        .animationBehavior(EFKick.getEFKickCombo(), 0.0F)
                        )
        );
    }
}
