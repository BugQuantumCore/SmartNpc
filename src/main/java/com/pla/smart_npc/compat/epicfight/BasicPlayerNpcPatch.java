package com.pla.smart_npc.compat.epicfight;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.world.entity.PathfinderMob;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.api.animation.Animator;
import yesman.epicfight.api.animation.LivingMotions;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.gameasset.Animations;
import yesman.epicfight.world.capabilities.entitypatch.mob.ZombiePatch;
import yesman.epicfight.world.damagesource.StunType;

public class BasicPlayerNpcPatch<T extends PathfinderMob> extends ZombiePatch<T> {

    public BasicPlayerNpcPatch() {
        super();
    }

    public void initAnimator(Animator animator) {
        super.initAnimator(animator);
        animator.addLivingAnimation(LivingMotions.IDLE, Animations.BIPED_IDLE);
        animator.addLivingAnimation(LivingMotions.WALK, Animations.BIPED_WALK);
        animator.addLivingAnimation(LivingMotions.CHASE, Animations.BIPED_RUN);
        animator.addLivingAnimation(LivingMotions.SNEAK, Animations.BIPED_SNEAK);
        animator.addLivingAnimation(LivingMotions.FALL, Animations.BIPED_FALL);
        animator.addLivingAnimation(LivingMotions.MOUNT, Animations.BIPED_MOUNT);
        animator.addLivingAnimation(LivingMotions.DEATH, Animations.BIPED_DEATH);
        if (EpicFightCloneAnimations.DIG_MAINHAND != null) {
            animator.addLivingAnimation(LivingMotions.DIGGING, EpicFightCloneAnimations.DIG_MAINHAND);
        }
    }

    @Override
    public void updateMotion(boolean considerInaction) {
        super.updateMotion(considerInaction);
        if (this.getOriginal() instanceof PlayerNpcEntity playerNpc && playerNpc.isEpicFightDigging()) {
            this.currentLivingMotion = LivingMotions.DIGGING;
            this.currentCompositeMotion = LivingMotions.DIGGING;
        }
    }

    @Override
    public AnimationManager.AnimationAccessor<? extends StaticAnimation> getHitAnimation(StunType stunType) {
        return switch (stunType) {
            case LONG -> Animations.BIPED_HIT_LONG;
            case NEUTRALIZE -> Animations.BIPED_COMMON_NEUTRALIZED;
            case KNOCKDOWN -> Animations.BIPED_KNOCKDOWN;
            default -> Animations.BIPED_HIT_SHORT;
        };
    }
}
