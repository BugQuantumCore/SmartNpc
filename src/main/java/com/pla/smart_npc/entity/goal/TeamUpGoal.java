package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.ChatUtil;
import com.pla.smart_npc.util.PlayerNpcTeamUpManager;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.EnumSet;

/** A bounded, non-worker social invitation that visibly waits for an exact acceptance. */
public final class TeamUpGoal extends Goal {
    private static final int INVITATION_CHECK_INTERVAL_TICKS = 20 * 5;
    private static final int MIN_WAIT_TICKS = 20 * 20;
    private static final int MAX_WAIT_TICKS = 20 * 30;
    private static final int RETRY_COOLDOWN_TICKS = 20 * 30;

    private final PlayerNpcEntity playerNpc;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(INVITATION_CHECK_INTERVAL_TICKS);
    @Nullable
    private LivingEntity inviteTarget;
    private Gesture gesture = Gesture.LOOK_AROUND;
    private int waitTicks;
    private int npcResponseTick;
    private int gestureTicks;
    private boolean invitationStarted;

    public TeamUpGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!this.playerNpc.hasInterest(PlayerNpcInterest.TEAMUP)
                || this.playerNpc.isTeamFollower()
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.isTeamUpRequestPending()
                || !this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        this.inviteTarget = PlayerNpcTeamUpManager.findInviteCandidate(this.playerNpc);
        return this.inviteTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.invitationStarted
                && this.waitTicks > 0
                && this.inviteTarget != null
                && PlayerNpcTeamUpManager.canContinueInvitation(this.playerNpc, this.inviteTarget);
    }

    @Override
    public void start() {
        if (this.inviteTarget == null) {
            return;
        }
        this.waitTicks = this.playerNpc.getRandom().nextInt(MIN_WAIT_TICKS, MAX_WAIT_TICKS + 1);
        this.npcResponseTick = 40 + this.playerNpc.getRandom().nextInt(61);
        this.gesture = Gesture.values()[this.playerNpc.getRandom().nextInt(Gesture.values().length)];
        this.gestureTicks = 0;
        this.invitationStarted = true;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState("ai.player_npc.awaiting_teamup_reply");
        this.updateDetail();
        PlayerNpcTeamUpManager.beginInvitation(
                this.playerNpc,
                this.inviteTarget,
                this.playerNpc.getServer().getTickCount() + this.waitTicks
        );
        ChatUtil.teamUpGreeting(this.playerNpc, this.inviteTarget);
    }

    @Override
    public void tick() {
        if (this.inviteTarget == null) {
            return;
        }
        this.waitTicks--;
        this.gestureTicks++;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(this.inviteTarget, 35.0F, 35.0F);
        this.performGesture();

        if (this.inviteTarget instanceof PlayerNpcEntity npcTarget
                && this.gestureTicks >= this.npcResponseTick
                && PlayerNpcTeamUpManager.acceptNpcResponse(this.playerNpc, npcTarget)) {
            this.waitTicks = 0;
        }
    }

    @Override
    public void stop() {
        this.playerNpc.setShiftKeyDown(false);
        PlayerNpcTeamUpManager.cancelInvitation(this.playerNpc, this.inviteTarget);
        if (!this.playerNpc.isTeamFollower()) {
            this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
            this.playerNpc.setCurrentAiDetail("");
        }
        this.canUseThrottle.retryIn(
                this.playerNpc,
                RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 15 + 1)
        );
        this.invitationStarted = false;
        this.inviteTarget = null;
        this.waitTicks = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    private void performGesture() {
        switch (this.gesture) {
            case SNEAK -> this.playerNpc.setShiftKeyDown(this.gestureTicks >= 20 && this.gestureTicks < 40);
            case REPEATED_SNEAK -> this.playerNpc.setShiftKeyDown((this.gestureTicks / 12) % 2 == 0);
            case JUMP -> {
                if (this.gestureTicks == 25 && this.playerNpc.onGround()) {
                    this.playerNpc.getJumpControl().jump();
                }
            }
            case REPEATED_JUMP -> {
                if (this.gestureTicks % 35 == 0 && this.playerNpc.onGround()) {
                    this.playerNpc.getJumpControl().jump();
                }
            }
            case LOOK_AROUND -> {
                if (this.gestureTicks % 35 == 0) {
                    Vec3 position = this.playerNpc.position();
                    double x = position.x + this.playerNpc.getRandom().nextDouble() * 12.0D - 6.0D;
                    double z = position.z + this.playerNpc.getRandom().nextDouble() * 12.0D - 6.0D;
                    this.playerNpc.getLookControl().setLookAt(x, position.y + 1.5D, z, 25.0F, 25.0F);
                }
            }
        }
    }

    private void updateDetail() {
        if (this.inviteTarget != null) {
            this.playerNpc.setCurrentAiDetail("waiting for " + this.inviteTarget.getDisplayName().getString());
        }
    }

    private enum Gesture {
        SNEAK,
        REPEATED_SNEAK,
        JUMP,
        REPEATED_JUMP,
        LOOK_AROUND
    }
}
