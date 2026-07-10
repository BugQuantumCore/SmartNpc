package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil.MissingBuildMaterialKind;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil.MissingBuildMaterialNeed;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Predicate;

public class GatherMissingBuildMaterialGoal extends Goal {
    private static final int SEARCH_RADIUS = 32;
    private static final int MAX_GATHER_TICKS = 20 * 45;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int SHEEP_ATTACK_INTERVAL_TICKS = 14;
    private static final double BREAK_DISTANCE_SQR = 4.5D * 4.5D;
    private static final double STAND_REACHED_DISTANCE_SQR = 1.4D * 1.4D;
    private static final double SHEEP_ATTACK_DISTANCE_SQR = 2.4D * 2.4D;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final PathNavigationAi pathNavigationAi;
    private MissingBuildMaterialNeed need;
    private BlockPos targetPos;
    private BlockPos standPos;
    private Sheep sheepTarget;
    private int gatherTicks;
    private int repathTicks;
    private int attackTicks;

    public GatherMissingBuildMaterialGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean needsMissingBuildMaterial(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return hasPreparedBuildSupplies(playerNpc)
                && !TerraformBuildSiteGoal.hasActionablePrepWork(playerNpc, serverLevel)
                && PlayerNpcBuildMaterialUtil.needsNonPrimaryBuildMaterial(serverLevel, playerNpc);
    }

    public static boolean hasNearbyGatherTarget(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        Optional<MissingBuildMaterialNeed> need = PlayerNpcBuildMaterialUtil.findMissingBuildMaterialNeed(serverLevel, playerNpc);
        if (need.isEmpty() || !isGatherableNeed(need.get().kind())) {
            return false;
        }
        return findTargetBlock(playerNpc, serverLevel, need.get()).isPresent()
                || need.get().kind() == MissingBuildMaterialKind.BED && findNearestSheep(playerNpc, serverLevel).isPresent();
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getGatherCooldown() > 0
                || shouldStayHomeForWeather(serverLevel)
                || !needsMissingBuildMaterial(this.playerNpc, serverLevel)) {
            return false;
        }

        Optional<MissingBuildMaterialNeed> missing = PlayerNpcBuildMaterialUtil.findMissingBuildMaterialNeed(serverLevel, this.playerNpc);
        if (missing.isEmpty() || !isGatherableNeed(missing.get().kind())) {
            return false;
        }

