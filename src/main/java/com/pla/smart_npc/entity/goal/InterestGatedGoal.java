package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.Arrays;
import java.util.List;

public class InterestGatedGoal extends Goal {
    private final PlayerNpcEntity playerNpc;
    private final Goal delegate;
    private final List<PlayerNpcInterest> interests;

    public InterestGatedGoal(PlayerNpcEntity playerNpc, Goal delegate, PlayerNpcInterest... interests) {
        this.playerNpc = playerNpc;
        this.delegate = delegate;
        this.interests = List.copyOf(Arrays.asList(interests));
        this.setFlags(delegate.getFlags());
    }

    public Goal getDelegateGoal() {
        return this.delegate;
    }

    @Override
    public boolean canUse() {
        return this.playerNpc.isInterestGateActive(this.interests) && this.delegate.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return this.playerNpc.isInterestGateActive(this.interests) && this.delegate.canContinueToUse();
    }

    @Override
    public boolean isInterruptable() {
        return this.delegate.isInterruptable();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return this.delegate.requiresUpdateEveryTick();
    }

    @Override
    public void start() {
        this.delegate.start();
    }

    @Override
    public void stop() {
        this.delegate.stop();
    }

    @Override
    public void tick() {
        this.delegate.tick();
    }
}
