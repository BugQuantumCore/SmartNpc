package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.init.SmartNpcModEntities;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

public final class EpicFight {
    private static boolean warnedMissingDigAnimation;
    private static boolean warnedMissingSleepAnimation;

    private EpicFight() {
    }

    public static void registerArmatures() {
        Armatures.registerEntityTypeArmature(SmartNpcModEntities.PLAYER_NPC.get(), Armatures.BIPED);
    }

    public static void keepDiggingState(PlayerNpcEntity playerNpc) {
        if (playerNpc == null) {
            return;
        }

        playerNpc.setEpicFightDigging(true);
        LivingEntityPatch<?> patch = getPatch(playerNpc);
        if (patch != null) {
            patch.currentLivingMotion = LivingMotions.DIGGING;
            patch.currentCompositeMotion = LivingMotions.DIGGING;
        }
    }

    public static void playDiggingAnimation(PlayerNpcEntity playerNpc) {
        if (!canAnimate(playerNpc)) {
            return;
        }

        playerNpc.setEpicFightDigging(true);
        LivingEntityPatch<?> patch = getPatch(playerNpc);
        AssetAccessor<? extends StaticAnimation> animation = diggingAnimation();
        if (patch != null && animation != null) {
            patch.currentLivingMotion = LivingMotions.DIGGING;
            patch.currentCompositeMotion = LivingMotions.DIGGING;
            patch.playAnimationSynchronized(animation, 0.0F);
        }
    }

    public static void stopDiggingAnimation(PlayerNpcEntity playerNpc) {
        if (playerNpc == null) {
            return;
        }

        playerNpc.setEpicFightDigging(false);
        LivingEntityPatch<?> patch = getPatch(playerNpc);
        if (patch != null) {
            patch.currentLivingMotion = LivingMotions.IDLE;
            patch.currentCompositeMotion = LivingMotions.IDLE;
            stopIfPresent(patch, diggingAnimation());
        }
    }

    public static void keepSleepingState(PlayerNpcEntity playerNpc) {
        if (playerNpc == null || !playerNpc.isSleeping()) {
            return;
        }

        LivingEntityPatch<?> patch = getPatch(playerNpc);
        if (patch != null) {
            patch.currentLivingMotion = LivingMotions.SLEEP;
            patch.currentCompositeMotion = LivingMotions.SLEEP;
        }
    }

    public static void playSleepingAnimation(PlayerNpcEntity playerNpc) {
        if (!canAnimate(playerNpc) || !playerNpc.isSleeping()) {
            return;
        }

        LivingEntityPatch<?> patch = getPatch(playerNpc);
        AssetAccessor<? extends StaticAnimation> animation = sleepingAnimation();
        if (patch != null && animation != null) {
            patch.currentLivingMotion = LivingMotions.SLEEP;
            patch.currentCompositeMotion = LivingMotions.SLEEP;
            patch.playAnimationSynchronized(animation, 0.0F);
        }
    }

    public static void stopSleepingAnimation(PlayerNpcEntity playerNpc) {
        if (playerNpc == null) {
            return;
        }

        LivingEntityPatch<?> patch = getPatch(playerNpc);
        if (patch != null) {
            stopSleepingIfPresent(patch, sleepingAnimation());
            patch.currentLivingMotion = LivingMotions.IDLE;
            patch.currentCompositeMotion = LivingMotions.IDLE;
        }
    }

    static AssetAccessor<? extends StaticAnimation> diggingAnimation() {
        AssetAccessor<? extends StaticAnimation> animation = EpicFightCloneAnimations.DIG_MAINHAND;
        if (isUsable(animation)) {
            return animation;
        }

        if (!warnedMissingDigAnimation) {
            warnedMissingDigAnimation = true;
            SmartNpc.LOGGER.warn("Smart NPC Epic Fight digging animation is unavailable; skipping mining swing sync.");
        }
        return null;
    }

    static AssetAccessor<? extends StaticAnimation> sleepingAnimation() {
        AssetAccessor<? extends StaticAnimation> animation = Animations.BIPED_SLEEPING;
        if (isSleepingAnimationUsable(animation)) {
            return animation;
        }

        if (!warnedMissingSleepAnimation) {
            warnedMissingSleepAnimation = true;
            SmartNpc.LOGGER.warn("Smart NPC Epic Fight sleeping animation is unavailable; skipping sleep animation sync.");
        }
        return null;
    }

    private static LivingEntityPatch<?> getPatch(PlayerNpcEntity playerNpc) {
        return EpicFightCapabilities.getEntityPatch(playerNpc, LivingEntityPatch.class);
    }

    private static boolean canAnimate(PlayerNpcEntity playerNpc) {
        return playerNpc != null
                && !playerNpc.level().isClientSide
                && playerNpc.isAlive()
                && !playerNpc.isRemoved()
                && !playerNpc.isDeadOrDying();
    }

    private static boolean isUsable(AssetAccessor<? extends StaticAnimation> animation) {
        if (animation == null) {
            return false;
        }

        try {
            return animation.isPresent();
        } catch (RuntimeException exception) {
            if (!warnedMissingDigAnimation) {
                warnedMissingDigAnimation = true;
                SmartNpc.LOGGER.warn("Smart NPC Epic Fight digging animation could not be resolved.", exception);
            }
            return false;
        }
    }

    private static boolean isSleepingAnimationUsable(AssetAccessor<? extends StaticAnimation> animation) {
        if (animation == null) {
            return false;
        }

        try {
            return animation.isPresent();
        } catch (RuntimeException exception) {
            if (!warnedMissingSleepAnimation) {
                warnedMissingSleepAnimation = true;
                SmartNpc.LOGGER.warn("Smart NPC Epic Fight sleeping animation could not be resolved.", exception);
            }
            return false;
        }
    }

    private static void stopIfPresent(LivingEntityPatch<?> patch, AssetAccessor<? extends StaticAnimation> animation) {
        if (isUsable(animation)) {
            patch.stopPlaying(animation);
        }
    }

    private static void stopSleepingIfPresent(LivingEntityPatch<?> patch, AssetAccessor<? extends StaticAnimation> animation) {
        if (isSleepingAnimationUsable(animation)) {
            patch.stopPlaying(animation);
        }
    }
}