        this.need = missing.get();
        this.targetPos = findTargetBlock(this.playerNpc, serverLevel, this.need).orElse(null);
        this.sheepTarget = this.targetPos == null && this.need.kind() == MissingBuildMaterialKind.BED
                ? findNearestSheep(this.playerNpc, serverLevel).orElse(null)
                : null;
        if (this.targetPos != null) {
            this.standPos = this.findStandPos(serverLevel, this.targetPos).orElse(null);
            return this.canBreakFromCurrentPosition(this.targetPos) || this.standPos != null;
        }
        return this.sheepTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.need != null
                && this.gatherTicks < MAX_GATHER_TICKS
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && !shouldStayHomeForWeather(serverLevel)
                && needsMissingBuildMaterial(this.playerNpc, serverLevel)
                && (this.targetPos != null && this.isValidTarget(serverLevel, this.targetPos)
                || this.sheepTarget != null && this.sheepTarget.isAlive());
    }

    @Override
    public void start() {
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.attackTicks = 0;
        this.playerNpc.setCurrentAiState("ai.player_npc.gathering_build_material");
        if (this.need != null && this.need.kind() == MissingBuildMaterialKind.SAND) {
            this.toolAi.equipTool(ShovelItem.class);
        }
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.need == null) {
            return;
        }

        this.gatherTicks++;
        if (this.sheepTarget != null) {
            this.tickSheepTarget();
            this.updateDetail();
            return;
        }

        if (this.targetPos == null || !this.isValidTarget(serverLevel, this.targetPos)) {
            this.finishGathering();
            return;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                35.0F,
                35.0F
        );

        if (!this.canBreakFromCurrentPosition(this.targetPos)) {
            this.breakingBlockAi.stop();
            if (this.standPos == null) {
                this.standPos = this.findStandPos(serverLevel, this.targetPos).orElse(null);
            }
            if (this.standPos == null) {
                this.finishGathering();
                return;
            }
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                this.pathNavigationAi.moveTo(serverLevel, this.standPos, this.speed, 3);
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            this.updateDetail();
            return;
        }

        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                this.targetPredicate(),
                this.requiredBreakTicks(),
                "gathering " + this.need.description()
        );
        if (result == BreakingBlockAi.TickResult.DONE) {
            this.finishGathering();
        } else if (result == BreakingBlockAi.TickResult.FAILED) {
            this.finishGathering();
        }
        this.updateDetail();
    }

    @Override
    public void stop() {
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        this.need = null;
        this.targetPos = null;
        this.standPos = null;
        this.sheepTarget = null;
        this.gatherTicks = 0;
        this.repathTicks = 0;
        this.attackTicks = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private void tickSheepTarget() {
        if (this.sheepTarget == null || !this.sheepTarget.isAlive()) {
            this.finishGathering();
            return;
        }

        this.playerNpc.getLookControl().setLookAt(this.sheepTarget, 35.0F, 35.0F);
        if (this.playerNpc.distanceToSqr(this.sheepTarget) > SHEEP_ATTACK_DISTANCE_SQR) {
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                this.playerNpc.getNavigation().moveTo(this.sheepTarget, this.speed);
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.attackTicks++ % SHEEP_ATTACK_INTERVAL_TICKS == 0) {
            this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
            this.playerNpc.triggerMainHandAttackAnimation();
            this.playerNpc.doHurtTarget(this.sheepTarget);
        }
    }

    private void finishGathering() {
        this.playerNpc.setGatherCooldown(20 + this.playerNpc.getRandom().nextInt(20));
        this.targetPos = null;
        this.standPos = null;
        this.sheepTarget = null;
    }

    private Optional<BlockPos> findStandPos(ServerLevel serverLevel, BlockPos target) {
        return Direction.Plane.HORIZONTAL.stream()
                .map(target::relative)
                .filter(pos -> PathNavigationAi.canStandAt(serverLevel, pos))
                .filter(pos -> {
                    Path path = this.playerNpc.getNavigation().createPath(pos, 0);
                    return this.pathNavigationAi.isValidPathTo(pos, path)
                            || this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) <= STAND_REACHED_DISTANCE_SQR;
                })
                .min(Comparator.comparingDouble(pos -> this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D)))
                .map(BlockPos::immutable);
    }

    private boolean canBreakFromCurrentPosition(BlockPos pos) {
        return this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= BREAK_DISTANCE_SQR;
    }

    private boolean isValidTarget(ServerLevel serverLevel, BlockPos pos) {
        return this.targetPredicate().test(serverLevel.getBlockState(pos));
    }

    private Predicate<BlockState> targetPredicate() {
        MissingBuildMaterialKind kind = this.need == null ? MissingBuildMaterialKind.NONE : this.need.kind();
        return state -> switch (kind) {
            case SAND -> PlayerNpcBuildMaterialUtil.isSandSourceBlock(state);
            case PLANT -> PlayerNpcBuildMaterialUtil.isGatherablePlantBlock(state);
            case BED -> state.getBlock() instanceof BedBlock;
            case OTHER -> this.need != null && PlayerNpcBuildMaterialUtil.matches(state, this.need.targetState());
            default -> false;
        };
    }

    private int requiredBreakTicks() {
        if (this.need == null) {
            return 20;
        }
        return switch (this.need.kind()) {
            case PLANT -> 8;
            case BED -> 35;
            case SAND -> 36;
            default -> 25;
        };
    }

    private void updateDetail() {
        if (this.need == null) {
            return;
        }
        if (this.sheepTarget != null) {
            this.playerNpc.setCurrentAiDetail("hunting sheep for " + this.need.description());
            return;
        }
        if (this.breakingBlockAi.isRunning()) {
            this.playerNpc.setCurrentAiDetail(this.breakingBlockAi.detail());
            return;
        }
        if (this.targetPos != null) {
            this.playerNpc.setCurrentAiDetail("seeking " + this.need.description() + " @ "
                    + this.targetPos.getX() + " "
                    + this.targetPos.getY() + " "
                    + this.targetPos.getZ());
        }
    }

    private static boolean hasPreparedBuildSupplies(PlayerNpcEntity playerNpc) {
        return !playerNpc.shouldPrioritizeLogGathering()
                && !playerNpc.shouldPrioritizeCobblestoneGathering();
    }

    private static boolean isGatherableNeed(MissingBuildMaterialKind kind) {
        return kind == MissingBuildMaterialKind.SAND
                || kind == MissingBuildMaterialKind.PLANT
                || kind == MissingBuildMaterialKind.BED
                || kind == MissingBuildMaterialKind.OTHER;
    }

    private static Optional<BlockPos> findTargetBlock(PlayerNpcEntity playerNpc, ServerLevel serverLevel, MissingBuildMaterialNeed need) {
        return BlockPos.betweenClosedStream(
                        playerNpc.blockPosition().offset(-SEARCH_RADIUS, -8, -SEARCH_RADIUS),
                        playerNpc.blockPosition().offset(SEARCH_RADIUS, 8, SEARCH_RADIUS))
                .map(BlockPos::immutable)
                .filter(pos -> !isProtectedHomeBlock(playerNpc, pos))
                .filter(pos -> matchesNeed(serverLevel.getBlockState(pos), need))
                .min(Comparator.comparingDouble(pos -> playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)));
    }

    private static boolean matchesNeed(BlockState state, MissingBuildMaterialNeed need) {
        return switch (need.kind()) {
            case SAND -> PlayerNpcBuildMaterialUtil.isSandSourceBlock(state);
            case PLANT -> PlayerNpcBuildMaterialUtil.isGatherablePlantBlock(state);
            case BED -> state.getBlock() instanceof BedBlock;
            case OTHER -> PlayerNpcBuildMaterialUtil.matches(state, need.targetState());
            default -> false;
        };
    }

    private static Optional<Sheep> findNearestSheep(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return serverLevel.getEntitiesOfClass(
                        Sheep.class,
                        playerNpc.getBoundingBox().inflate(SEARCH_RADIUS),
                        sheep -> sheep.isAlive() && !sheep.isBaby())
                .stream()
                .min(Comparator.comparingDouble(playerNpc::distanceToSqr));
    }

    private static boolean isProtectedHomeBlock(PlayerNpcEntity playerNpc, BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        return home.isPresent() && PlayerNpcHomeUtil.isInside(home.get(), pos);
    }

    private static boolean shouldStayHomeForWeather(ServerLevel serverLevel) {
        return serverLevel.isNight() || serverLevel.isThundering();
    }
}
