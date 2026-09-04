package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class DescendHighColumnGoal extends Goal {
    private static final String AI_STATE = "ai.player_npc.descending_column";
    private static final int REQUIRED_BREAK_TICKS = 16;
    private static final int MAX_GOAL_TICKS = 20 * 30;
    private static final int MAX_DESCENT_STEPS = 24;
    private static final int LOWER_TERRAIN_RADIUS = 6;
    private static final int MIN_COLUMN_DROP_BLOCKS = 3;
    private static final int MAX_SOLID_SIDE_SUPPORTS = 1;
    private static final int MIN_ENCLOSED_BODY_SIDES = 2;
    private static final int LOWER_TERRAIN_COLUMNS_PER_PASS = 4;
    private static final List<BlockPos> LOWER_TERRAIN_OFFSETS = createLowerTerrainOffsets();

    private final PlayerNpcEntity playerNpc;
    private final TerraformBuildSiteGoal terraformBuildSiteGoal;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle(10);
    private BlockPos floorTarget;
    private int goalTicks;
    private int descentSteps;
    private boolean finished;
    private boolean floorTargetOwnedTemporary;
    private int lowerTerrainColumnCursor;
    private BlockPos lowerTerrainSearchOrigin;
    private boolean workerSlotPaused;

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
                || !PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)
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
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        long timing = PlayerNpcPerformanceMonitor.beginAuxiliaryTiming();
        try {
            this.floorTarget = this.findDescendFloor(serverLevel);
            this.workerSlotPaused = false;
            return this.floorTarget != null;
        } finally {
            PlayerNpcPerformanceMonitor.recordGoalWork(
                    this.playerNpc,
                    this.getClass().getSimpleName() + ".canUse",
                    timing
            );
        }
    }

    @Override
    public boolean canContinueToUse() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            return false;
        }
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
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            this.playerNpc.getNavigation().stop();
            return;
        }
        this.workerSlotPaused = false;
        this.goalTicks = 0;
        this.descentSteps = 0;
        this.finished = false;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState(AI_STATE);
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (!PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.workerSlotPaused = true;
            this.pauseForWorkerSlotLoss();
            return;
        }
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

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.isInsideEnclosedBodyColumn(serverLevel, feet)) {
            this.deferDescentInsideHole(feet);
            this.finished = true;
            return;
        }

        if (this.floorTarget != null
                && this.floorTargetOwnedTemporary
                && !this.playerNpc.isTemporaryPillarSupport(this.floorTarget)) {
            // Ownership disappeared or the tracked block changed after admission. Never reinterpret
            // that stale target as an ordinary natural column during the same descent episode.
            this.finished = true;
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
            this.playerNpc.forgetTemporaryPillarSupport(this.floorTarget);
            this.descentSteps++;
            this.playerNpc.fallDistance = 0.0F;
            this.floorTarget = null;
            this.floorTargetOwnedTemporary = false;
            this.updateDetail();
            return;
        }

        this.finished = true;
    }

    @Override
    public void stop() {
        if (this.workerSlotPaused || !PlayerNpcAiWorkBudget.hasActiveWorkerSlot(this.playerNpc)) {
            this.pauseForWorkerSlotLoss();
            return;
        }
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.floorTarget = null;
        this.floorTargetOwnedTemporary = false;
        this.goalTicks = 0;
        this.descentSteps = 0;
        this.finished = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private void pauseForWorkerSlotLoss() {
        this.playerNpc.getNavigation().stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.workerSlotPaused = true;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        // Keep floorTarget and the entity's persisted ownership ledger. Admission revalidates the
        // exact block after this NPC earns a later worker slot.
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
        this.floorTargetOwnedTemporary = false;
        if (!this.playerNpc.onGround()) {
            return null;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        if (this.isInsideEnclosedBodyColumn(serverLevel, feet)) {
            this.deferDescentInsideHole(feet);
            return null;
        }
        BlockPos floor = feet.below();
        BlockState floorState = serverLevel.getBlockState(floor);
        boolean ownedTemporarySupport = this.playerNpc.isTemporaryPillarSupport(floor);
        if (!this.canBreakColumnBlock(serverLevel, floor, floorState)
                || !serverLevel.getBlockState(floor.below()).isSolidRender(serverLevel, floor.below())) {
            return null;
        }
        if (ownedTemporarySupport) {
            // Stacked owned supports remain a valid interrupted-pillar descent. At the final
            // support, however, prove that the landing cell has an ordinary same-level exterior
            // exit. Merely finding a walkable cell beside the lower landing is insufficient: an
            // irregular pocket inside the same shaft passes that test, then EscapeHole immediately
            // replaces the removed dirt and produces an endless descend/pillar cooldown cycle.
            boolean continuesOwnedColumn = this.playerNpc.isTemporaryPillarSupport(floor.below());
            if (!continuesOwnedColumn && !this.hasSafeSurfaceExitAfterLanding(serverLevel, floor)) {
                this.playerNpc.setIdleTraceDetail(
                        "pillar descent deferred: final support still covers enclosed hole @ "
                                + floor.getX() + " " + floor.getY() + " " + floor.getZ(),
                        30
                );
                return null;
            }
            this.floorTargetOwnedTemporary = true;
            return floor.immutable();
        }

        if (!this.isNarrowColumnTop(serverLevel, floor)
                || !this.hasLowerWalkableTerrainNearby(serverLevel, feet)) {
            return null;
        }
        return floor.immutable();
    }

    private boolean hasSafeSurfaceExitAfterLanding(ServerLevel serverLevel, BlockPos landingFeet) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stepOff = landingFeet.relative(direction);
            if (serverLevel.hasChunkAt(stepOff)
                    && PathNavigationAi.canStandAt(serverLevel, stepOff)
                    && serverLevel.getFluidState(stepOff).isEmpty()
                    && serverLevel.getFluidState(stepOff.above()).isEmpty()
                    && serverLevel.canSeeSky(stepOff.above())) {
                return true;
            }
        }
        return false;
    }

    private boolean canBreakColumnBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        // Completed pillar supports stay remembered for placement safety. Admission separately
        // requires either conservative natural-column evidence or an owned-support landing exit.
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

    /**
     * A descent support may look like an isolated pillar at its own Y level while the NPC's body
     * is still surrounded by the higher rim of a 1x1, 1x2, or 2x2 shaft. In that situation,
     * breaking downward only deepens the hole and can undo blocks just placed by hole escape.
     */
    private boolean isInsideEnclosedBodyColumn(ServerLevel serverLevel, BlockPos feet) {
        int enclosedSides = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            if (!serverLevel.hasChunkAt(side)) {
                return true;
            }
            if (hasBlockingCollision(serverLevel, side)
                    || hasBlockingCollision(serverLevel, side.above())) {
                enclosedSides++;
                if (enclosedSides >= MIN_ENCLOSED_BODY_SIDES) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasBlockingCollision(ServerLevel serverLevel, BlockPos pos) {
        return !serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty();
    }

    private void deferDescentInsideHole(BlockPos feet) {
        this.playerNpc.setIdleTraceDetail(
                "pillar descent deferred: NPC is inside an enclosed hole @ "
                        + feet.getX() + " " + feet.getY() + " " + feet.getZ(),
                40
        );
    }

    private boolean hasLowerWalkableTerrainNearby(ServerLevel serverLevel, BlockPos feet) {
        if (this.lowerTerrainSearchOrigin == null || !this.lowerTerrainSearchOrigin.equals(feet)) {
            this.lowerTerrainSearchOrigin = feet.immutable();
            this.lowerTerrainColumnCursor = 0;
        }
        int size = LOWER_TERRAIN_OFFSETS.size();
        int checked = 0;
        while (checked++ < LOWER_TERRAIN_COLUMNS_PER_PASS && this.lowerTerrainColumnCursor < size) {
            BlockPos offset = LOWER_TERRAIN_OFFSETS.get(this.lowerTerrainColumnCursor++);
            int x = feet.getX() + offset.getX();
            int z = feet.getZ() + offset.getZ();
            if (!serverLevel.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (feet.getY() - candidate.getY() >= MIN_COLUMN_DROP_BLOCKS
                    && PathNavigationAi.canStandAt(serverLevel, candidate)) {
                this.lowerTerrainColumnCursor = 0;
                this.lowerTerrainSearchOrigin = null;
                return true;
            }
        }
        if (this.lowerTerrainColumnCursor >= size) {
            this.lowerTerrainColumnCursor = 0;
            this.lowerTerrainSearchOrigin = null;
        }
        return false;
    }

    private static List<BlockPos> createLowerTerrainOffsets() {
        List<BlockPos> offsets = new ArrayList<>();
        int radiusSqr = LOWER_TERRAIN_RADIUS * LOWER_TERRAIN_RADIUS;
        for (int dx = -LOWER_TERRAIN_RADIUS; dx <= LOWER_TERRAIN_RADIUS; dx++) {
            for (int dz = -LOWER_TERRAIN_RADIUS; dz <= LOWER_TERRAIN_RADIUS; dz++) {
                int distanceSqr = dx * dx + dz * dz;
                if (distanceSqr > 0 && distanceSqr <= radiusSqr) {
                    offsets.add(new BlockPos(dx, 0, dz));
                }
            }
        }
        offsets.sort(Comparator.comparingDouble(offset -> offset.distSqr(BlockPos.ZERO)));
        return List.copyOf(offsets);
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
