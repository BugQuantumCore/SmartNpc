package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Defers routine work while an integrated/dedicated server is still restoring its world.
 * Combat, targeting, fleeing, healing, and emergency movement are intentionally not wrapped.
 */
public final class StartupWorkGatedGoal extends Goal {
    public static final int SERVER_STARTUP_GRACE_TICKS = 60;
    public static final int MAX_RELEASE_STAGGER_TICKS = 60;
    public static final int ROUTINE_PROBE_SLICES = 4;

    private final PlayerNpcEntity playerNpc;
    private final Goal delegate;
    private final int releaseServerTick;
    private final int predicateSlice;

    public StartupWorkGatedGoal(PlayerNpcEntity playerNpc, Goal delegate, int registrationIndex) {
        this.playerNpc = playerNpc;
        this.delegate = delegate;
        this.releaseServerTick = SERVER_STARTUP_GRACE_TICKS
                + Math.floorMod(playerNpc.getUUID().hashCode(), MAX_RELEASE_STAGGER_TICKS + 1);
        this.predicateSlice = Math.floorMod(registrationIndex, ROUTINE_PROBE_SLICES);
        this.setFlags(delegate.getFlags());
    }

    public Goal getDelegateGoal() {
        return this.delegate;
    }

    private boolean isStartupGraceComplete() {
        MinecraftServer server = this.playerNpc.level().getServer();
        return server != null && server.getTickCount() > this.releaseServerTick;
    }

    @Override
    public boolean canUse() {
        if (!this.isStartupGraceComplete()
                || !PlayerNpcAiWorkBudget.canStartWork(
                this.playerNpc,
                this.predicateSlice,
                ROUTINE_PROBE_SLICES
        )) {
            return false;
        }
        // Advance from actual resource-owned selector opportunities, not absolute entity time.
        // GoalSelector's new-goal cadence plus the server-wide probe queue can repeatedly grant an
        // NPC only a subset of tickCount modulo phases, starving an absolute-time slice forever.
        if (this.delegate instanceof InterestGatedGoal interestGoal
                && !interestGoal.isInterestGateActive()) {
            return false;
        }
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            return this.delegate.canUse();
        } finally {
            this.recordGoalWork("canUse", timing);
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (!this.isStartupGraceComplete()
                || !PlayerNpcAiWorkBudget.canContinueWork(this.playerNpc)) {
            return false;
        }
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            return this.delegate.canContinueToUse();
        } finally {
            this.recordGoalWork("canContinue", timing);
        }
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
        PlayerNpcAiWorkBudget.onWorkStarted(this.playerNpc);
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.delegate.start();
        } finally {
            this.recordGoalWork("start", timing);
        }
    }

    @Override
    public void stop() {
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.delegate.stop();
        } finally {
            this.recordGoalWork("stop", timing);
            PlayerNpcAiWorkBudget.onWorkStopped(this.playerNpc);
        }
    }

    @Override
    public void tick() {
        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.delegate.tick();
        } finally {
            this.recordGoalWork("tick", timing);
        }
    }

    private void recordGoalWork(String operation, long timing) {
        // InterestGatedGoal already measures its delegate. Recording this outer wrapper as well
        // would double-count the same elapsed time in wrappedGoalTotalMs/calls.
        if (this.delegate instanceof InterestGatedGoal) {
            return;
        }
        PlayerNpcPerformanceMonitor.recordGoalWork(
                this.playerNpc,
                this.delegate.getClass().getSimpleName() + "." + operation,
                timing
        );
    }
}
