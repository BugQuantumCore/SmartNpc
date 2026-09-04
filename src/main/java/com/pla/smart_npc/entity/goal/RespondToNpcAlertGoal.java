package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.util.PlayerNpcAlertManager;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

public class RespondToNpcAlertGoal extends Goal {
    private static final double ALERT_RADIUS = 36.0D;
    private static final double AVOID_SPEED = 1.0D;
    private static final int AVOID_TICKS = 80;
    private static final int AVOID_REPATH_TICKS = 20;
    // Alert responses refresh every second, so a short route is enough to make safe progress and
    // can be replaced as the threat moves. The old 16x7/.15 probe expanded a comparatively large
    // synchronous navigation region during Mob.super.tick() and was the only state transition at
    // the trace's 291 ms hottest NPC.
    private static final int AVOID_HORIZONTAL_RANGE = 10;
    private static final int AVOID_VERTICAL_RANGE = 4;
    private static final float PATH_NODE_MULTIPLIER = 0.03F;
    private static final float OVERLOADED_PATH_NODE_MULTIPLIER = 0.01F;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(10);
    private LivingEntity threat;
    private boolean avoiding;
    private int avoidTicks;
    private int repathTicks;

    public RespondToNpcAlertGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.TARGET));
    }

    @Override
    public boolean canUse() {
        if (this.playerNpc.level().isClientSide
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || !this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        this.threat = PlayerNpcAlertManager.getNearbyThreat(this.playerNpc, ALERT_RADIUS).orElse(null);
        return this.threat != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.avoiding
                && this.avoidTicks > 0
                && this.threat != null
                && this.threat.isAlive();
    }

    @Override
    public void start() {
        if (this.threat == null) {
            return;
        }

        if (this.shouldAttack(this.threat)) {
            this.playerNpc.setSprinting(false);
            this.playerNpc.setTarget(this.threat);
            this.playerNpc.setCurrentAiState("ai.player_npc.assisting_alert");
            this.avoiding = false;
            this.avoidTicks = 0;
            return;
        }

        this.avoiding = true;
        this.avoidTicks = AVOID_TICKS;
        this.playerNpc.setSprinting(true);
        this.playerNpc.setTarget(null);
        this.playerNpc.setCurrentAiState("ai.player_npc.avoiding_alert");
        this.moveAway();
        this.repathTicks = AVOID_REPATH_TICKS;
    }

    @Override
    public void tick() {
        this.avoidTicks--;
        if (this.threat == null) {
            return;
        }

        this.playerNpc.setTarget(null);
        this.playerNpc.getLookControl().setLookAt(this.threat, 40.0F, 40.0F);
        if (this.repathTicks-- <= 0) {
            this.moveAway();
            this.repathTicks = AVOID_REPATH_TICKS;
        }
    }

    @Override
    public void stop() {
        this.playerNpc.setSprinting(false);
        this.threat = null;
        this.avoiding = false;
        this.avoidTicks = 0;
        this.repathTicks = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean shouldAttack(LivingEntity threat) {
        if (this.playerNpc.shouldSmartNpcFleeFromTarget(threat)) {
            return false;
        }
        if (this.playerNpc.isSmartNpcCompatHighDangerThreat(threat)
                && !this.playerNpc.shouldSmartNpcAttackTarget(threat)) {
            return false;
        }

        float healthRatio = this.playerNpc.getHealth() / this.playerNpc.getMaxHealth();
        double myPower = this.powerScore(this.playerNpc);
        double threatPower = this.powerScore(threat);
        return healthRatio > 0.55F && (myPower + 4.0D >= threatPower || this.playerNpc.getRandom().nextFloat() < 0.35F);
    }

    private void moveAway() {
        if (this.threat == null) {
            return;
        }

        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            Vec3 awayPos = DefaultRandomPos.getPosAway(
                    this.playerNpc,
                    AVOID_HORIZONTAL_RANGE,
                    AVOID_VERTICAL_RANGE,
                    this.threat.position()
            );
            if (awayPos == null) {
                Vec3 away = this.playerNpc.position().subtract(this.threat.position());
                if (away.lengthSqr() < 1.0E-4D) {
                    away = Vec3.directionFromRotation(0.0F, this.playerNpc.getYRot());
                }
                awayPos = this.playerNpc.position().add(away.normalize().scale(AVOID_HORIZONTAL_RANGE));
            }
            float pathBudget = PlayerNpcPerformanceMonitor.isAiWorkOverloaded()
                    ? OVERLOADED_PATH_NODE_MULTIPLIER
                    : PATH_NODE_MULTIPLIER;
            Path path = PathNavigationAi.createBoundedPath(
                    this.playerNpc,
                    BlockPos.containing(awayPos.x, awayPos.y, awayPos.z),
                    pathBudget
            );
            if (path != null && path.getNodeCount() > 0) {
                this.playerNpc.getNavigation().moveTo(path, AVOID_SPEED);
            }
        } finally {
            // This goal is deliberately not a routine-worker wrapper, so time its only expensive
            // operation directly rather than leaving future alert-route regressions hidden in
            // the residual Mob.super.tick() bucket.
            PlayerNpcPerformanceMonitor.recordGoalWork(
                    this.playerNpc,
                    this.getClass().getSimpleName() + ".moveAway",
                    timing
            );
        }
    }

    private double powerScore(LivingEntity entity) {
        double score = entity.getHealth() * 0.4D + entity.getArmorValue();
        score += itemPower(entity.getMainHandItem()) * 1.4D;
        score += itemPower(entity.getOffhandItem()) * 0.5D;
        for (ItemStack stack : entity.getArmorSlots()) {
            score += itemPower(stack);
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
        }
        if (stack.getItem() instanceof ArmorItem armorItem) {
            score += armorItem.getDefense() + armorItem.getToughness();
        }
        if (stack.isEnchanted()) {
            score += 2.0D;
        }
        return score;
    }
}
