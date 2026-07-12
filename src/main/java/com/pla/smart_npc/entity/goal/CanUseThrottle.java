package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;

/**
 * Keeps expensive goal activation checks from running every selector pass.
 */
final class CanUseThrottle {
    static final int DEFAULT_INTERVAL_TICKS = 20;

    private final int intervalTicks;
    private int nextCheckTick;

    CanUseThrottle() {
        this(DEFAULT_INTERVAL_TICKS);
    }

    CanUseThrottle(int intervalTicks) {
        this.intervalTicks = Math.max(1, intervalTicks);
    }

    boolean canCheck(PlayerNpcEntity playerNpc) {
        if (playerNpc.tickCount < this.nextCheckTick) {
            return false;
        }

        this.nextCheckTick = playerNpc.tickCount + this.intervalTicks;
        return true;
    }

    void reset() {
        this.nextCheckTick = 0;
    }
}
