/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Goal behavior adapted from Combat Evolution by ShelMarow.
 */
package com.pla.smart_npc.compat.epicfight.advancedmobpatch;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;

import java.util.function.BooleanSupplier;

public final class AdvancedAnimationAttackGoal<T extends MobPatch<?>> extends Goal {
    private final T mobPatch;
    private final AdvancedCombatBehaviors<T> combatBehaviors;
    private final BooleanSupplier actionAllowed;
    private final BooleanSupplier tryStartGuard;

    public AdvancedAnimationAttackGoal(
            T mobPatch,
            AdvancedCombatBehaviors<T> combatBehaviors,
            BooleanSupplier actionAllowed,
            BooleanSupplier tryStartGuard
    ) {
        this.mobPatch = mobPatch;
        this.combatBehaviors = combatBehaviors;
        this.actionAllowed = actionAllowed;
        this.tryStartGuard = tryStartGuard;
    }

    @Override
    public boolean canUse() {
        boolean finishingAction = this.combatBehaviors.getCurrentBehavior() != null
                && !this.mobPatch.getEntityState().inaction();
        return this.actionAllowed.getAsBoolean() && (this.hasValidTarget() || finishingAction);
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public void tick() {
        if (!this.actionAllowed.getAsBoolean() || !this.hasValidTarget()) {
            this.combatBehaviors.clearCurrentBehavior();
            return;
        }
        // Give a locally configured guard the same opening that an attack root
        // would otherwise claim immediately. The patch only permits this while
        // no behavior or animation is active, so an attack is never interrupted.
        if (this.tryStartGuard.getAsBoolean()) {
            this.combatBehaviors.clearCurrentBehavior();
            return;
        }
        this.combatBehaviors.tick(this.mobPatch);
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private boolean hasValidTarget() {
        LivingEntity target = this.mobPatch.getTarget();
        if (target == null || !target.isAlive()) {
            return false;
        }
        return !(target instanceof Player player) || (!player.isSpectator() && !player.isCreative());
    }
}
