package com.pla.smart_npc.compat.epicfight_;

import com.pla.efkick.gameasset.EFKickAnimations;
import net.minecraftforge.fml.ModList;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.world.capabilities.entitypatch.MobPatch;

public class EFKick {
    public static boolean isEfKickInstalled(MobPatch<?> mobPatch) {
        return ModList.get().isLoaded("efkick");
    }

    public static AnimationManager.AnimationAccessor<? extends StaticAnimation> getEFKick1() {
        return EFKickAnimations.KICK_1;
    }

    public static AnimationManager.AnimationAccessor<? extends StaticAnimation> getEFKick2() {
        return EFKickAnimations.KICK_2;
    }

    public static AnimationManager.AnimationAccessor<? extends StaticAnimation> getEFKick3() {
        return EFKickAnimations.KICK_3;
    }

    public static AnimationManager.AnimationAccessor<? extends StaticAnimation> getEFKick4() {
        return EFKickAnimations.KICK_4;
    }

    public static AnimationManager.AnimationAccessor<? extends StaticAnimation> getEFKickH() {
        return EFKickAnimations.KICK_H;
    }

    public static AnimationManager.AnimationAccessor<? extends StaticAnimation> getEFKickC() {
        return EFKickAnimations.KICK_C;
    }

    public static AnimationManager.AnimationAccessor<? extends StaticAnimation> getEFKickRush() {
        return EFKickAnimations.KICK_RUSH;
    }

    public static AnimationManager.AnimationAccessor<? extends StaticAnimation> getEFKickCombo() {
        return EFKickAnimations.KICK_COMBO;
    }
}
