package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Predicate;

public class ExploreAroundGoal extends Goal {
    private static final int[] SEARCH_RADII = {48, 32, 24, 12, 6};
    private static final int ATTEMPTS_PER_RADIUS = 10;
    private static final int MAX_EXPLORE_TICKS = 20 * 45;
    private static final int REPATH_INTERVAL_TICKS = 20 * 2;
    private static final int UPWARD_ESCAPE_REQUEST_TICKS = 20 * 8;
    private static final int MAX_EXPLORE_PILLAR_BLOCKS = 5;
    private static final int MAX_EXPLORE_SAFE_DROP_BLOCKS = 5;
    private static final int LOCAL_SURFACE_ESCAPE_RADIUS = 6;
    private static final int MIN_LOCAL_SURFACE_NEIGHBORS = 2;
    private static final double ARRIVAL_DISTANCE_SQR = 3.0D * 3.0D;

    private final PlayerNpcEntity playerNpc;
    private final PathNavigationAi pathNavigationAi;
    private final double speed;
    private final String detail;
    private final Predicate<ServerLevel> shouldExplore;
    private final Predicate<ServerLevel> shouldYieldToSubGoal;
    private BlockPos targetPos;
    private int exploreTicks;
    private int repathTicks;

    public ExploreAroundGoal(
            PlayerNpcEntity playerNpc,
            double speed,
            String detail,
            Predicate<ServerLevel> shouldExplore,
            Predicate<ServerLevel> shouldYieldToSubGoal
    ) {
        this.playerNpc = playerNpc;
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.speed = speed;
        this.detail = detail;
        this.shouldExplore = shouldExplore;
        this.shouldYieldToSubGoal = shouldYieldToSubGoal;
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
                || !this.shouldExplore.test(serverLevel)
                || this.shouldYieldToSubGoal.test(serverLevel)) {
            return false;
        }

        this.targetPos = this.findReachableSurfaceTarget(serverLevel);
        if (this.targetPos == null) {
            this.tryRequestShortUpwardEscape(serverLevel, null);
        }
        return this.targetPos != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.targetPos != null
                && this.exploreTicks < MAX_EXPLORE_TICKS
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.shouldExplore.test(serverLevel)
                && !this.shouldYieldToSubGoal.test(serverLevel)
                && this.distanceToTargetSqr() > ARRIVAL_DISTANCE_SQR;
    }

    @Override
    public void start() {
        this.exploreTicks = 0;
        this.repathTicks = 0;
        this.playerNpc.setCurrentAiState("ai.player_npc.exploring");
        this.playerNpc.setCurrentAiDetail(this.detail);
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.moveToTarget(serverLevel);
        }
    }

    @Override
    public void tick() {
        this.exploreTicks++;
        if (this.targetPos == null) {
            return;
        }
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.targetPos = null;
            return;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY(),
                this.targetPos.getZ() + 0.5D,
                30.0F,
                30.0F
        );
        if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
            this.moveToTarget(serverLevel);
            this.repathTicks = REPATH_INTERVAL_TICKS;
        }
    }

    @Override
    public void stop() {
        this.targetPos = null;
        this.exploreTicks = 0;
        this.repathTicks = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private BlockPos findReachableSurfaceTarget(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        for (int radius : SEARCH_RADII) {
            for (int attempt = 0; attempt < ATTEMPTS_PER_RADIUS; attempt++) {
                double angle = this.playerNpc.getRandom().nextDouble() * Math.PI * 2.0D;
                int distance = Math.max(4, radius - this.playerNpc.getRandom().nextInt(Math.max(1, radius / 4)));
                int x = center.getX() + (int) Math.round(Math.cos(angle) * distance);
                int z = center.getZ() + (int) Math.round(Math.sin(angle) * distance);
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (!this.isSafeExploreTarget(serverLevel, center, candidate)) {
                    continue;
                }

                Path path = this.playerNpc.getNavigation().createPath(candidate, 0);
                if (this.pathNavigationAi.isValidPathTo(candidate, path)
                        || this.pathNavigationAi.canReachOrSafelyDropTo(serverLevel, candidate, MAX_EXPLORE_SAFE_DROP_BLOCKS)) {
                    return candidate.immutable();
                }
            }
        }
        return null;
    }

    private boolean isSafeExploreTarget(ServerLevel serverLevel, BlockPos center, BlockPos pos) {
        if (!this.canStandAt(serverLevel, pos) || !serverLevel.canSeeSky(pos.above())) {
            return false;
        }
        return this.hasLocalSurfaceRoom(serverLevel, pos);
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos);
    }

    private void moveToTarget(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return;
        }

        if (this.pathNavigationAi.moveTo(serverLevel, this.targetPos, this.speed, MAX_EXPLORE_SAFE_DROP_BLOCKS)) {
            return;
        }

        if (this.tryRequestShortUpwardEscape(serverLevel, this.targetPos)) {
            this.targetPos = null;
        }
    }

    private boolean tryRequestShortUpwardEscape(ServerLevel serverLevel, BlockPos routeHint) {
        if (this.playerNpc.getUpwardEscapeTarget() != null) {
            return true;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos escapeTarget = this.findShortSurfaceEscapeTarget(serverLevel, feet, routeHint);
        if (escapeTarget == null) {
            return false;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.requestUpwardEscapeTo(escapeTarget, UPWARD_ESCAPE_REQUEST_TICKS, MAX_EXPLORE_PILLAR_BLOCKS);
        this.playerNpc.setCurrentAiDetail("exploration climb request @ "
                + posText(escapeTarget)
                + " max="
                + MAX_EXPLORE_PILLAR_BLOCKS);
        return true;
    }

    private BlockPos findShortSurfaceEscapeTarget(ServerLevel serverLevel, BlockPos feet, BlockPos routeHint) {
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -LOCAL_SURFACE_ESCAPE_RADIUS; dx <= LOCAL_SURFACE_ESCAPE_RADIUS; dx++) {
            for (int dz = -LOCAL_SURFACE_ESCAPE_RADIUS; dz <= LOCAL_SURFACE_ESCAPE_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }

                int x = feet.getX() + dx;
                int z = feet.getZ() + dz;
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int climb = y - feet.getY();
                if (climb <= 0 || climb > MAX_EXPLORE_PILLAR_BLOCKS) {
                    continue;
                }

                BlockPos candidate = new BlockPos(x, y, z);
                if (!this.canStandAt(serverLevel, candidate)
                        || !serverLevel.canSeeSky(candidate.above())
                        || !this.hasLocalSurfaceRoom(serverLevel, candidate)) {
                    continue;
                }

                if (this.pathNavigationAi.hasValidPathTo(candidate)) {
                    continue;
                }

                candidates.add(candidate.immutable());
            }
        }

        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> routeHint == null ? 0.0D : horizontalDistanceSqr(pos, routeHint))
                .thenComparingDouble(pos -> blockDistanceSqr(feet, pos)));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private boolean hasLocalSurfaceRoom(ServerLevel serverLevel, BlockPos pos) {
        int neighbors = 0;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos adjacent = pos.relative(direction).offset(0, dy, 0);
                if (this.canStandAt(serverLevel, adjacent) && serverLevel.canSeeSky(adjacent.above())) {
                    neighbors++;
                    break;
                }
            }
        }
        return neighbors >= MIN_LOCAL_SURFACE_NEIGHBORS;
    }

    private double distanceToTargetSqr() {
        return this.targetPos == null
                ? Double.MAX_VALUE
                : this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY(), this.targetPos.getZ() + 0.5D);
    }

    private static double blockDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dy = first.getY() - second.getY();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static double horizontalDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
