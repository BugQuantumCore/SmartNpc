package com.pla.smart_npc.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Common-side gate for the optional Better Combat integration.
 *
 * Keep all Better Combat / Player Animator classes out of this class so the
 * mod remains loadable when Better Combat is not installed.
 */
public final class BetterCombatCompat {
    private static final String MOD_ID = "bettercombat";

    private BetterCombatCompat() {
    }

    public static boolean isLoaded() {
        return FabricLoader.getInstance().isModLoaded(MOD_ID);
    }
}
