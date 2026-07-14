package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.entity.ai.WeaponAi;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil.MissingBuildMaterialKind;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil.MissingBuildMaterialNeed;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.pathfinder.Path;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Predicate;

public class GatherMissingBuildMaterialGoal extends Goal {
    private static final int SEARCH_RADIUS = 32;
    private static final int TARGET_SCAN_CACHE_TICKS = 20;
    private static final int MAX_GATHER_TICKS = 20 * 45;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int SHEEP_ATTACK_INTERVAL_TICKS = 14;
    private static final double BREAK_DISTANCE_SQR = 4.5D * 4.5D;
    private static final double STAND_REACHED_DISTANCE_SQR = 1.4D * 1.4D;
    private static final double SHEEP_ATTACK_DISTANCE_SQR = 2.4D * 2.4D;
    private static final Map<PlayerNpcEntity, TargetScanCache> TARGET_SCAN_CACHE = new WeakHashMap<>();

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final ToolAi toolAi;
    private final WeaponAi weaponAi;
    private final BreakingBlockAi breakingBlockAi;
    private final PathNavigationAi pathNavigationAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
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
        this.weaponAi = new WeaponAi(playerNpc);
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
        if (need.get().kind() == MissingBuildMaterialKind.BED) {
            return findNearestSheep(playerNpc, serverLevel).isPresent()
                    || findTargetBlock(playerNpc, serverLevel, need.get()).isPresent();
        }
        return findTargetBlock(playerNpc, serverLevel, need.get()).isPresent();
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
                || this.playerNpc.getGatherCooldown() > 0) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (shouldStayHomeForWeather(serverLevel)
                || !needsMissingBuildMaterial(this.playerNpc, serverLevel)) {
            return false;
        }

        Optional<MissingBuildMaterialNeed> missing = PlayerNpcBuildMaterialUtil.findMissingBuildMaterialNeed(serverLevel, this.playerNpc);
        if (missing.isEmpty() || !isGatherableNeed(missing.get().kind())) {
            return false;
        }

        this.need = missing.get();
        this.targetPos = null;
        this.standPos = null;
        this.sheepTarget = null;
        if (this.need.kind() == MissingBuildMaterialKind.BED) {
            return this.selectBedGatherTarget(serverLevel);
        }

        this.targetPos = findTargetBlock(this.playerNpc, serverLevel, this.need).orElse(null);
        if (this.targetPos != null) {
            this.standPos = this.findStandPos(serverLevel, this.targetPos).orElse(null);
            return this.canBreakFromCurrentPosition(this.targetPos) || this.standPos != null;
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return this.need != null
                && this.gatherTicks < MAX_GATHER_TICKS
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.getHoleEscapeCooldown() <= 0
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
        this.playerNpc.setCurrentAiState(this.sheepTarget == null
                ? "ai.player_npc.gathering_build_material"
                : "ai.player_npc.hunting_sheep");
        if (this.need != null && this.need.kind() == MissingBuildMaterialKind.SAND) {
            this.toolAi.equipTool(ShovelItem.class);
        } else if (this.sheepTarget != null) {
            this.weaponAi.equipBestMeleeWeapon();
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
                "gathering " + this.need.description(),
                this.need.kind() == MissingBuildMaterialKind.BED
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
        this.weaponAi.restoreMainHand();
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

    private boolean selectBedGatherTarget(ServerLevel serverLevel) {
        Optional<BlockPos> bedTarget = findTargetBlock(this.playerNpc, serverLevel, this.need);
        Sheep nearbySheep = findNearestSheep(this.playerNpc, serverLevel).orElse(null);
        Optional<BlockPos> bedStand = Optional.empty();
        boolean canGatherBed = false;
        if (bedTarget.isPresent()) {
            BlockPos bedPos = bedTarget.get();
            bedStand = this.findStandPos(serverLevel, bedPos);
            canGatherBed = this.canBreakFromCurrentPosition(bedPos) || bedStand.isPresent();
        }

        if (canGatherBed) {
            this.targetPos = bedTarget.get();
            this.standPos = bedStand.orElse(null);
            return true;
        }
        if (nearbySheep != null) {
            this.sheepTarget = nearbySheep;
            return true;
        }
        if (canGatherBed) {
            this.targetPos = bedTarget.get();
            this.standPos = bedStand.orElse(null);
            return true;
        }
        return false;
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
            int woolCount = PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(ItemTags.WOOL));
            this.playerNpc.setCurrentAiDetail("hunting sheep for " + this.need.description() + " wool=" + woolCount + "/3");
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
        BlockPos center = playerNpc.blockPosition();
        TargetScanCache cache = TARGET_SCAN_CACHE.get(playerNpc);
        if (cache != null && cache.matches(playerNpc.tickCount, center, need)) {
            Optional<BlockPos> cached = cache.target();
            if (cached.isEmpty() || isValidTargetBlock(playerNpc, serverLevel, cached.get(), need)) {
                return cached;
            }
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos bestPos = null;
        double bestDistance = Double.MAX_VALUE;
        int minX = center.getX() - SEARCH_RADIUS;
        int maxX = center.getX() + SEARCH_RADIUS;
        int minY = center.getY() - 8;
        int maxY = center.getY() + 8;
        int minZ = center.getZ() - SEARCH_RADIUS;
        int maxZ = center.getZ() + SEARCH_RADIUS;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockPos target = matchingTargetPos(serverLevel, cursor, need);
                    if (target == null) {
                        continue;
                    }
                    if (home.isPresent() && PlayerNpcHomeUtil.isInside(home.get(), target)) {
                        continue;
                    }

                    double distance = playerNpc.distanceToSqr(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestPos = target.immutable();
                    }
                }
            }
        }

        Optional<BlockPos> result = Optional.ofNullable(bestPos);
        TARGET_SCAN_CACHE.put(playerNpc, new TargetScanCache(
                playerNpc.tickCount,
                center.immutable(),
                need.kind(),
                need.targetState(),
                result
        ));
        return result;
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

    private static BlockPos matchingTargetPos(ServerLevel serverLevel, BlockPos pos, MissingBuildMaterialNeed need) {
        BlockState state = serverLevel.getBlockState(pos);
        if (!matchesNeed(state, need)) {
            return null;
        }
        if (need.kind() == MissingBuildMaterialKind.BED) {
            return normalizeBedFoot(serverLevel, pos, state);
        }
        return pos.immutable();
    }

    private static BlockPos normalizeBedFoot(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof BedBlock)
                || !state.hasProperty(BedBlock.PART)
                || !state.hasProperty(BedBlock.FACING)) {
            return pos.immutable();
        }
        if (state.getValue(BedBlock.PART) == BedPart.FOOT) {
            return pos.immutable();
        }

        Direction facing = state.getValue(BedBlock.FACING);
        BlockPos foot = pos.relative(facing.getOpposite());
        BlockState footState = serverLevel.getBlockState(foot);
        if (footState.getBlock() instanceof BedBlock
                && footState.hasProperty(BedBlock.PART)
                && footState.getValue(BedBlock.PART) == BedPart.FOOT) {
            return foot.immutable();
        }
        return pos.immutable();
    }

    private static boolean isValidTargetBlock(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos pos, MissingBuildMaterialNeed need) {
        return !isProtectedHomeBlock(playerNpc, pos)
                && matchesNeed(serverLevel.getBlockState(pos), need);
    }

    private static double blockDistanceSqr(BlockPos first, BlockPos second) {
        double dx = first.getX() - second.getX();
        double dy = first.getY() - second.getY();
        double dz = first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private record TargetScanCache(
            int tick,
            BlockPos scanCenter,
            MissingBuildMaterialKind kind,
            BlockState targetState,
            Optional<BlockPos> target) {
        private boolean matches(int currentTick, BlockPos currentCenter, MissingBuildMaterialNeed need) {
            return currentTick - this.tick <= TARGET_SCAN_CACHE_TICKS
                    && blockDistanceSqr(this.scanCenter, currentCenter) <= 4.0D
                    && this.kind == need.kind()
                    && this.targetState.equals(need.targetState());
        }
    }
}
