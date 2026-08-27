package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Defers routine work while an integrated/dedicated server is still restoring its world.
 * Combat, targeting, fleeing, healing, and emergency movement are intentionally not wrapped.
 */
public final class StartupWorkGatedGoal extends Goal {
    public static final int SERVER_STARTUP_GRACE_TICKS = 60;
    public static final int MAX_RELEASE_STAGGER_TICKS = 60;

    private final PlayerNpcEntity playerNpc;
    private final Goal delegate;
    private final int releaseServerTick;

    public StartupWorkGatedGoal(PlayerNpcEntity playerNpc, Goal delegate) {
        this.playerNpc = playerNpc;
        this.delegate = delegate;
        this.releaseServerTick = SERVER_STARTUP_GRACE_TICKS
                + Math.floorMod(playerNpc.getUUID().hashCode(), MAX_RELEASE_STAGGER_TICKS + 1);
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
        return this.isStartupGraceComplete()
                && PlayerNpcAiWorkBudget.canStartWork(this.playerNpc)
                && this.delegate.canUse();
    }

    @Override
    public boolean canContinueToUse() {
        return this.isStartupGraceComplete()
                && PlayerNpcAiWorkBudget.canContinueWork(this.playerNpc)
                && this.delegate.canContinueToUse();
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
        this.delegate.start();
    }

    @Override
    public void stop() {
        this.delegate.stop();
        PlayerNpcAiWorkBudget.onWorkStopped(this.playerNpc);
    }

    @Override
    public void tick() {
        this.delegate.tick();
    }
}
