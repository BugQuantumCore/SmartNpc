package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcBlockBreakUtil;
import com.pla.smart_npc.util.PlayerNpcBlockSoundUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

public final class BreakingBlockAi {
    public enum TickResult {
        IDLE,
        RUNNING,
        DONE,
        FAILED
    }

    private static final int HIT_SOUND_INTERVAL_TICKS = 8;
    private static final int ATTACK_ANIMATION_INTERVAL_TICKS = 6;
    private static final float MINING_SNEAK_CHANCE = 0.12F;
    private static final int MINING_SNEAK_MIN_TICKS = 20;
    private static final int MINING_SNEAK_RANDOM_TICKS = 35;

    private final PlayerNpcEntity playerNpc;
    private final ToolAi toolAi;
    private final SneakingAi sneakingAi;
    private BlockPos targetPos;
    private int breakTicks;
    private int requiredTicks;
    private String detail = "breaking block";
    private String toolDetail = "";

    public BreakingBlockAi(PlayerNpcEntity playerNpc, ToolAi toolAi) {
        this.playerNpc = playerNpc;
        this.toolAi = toolAi;
        this.sneakingAi = new SneakingAi(playerNpc);
    }

    public boolean isRunning() {
        return this.targetPos != null;
    }

    public BlockPos targetPos() {
        return this.targetPos;
    }

    public TickResult tick(
            ServerLevel serverLevel,
            BlockPos targetPos,
            Predicate<BlockState> targetPredicate,
            int requiredTicks,
            String detail
    ) {
        if (targetPos == null || targetPredicate == null) {
            this.stop();
            return TickResult.FAILED;
        }

        if (!targetPos.equals(this.targetPos)) {
            this.start(targetPos, requiredTicks, detail);
        }

        BlockState state = serverLevel.getBlockState(targetPos);
        if (!targetPredicate.test(state)) {
            this.stop();
            return TickResult.DONE;
        }
        if (!this.canBreak(serverLevel, targetPos, state)) {
            this.stop();
            return TickResult.FAILED;
        }

        this.toolDetail = this.toolAi.hasPreferredToolFor(state) ? "" : " without preferred tool";
        this.toolAi.equipBestToolFor(state);
        this.playerNpc.getLookControl().setLookAt(
                targetPos.getX() + 0.5D,
                targetPos.getY() + 0.5D,
                targetPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        this.sneakingAi.tickHeldSneak();
        this.breakTicks++;
        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        if (this.breakTicks == 1 || this.breakTicks % ATTACK_ANIMATION_INTERVAL_TICKS == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
        }
        this.playerNpc.showBlockBreakProgress(targetPos, this.breakTicks, this.requiredTicks);
        if (this.breakTicks % HIT_SOUND_INTERVAL_TICKS == 0) {
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, targetPos, state, this.playerNpc);
        }

        if (this.breakTicks < this.requiredTicks) {
            this.updateDetail();
            return TickResult.RUNNING;
        }

        boolean destroyed = PlayerNpcBlockBreakUtil.destroyBlock(serverLevel, targetPos, state, this.playerNpc);
        if (destroyed) {
            this.playerNpc.hurtMainHandItem(1);
        }
        this.stop();
        return destroyed ? TickResult.DONE : TickResult.FAILED;
    }

    public void stop() {
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.sneakingAi.stopSneaking();
        this.targetPos = null;
        this.breakTicks = 0;
        this.requiredTicks = 0;
        this.detail = "breaking block";
        this.toolDetail = "";
    }

    public String detail() {
        if (this.targetPos == null) {
            return "";
        }
        return this.detail + this.toolDetail + " @ "
                + this.targetPos.getX() + " "
                + this.targetPos.getY() + " "
                + this.targetPos.getZ();
    }

    private void start(BlockPos targetPos, int requiredTicks, String detail) {
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.targetPos = targetPos.immutable();
        this.breakTicks = 0;
        this.requiredTicks = Math.max(1, requiredTicks);
        this.detail = detail == null || detail.isBlank() ? "breaking block" : detail;
        this.playerNpc.getNavigation().stop();
        this.sneakingAi.rollHeldSneak(
                this.playerNpc.getRandom(),
                MINING_SNEAK_CHANCE,
                MINING_SNEAK_MIN_TICKS,
                MINING_SNEAK_RANDOM_TICKS
        );
        this.updateDetail();
    }

    private boolean canBreak(ServerLevel serverLevel, BlockPos targetPos, BlockState state) {
        return serverLevel.isInWorldBounds(targetPos)
                && serverLevel.getWorldBorder().isWithinBounds(targetPos)
                && state.getDestroySpeed(serverLevel, targetPos) >= 0.0F
                && state.getFluidState().isEmpty()
                && serverLevel.getBlockEntity(targetPos) == null;
    }

    private void updateDetail() {
        String currentDetail = this.detail();
        if (!currentDetail.isBlank()) {
            this.playerNpc.setCurrentAiDetail(currentDetail);
        }
    }
}
