package com.pla.smart_npc.util;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

public final class PlayerNpcBuildStatusUtil {
    private PlayerNpcBuildStatusUtil() {
    }

    public static String describe(PlayerNpcEntity playerNpc) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        Optional<String> layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc);
        if (home.isEmpty() || layoutId.isEmpty()) {
            return playerNpc.hasInterest(PlayerNpcInterest.BUILDING) ? "No build selected" : "No building interest";
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcBuildLayoutLoader.getLayout(layoutId.get());
        if (layout.isEmpty()) {
            return "Missing layout " + layoutId.get();
        }

        if (playerNpc.level() instanceof ServerLevel serverLevel) {
            int missing = PlayerNpcBuildLayoutLoader.countMissingRequired(serverLevel, layout.get(), home.get().origin());
            if (missing <= 0) {
                return "Finished " + layout.get().name();
            }
            return missing + " required blocks missing";
        }

        return layout.get().name();
    }
}
