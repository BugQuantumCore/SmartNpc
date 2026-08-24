package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.EnumSet;
import java.util.Optional;

public class DescendHighColumnGoal extends Goal {
    private static final String AI_STATE = "ai.player_npc.descending_column";
    private static final int REQUIRED_BREAK_TICKS = 16;
    private static final int MAX_GOAL_TICKS = 20 * 30;
    private static final int MAX_DESCENT_STEPS = 24;
    private static final int LOWER_TERRAIN_RADIUS = 6;
    private static final int MIN_COLUMN_DROP_BLOCKS = 3;
    private static final int MAX_SOLID_SIDE_SUPPORTS = 1;

    private final PlayerNpcEntity playerNpc;
    private final TerraformBuildSiteGoal terraformBuildSiteGoal;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private BlockPos floorTarget;
    private int goalTicks;
    private int descentSteps;
    private boolean finished;

    public DescendHighColumnGoal(PlayerNpcEntity playerNpc, TerraformBuildSiteGoal terraformBuildSiteGoal) {
        this.playerNpc = playerNpc;
        this.terraformBuildSiteGoal = terraformBuildSiteGoal;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null
                || this.playerNpc.getHoleEscapeCooldown() > 0
                || this.terraformBuildSiteGoal.hasPendingSupportFillEscapeHandoff(serverLevel)
                || this.shouldYieldToMiningSupplyWork()
                || !this.shouldRunForCurrentState()) {
            return false;
        }

        this.floorTarget = this.findDescendFloor(serverLevel);
        return this.floorTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.goalTicks < MAX_GOAL_TICKS
                && this.descentSteps < MAX_DESCENT_STEPS
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
                && this.playerNpc.level() instanceof ServerLevel;
    }

    @Override
    public void start() {
        this.goalTicks = 0;
        this.descentSteps = 0;
        this.finished = false;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState(AI_STATE);
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.finished = true;
            return;
        }

        this.goalTicks++;
        if (!this.playerNpc.onGround()) {
            this.playerNpc.fallDistance = 0.0F;
            this.updateDetail();
            return;
        }

        if (this.floorTarget == null || !this.canBreakColumnBlock(serverLevel, this.floorTarget, serverLevel.getBlockState(this.floorTarget))) {
            this.floorTarget = this.findDescendFloor(serverLevel);
            if (this.floorTarget == null) {
                this.finished = true;
                return;
            }
        }

        this.playerNpc.getNavigation().stop();
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.floorTarget,
                state -> this.canBreakColumnBlock(serverLevel, this.floorTarget, state),
                REQUIRED_BREAK_TICKS,
                "pillar down"
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }
        if (result == BreakingBlockAi.TickResult.DONE) {
            this.descentSteps++;
            this.playerNpc.fallDistance = 0.0F;
            this.floorTarget = null;
            this.updateDetail();
            return;
        }

        this.finished = true;
    }

    @Override
    public void stop() {
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.floorTarget = null;
        this.goalTicks = 0;
        this.descentSteps = 0;
        this.finished = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private boolean shouldRunForCurrentState() {
        String state = this.playerNpc.getCurrentAiState();
        return PlayerNpcEntity.AI_IDLE.equals(state)
                || "ai.player_npc.returning_home".equals(state);
    }

    private boolean shouldYieldToMiningSupplyWork() {
        return this.playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)
                && !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && (this.playerNpc.shouldPrioritizeLogGathering()
                || this.playerNpc.shouldPrioritizeCobblestoneGathering());
    }

    private BlockPos findDescendFloor(ServerLevel serverLevel) {
        if (!this.playerNpc.onGround()) {
            return null;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos floor = feet.below();
        BlockState floorState = serverLevel.getBlockState(floor);
        if (!this.canBreakColumnBlock(serverLevel, floor, floorState)
                || !serverLevel.getBlockState(floor.below()).isSolidRender(serverLevel, floor.below())
                || !this.isNarrowColumnTop(serverLevel, floor)
                || !this.hasLowerWalkableTerrainNearby(serverLevel, feet)) {
            return null;
        }
        return floor.immutable();
    }

    private boolean canBreakColumnBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        // Completed pillar supports stay remembered for placement safety, but this goal only starts
        // after the upward request and escape cooldown end and its narrow-column checks pass.
        return pos != null
                && serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !this.isProtectedHomeBlock(pos)
                && !FarmAi.isOwnedFarmDestructionProtected(this.playerNpc, pos)
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && serverLevel.getBlockEntity(pos) == null
                && !state.getCollisionShape(serverLevel, pos).isEmpty();
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        return home.isPresent() && PlayerNpcHomeUtil.isInside(home.get(), pos);
    }

    private boolean isNarrowColumnTop(ServerLevel serverLevel, BlockPos floor) {
        int solidSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = floor.relative(direction);
            if (serverLevel.getBlockState(side).isSolidRender(serverLevel, side)) {
                solidSides++;
            }
        }
        return solidSides <= MAX_SOLID_SIDE_SUPPORTS;
    }

    private boolean hasLowerWalkableTerrainNearby(ServerLevel serverLevel, BlockPos feet) {
        for (int dx = -LOWER_TERRAIN_RADIUS; dx <= LOWER_TERRAIN_RADIUS; dx++) {
            for (int dz = -LOWER_TERRAIN_RADIUS; dz <= LOWER_TERRAIN_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                int distanceSqr = dx * dx + dz * dz;
                if (distanceSqr > LOWER_TERRAIN_RADIUS * LOWER_TERRAIN_RADIUS) {
                    continue;
                }

                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (feet.getY() - candidate.getY() >= MIN_COLUMN_DROP_BLOCKS
                        && PathNavigationAi.canStandAt(serverLevel, candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void updateDetail() {
        if (this.floorTarget == null) {
            this.playerNpc.setCurrentAiDetail("pillar down");
            return;
        }
        this.playerNpc.setCurrentAiDetail("pillar down @ "
                + this.floorTarget.getX() + " "
                + this.floorTarget.getY() + " "
                + this.floorTarget.getZ());
    }
}
