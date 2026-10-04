package com.pla.smart_npc.entity.goal;

import net.fabricmc.loader.api.FabricLoader;
import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.VanillaMeleeAttackAi;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.util.EnumSet;

/** One vanilla attack owner: bounded pursuit, varied swing cadence, and physical jump crits. */
public final class PlayerNpcMeleeAttackGoal extends Goal {
    private final PlayerNpcEntity npc;
    private int nextPathTick;
    private int nextAttackTick;
    private int burstSwings;
    private BlockPos lastPathTarget;
    private LivingEntity criticalTarget;
    private int criticalJumpTick;
    private boolean wasSprinting;

    public PlayerNpcMeleeAttackGoal(PlayerNpcEntity npc) {
        this.npc = npc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = this.npc.getTarget();
        return !this.npc.level().isClientSide()
                && this.npc.isAlive() && !this.npc.isNoAi() && !this.npc.isPassenger()
                && !this.npc.isHealing() && !this.npc.isUsingItem() && !this.npc.isSleeping()
                && !this.npc.hasInterest(PlayerNpcInterest.CAUTIOUS)
                && target != null && target.isAlive() && !target.isRemoved()
                && !this.npc.isAlliedTo(target) && !target.isAlliedTo(this.npc)
                && (!(target instanceof Player player) || !player.isCreative() && !player.isSpectator());
    }

    @Override
    public boolean canContinueToUse() {
        return this.canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        // Only look, timers, and hit/jump readiness are per-tick. Path planning is >=20 ticks.
        return true;
    }

    @Override
    public void start() {
        this.npc.setAggressive(true);
        this.npc.setCurrentAiState("ai.player_npc.melee_attacking");
    }

    @Override
    public void stop() {
        this.cancelCriticalJump();
        this.burstSwings = 0;
        this.npc.setAggressive(false);
        this.npc.getNavigation().stop();
        this.lastPathTarget = null;
        this.npc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.npc.setCurrentAiDetail("");
        // Preserve the attack deadline across utility preemption, preventing free extra swings.
    }

    @Override
    public void tick() {
        if (!this.canContinueToUse()) {
            return;
        }
        LivingEntity target = this.npc.getTarget();
        this.npc.getLookControl().setLookAt(target, 30.0F, 30.0F);
        boolean inReach = this.npc.distanceToSqr(target) <= 9.0D
                && this.npc.getSensing().hasLineOfSight(target);
        this.updatePursuit(target, inReach);

        if (this.criticalTarget != null) {
            if (target == this.criticalTarget && inReach && this.canCritNow()) {
                this.swing(target, true);
                this.cancelCriticalJump();
            } else if (target != this.criticalTarget || this.npc.tickCount - this.criticalJumpTick >= 20
                    || this.npc.onGround() && this.npc.tickCount - this.criticalJumpTick > 2) {
                this.cancelCriticalJump();
                this.nextAttackTick = Math.max(this.nextAttackTick, this.npc.tickCount + 3);
            }
            return;
        }
        if (!inReach || this.npc.tickCount < this.nextAttackTick) {
            return;
        }
        if (this.npc.onGround() && this.canJumpCrit() && this.npc.getRandom().nextFloat() < 0.20F) {
            this.criticalTarget = target;
            this.criticalJumpTick = this.npc.tickCount;
            this.wasSprinting = this.npc.isSprinting();
            this.npc.setSprinting(false);
            this.npc.getJumpControl().jump();
            this.npc.setCurrentAiDetail("jumping for critical hit");
            return;
        }
        this.swing(target, false);
    }

    private boolean canJumpCrit() {
        return !this.npc.isInWaterOrBubble() && !this.npc.isInLava() && !this.npc.onClimbable()
                && !this.npc.hasEffect(MobEffects.BLINDNESS) && !this.npc.hasEffect(MobEffects.LEVITATION);
    }

    private boolean canCritNow() {
        return this.canJumpCrit() && !this.npc.onGround() && this.npc.fallDistance > 0.0F
                && this.npc.getDeltaMovement().y < 0.0D;
    }

    private void cancelCriticalJump() {
        if (this.criticalTarget != null) {
            this.npc.setSprinting(this.wasSprinting);
            this.criticalTarget = null;
        }
    }

    private void swing(LivingEntity target, boolean critical) {
        this.npc.swing(InteractionHand.MAIN_HAND, true);
        VanillaMeleeAttackAi.attack(this.npc, target, critical);
        boolean fastFistAttack = false;
        int interval;
        if (this.npc.getMainHandItem().isEmpty()) {
            if (this.burstSwings == 0 && this.npc.getRandom().nextFloat() < 0.30F) {
                this.burstSwings = 2 + this.npc.getRandom().nextInt(3);
            }
            fastFistAttack = this.burstSwings > 0;
            interval = fastFistAttack
                    ? 3 + this.npc.getRandom().nextInt(4)
                    : 8 + this.npc.getRandom().nextInt(9);
            if (fastFistAttack) {
                this.burstSwings--;
            }
        } else {
            this.burstSwings = 0;
            interval = VanillaMeleeAttackAi.weaponAttackIntervalTicks(this.npc.getMainHandItem());
        }
        this.nextAttackTick = this.npc.tickCount + interval;
        this.npc.setCurrentAiDetail(
                critical ? "critical hit" : fastFistAttack ? "fast unarmed swings" : "melee attacking");
    }

    private void updatePursuit(LivingEntity target, boolean inReach) {
        if (inReach) {
            this.npc.getNavigation().stop();
            return;
        }
        if (this.npc.tickCount < this.nextPathTick) {
            return;
        }
        this.nextPathTick = this.npc.tickCount + 20 + this.npc.getRandom().nextInt(6);
        BlockPos targetPos = target.blockPosition();
        if (!this.npc.getNavigation().isDone() && this.lastPathTarget != null
                && this.lastPathTarget.distSqr(targetPos) <= 4.0D) {
            return;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquireNavigationPathStart(this.npc)) {
            return;
        }
        var path = PathNavigationAi.createBoundedPath(this.npc, targetPos, 0.15F);
        this.lastPathTarget = targetPos.immutable();
        if (path != null) {
            this.npc.getNavigation().moveTo(path, 1.0D);
        }
    }
}
