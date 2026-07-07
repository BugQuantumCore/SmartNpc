package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcBuildLayout;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class AfkAtHomeGoal extends Goal {
    private static final double HOME_START_DISTANCE_SQR = 28.0D * 28.0D;
    private static final double AFK_REACHED_DISTANCE_SQR = 1.5D * 1.5D;
    private static final int MIN_AFK_TICKS = 20 * 12;
    private static final int MAX_AFK_TICKS = 20 * 35;
    private static final int REPATH_INTERVAL_TICKS = 20 * 2;
    private static final int SHORT_COOLDOWN_TICKS = 20 * 35;
    private static final int BASE_COOLDOWN_TICKS = 20 * 90;
    private static final int RANDOM_COOLDOWN_TICKS = 20 * 150;
    private static final float START_CHANCE = 0.25F;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private BlockPos afkPos;
    private int afkTicks;
    private int cooldownTicks;
    private int repathTicks;
    private boolean reachedSpot;

    public AfkAtHomeGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (this.cooldownTicks > 0) {
            this.cooldownTicks--;
            return false;
        }

        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.shouldPrioritizeLogGathering()
                || this.playerNpc.shouldPrioritizeCobblestoneGathering()) {
            return false;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> savedHome = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (savedHome.isEmpty() || !this.isFinishedHouse(serverLevel, savedHome.get())) {
            return false;
        }

        this.homeArea = savedHome.get();
        BlockPos homeCenter = PlayerNpcHomeUtil.center(this.homeArea);
        if (this.playerNpc.distanceToSqr(homeCenter.getX() + 0.5D, homeCenter.getY(), homeCenter.getZ() + 0.5D) > HOME_START_DISTANCE_SQR) {
            return false;
        }

        if (this.playerNpc.getRandom().nextFloat() >= START_CHANCE) {
            this.cooldownTicks = SHORT_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(SHORT_COOLDOWN_TICKS);
            return false;
        }

        this.afkPos = this.findAfkPos(serverLevel, this.homeArea);
        if (this.afkPos == null) {
            this.cooldownTicks = SHORT_COOLDOWN_TICKS;
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.afkTicks > 0
                && this.afkPos != null
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && !this.playerNpc.shouldPrioritizeLogGathering()
                && !this.playerNpc.shouldPrioritizeCobblestoneGathering();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.afkTicks = MIN_AFK_TICKS + this.playerNpc.getRandom().nextInt(MAX_AFK_TICKS - MIN_AFK_TICKS + 1);
        this.repathTicks = 0;
        this.reachedSpot = this.isAtAfkPos();
        this.playerNpc.setCurrentAiState("ai.player_npc.afk_home");
        this.updateDetail();
        if (this.reachedSpot) {
            this.playerNpc.getNavigation().stop();
        } else {
            this.moveToAfkPos();
        }
    }

    @Override
    public void tick() {
        if (this.afkPos == null) {
            return;
        }

        this.afkTicks--;
        if (!this.isAtAfkPos()) {
            this.reachedSpot = false;
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                this.moveToAfkPos();
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            this.updateDetail();
            return;
        }

        this.reachedSpot = true;
        this.playerNpc.getNavigation().stop();
        this.updateDetail();
    }

    @Override
    public void stop() {
        if (!this.playerNpc.level().isClientSide) {
            this.cooldownTicks = BASE_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(RANDOM_COOLDOWN_TICKS);
        }
        this.homeArea = null;
        this.afkPos = null;
        this.afkTicks = 0;
        this.repathTicks = 0;
        this.reachedSpot = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean isFinishedHouse(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(this.playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()
                || layout.get().width() != homeArea.width()
                || layout.get().depth() != homeArea.depth()
                || TerraformBuildSiteGoal.hasActionablePrepWork(this.playerNpc, serverLevel)
                || BuildHouseGoal.hasReadyHomeBuildWork(this.playerNpc, serverLevel)) {
            return false;
        }

        for (PlayerNpcBuildLayout.RelativeBlock block : layout.get().blocks()) {
            if (block.optional() || block.state().isAir()) {
                continue;
            }
            BlockPos worldPos = block.toWorld(homeArea.origin());
            if (!PlayerNpcBuildMaterialUtil.matches(serverLevel.getBlockState(worldPos), block.state())) {
                return false;
            }
        }
        return true;
    }

    private BlockPos findAfkPos(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = 1; x < homeArea.width() - 1; x++) {
            for (int z = 1; z < homeArea.depth() - 1; z++) {
                BlockPos candidate = PlayerNpcHomeUtil.interiorPos(homeArea, x, z);
                if (this.canStandAt(serverLevel, candidate) && this.canReachOrAlreadyAt(candidate)) {
                    candidates.add(candidate.immutable());
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(this.playerNpc.getRandom().nextInt(candidates.size()));
    }

    private boolean canReachOrAlreadyAt(BlockPos pos) {
        if (this.distanceToPosSqr(pos) <= AFK_REACHED_DISTANCE_SQR) {
            return true;
        }

        Path path = this.playerNpc.getNavigation().createPath(pos, 0);
        return path != null && path.canReach();
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }

        BlockState feet = serverLevel.getBlockState(pos);
        BlockState head = serverLevel.getBlockState(pos.above());
        BlockPos floorPos = pos.below();
        return feet.getCollisionShape(serverLevel, pos).isEmpty()
                && head.getCollisionShape(serverLevel, pos.above()).isEmpty()
                && feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && serverLevel.getBlockState(floorPos).isSolidRender(serverLevel, floorPos);
    }

    private void moveToAfkPos() {
        if (this.afkPos == null) {
            return;
        }

        Path path = this.playerNpc.getNavigation().createPath(this.afkPos, 0);
        if (path != null && path.canReach()) {
            this.playerNpc.getNavigation().moveTo(path, this.speed);
        }
    }

    private boolean isAtAfkPos() {
        return this.afkPos != null && this.distanceToPosSqr(this.afkPos) <= AFK_REACHED_DISTANCE_SQR;
    }

    private double distanceToPosSqr(BlockPos pos) {
        return this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
    }

    private void updateDetail() {
        this.playerNpc.setCurrentAiDetail(this.reachedSpot ? "standing inside home" : "walking to indoor spot");
    }
}
