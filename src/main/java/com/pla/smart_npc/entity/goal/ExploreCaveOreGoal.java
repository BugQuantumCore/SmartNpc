package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.util.PlayerNpcBlockBreakUtil;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

public class ExploreCaveOreGoal extends Goal {
    private static final int SEARCH_RADIUS = 16;
    private static final int LOCAL_RESOURCE_RADIUS = 96;
    private static final int SEARCH_DOWN = 8;
    private static final int SEARCH_UP = 6;
    private static final int MAX_ORE_TARGET_PATH_CHECKS = 24;
    private static final double BREAK_DISTANCE_SQR = 3.0D * 3.0D;
    private static final double PATH_OBSTRUCTION_BREAK_DISTANCE_SQR = 4.5D * 4.5D;
    private static final int COOLDOWN_TICKS = 20 * 12;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_MINE_TICKS = 20 * 12;
    private static final int MAX_FAILED_PATH_TICKS = 20 * 3;
    private static final int MAX_CLUSTER_SCAN_BLOCKS = 48;
    private static final int MAX_SAFE_DROP_BLOCKS = 4;
    private static final int SUCCESS_COOLDOWN_TICKS = 20 * 2;
    private static final int TORCH_PLACE_INTERVAL_TICKS = 20 * 4;
    private static final float TORCH_PLACE_CHANCE = 0.35F;
    private static final int TORCH_NEARBY_RADIUS = 6;
    private static final int TORCH_LOW_LIGHT_LEVEL = 7;
    private static final int SURFACE_ESCAPE_SCAN_UP = 96;
    private static final int UPWARD_ESCAPE_REQUEST_TICKS = 20 * 8;

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private final double speed;
    private final Set<BlockPos> clusterOres = new HashSet<>();
    private final Set<BlockPos> minedClusterOres = new HashSet<>();
    private final Set<BlockPos> skippedOreTargets = new HashSet<>();
    private final Set<BlockPos> skippedPathObstructions = new HashSet<>();
    private BlockPos targetPos;
    private BlockPos standPos;
    private BlockPos pathObstructionPos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private OreFamily clusterFamily = OreFamily.NONE;
    private int mineTicks;
    private int repathTicks;
    private int failedPathTicks;
    private int pathObstructionMineTicks;
    private int torchPlaceTicks;
    private boolean usingTemporaryPickaxe;
    private boolean minedAnyOre;

    public ExploreCaveOreGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.speed = Math.min(speed, 1.0D);
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
                || this.playerNpc.getOreMiningCooldown() > 0
                || !this.hasAnyPickaxe()
                || this.inventoryIsMostlyFull()) {
            return false;
        }

        OreTarget target = this.findOreTarget(serverLevel);
        if (target == null) {
            this.requestSurfaceEscapeIfUnderground(serverLevel);
            return false;
        }

        this.targetPos = target.targetPos();
        this.standPos = target.standPos();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.targetPos != null
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && this.playerNpc.getTarget() == null
                && this.mineTicks < MAX_MINE_TICKS;
    }

    @Override
    public void start() {
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.pathObstructionMineTicks = 0;
        this.torchPlaceTicks = this.playerNpc.getRandom().nextInt(TORCH_PLACE_INTERVAL_TICKS);
        this.pathObstructionPos = null;
        this.previousMainHand = ItemStack.EMPTY;
        this.clusterFamily = OreFamily.NONE;
        this.clusterOres.clear();
        this.minedClusterOres.clear();
        this.skippedPathObstructions.clear();
        this.usingTemporaryPickaxe = false;
        this.minedAnyOre = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.exploring_cave");
        if (this.targetPos != null && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            BlockState targetState = serverLevel.getBlockState(this.targetPos);
            this.clusterFamily = this.oreFamily(targetState);
            this.refreshClusterOres(serverLevel, this.targetPos);
            if (!this.equipPickaxeFor(targetState)) {
                this.targetPos = null;
                return;
            }
            this.updateTaskDetail(serverLevel);
        }
        this.moveToTarget();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.targetPos == null) {
            return;
        }

        BlockState state = serverLevel.getBlockState(this.targetPos);
        if (!this.isOreBlock(state)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            OreTarget nextOre = this.findNextOreInCluster(serverLevel, this.targetPos);
            if (!this.switchToNextClusterOre(serverLevel, nextOre)) {
                this.targetPos = null;
            }
            return;
        }
        if (this.clusterFamily == OreFamily.NONE) {
            this.clusterFamily = this.oreFamily(state);
        }
        if (this.oreFamily(state) != this.clusterFamily || !this.equipPickaxeFor(state)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.targetPos = null;
            return;
        }
        this.tickTorchPlacement(serverLevel);
        if (this.tickPathObstruction(serverLevel)) {
            return;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D) > BREAK_DISTANCE_SQR) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                if (this.tryStartPathObstructionMining(serverLevel)) {
                    return;
                } else if (this.tryStepDownToward(serverLevel, this.standPos)) {
                    this.failedPathTicks = 0;
                } else if (this.moveToTarget()) {
                    this.failedPathTicks = 0;
                } else {
                    this.failedPathTicks += REPATH_INTERVAL_TICKS;
                    if (this.failedPathTicks >= MAX_FAILED_PATH_TICKS) {
                        this.skipCurrentOreTarget();
                        this.playerNpc.clearBlockBreakProgress(this.targetPos);
                        this.targetPos = null;
                    }
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            this.updateTaskDetail(serverLevel);
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.mineTicks % 8 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, this.targetPos, state, this.playerNpc);
        }

        this.mineTicks++;
        this.updateTaskDetail(serverLevel);
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, state);
        this.playerNpc.showBlockBreakProgress(this.targetPos, this.mineTicks, requiredMineTicks);
        if (this.mineTicks < requiredMineTicks) {
            return;
        }

        BlockPos minedPos = this.targetPos;
        if (PlayerNpcBlockBreakUtil.destroyBlock(serverLevel, minedPos, state, this.playerNpc)) {
            this.minedAnyOre = true;
            this.minedClusterOres.add(minedPos.immutable());
            this.skippedOreTargets.remove(minedPos.immutable());
            this.playerNpc.hurtMainHandItem(1);
        }
        this.playerNpc.clearBlockBreakProgress(minedPos);
        OreTarget nextOre = this.findNextOreInCluster(serverLevel, minedPos);
        if (this.switchToNextClusterOre(serverLevel, nextOre)) {
            return;
        }
        this.targetPos = null;
    }

    @Override
    public void stop() {
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.playerNpc.clearBlockBreakProgress(this.pathObstructionPos);
        this.restorePreviousMainHand();
        this.targetPos = null;
        this.standPos = null;
        this.pathObstructionPos = null;
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.pathObstructionMineTicks = 0;
        this.torchPlaceTicks = 0;
        this.clusterFamily = OreFamily.NONE;
        this.clusterOres.clear();
        this.minedClusterOres.clear();
        this.skippedPathObstructions.clear();
        int cooldown = this.minedAnyOre
                ? SUCCESS_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 2)
                : COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 8);
        this.minedAnyOre = false;
        this.playerNpc.setOreMiningCooldown(cooldown);
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private OreTarget findOreTarget(ServerLevel serverLevel) {
        List<OreTarget> candidates = new ArrayList<>();
        List<BlockPos> oreCandidates = new ArrayList<>();
        BlockPos center = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-SEARCH_RADIUS, -SEARCH_DOWN, -SEARCH_RADIUS),
                center.offset(SEARCH_RADIUS, SEARCH_UP, SEARCH_RADIUS))) {
            BlockPos immutable = pos.immutable();
            BlockState state = serverLevel.getBlockState(immutable);
            if (this.skippedOreTargets.contains(immutable)
                    || !this.isInsideResourceRadius(immutable)
                    || !this.isCaveOre(serverLevel, immutable, state)
                    || !this.hasUsablePickaxeFor(state)) {
                continue;
            }

            oreCandidates.add(immutable);
        }

        oreCandidates.sort(Comparator
                .comparingInt((BlockPos pos) -> this.orePriority(serverLevel.getBlockState(pos)))
                .thenComparingDouble(center::distSqr));
        int checked = 0;
        for (BlockPos oreCandidate : oreCandidates) {
            BlockPos stand = this.findStandPos(serverLevel, oreCandidate);
            if (stand != null) {
                candidates.add(new OreTarget(oreCandidate, stand));
            }
            if (++checked >= MAX_ORE_TARGET_PATH_CHECKS) {
                break;
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator
                .comparingInt((OreTarget target) -> this.orePriority(serverLevel.getBlockState(target.targetPos())))
                .thenComparingDouble(target -> center.distSqr(target.standPos())));
        return candidates.get(this.playerNpc.getRandom().nextInt(Math.min(candidates.size(), 6)));
    }

    private void requestSurfaceEscapeIfUnderground(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (serverLevel.canSeeSky(feet.above())) {
            return;
        }

        BlockPos surfaceTarget = this.findSurfaceEscapeTarget(serverLevel, feet);
        if (surfaceTarget != null) {
            this.playerNpc.requestUpwardEscapeTo(surfaceTarget, UPWARD_ESCAPE_REQUEST_TICKS);
        }
    }

    private BlockPos findSurfaceEscapeTarget(ServerLevel serverLevel, BlockPos feet) {
        int scanTop = Math.min(serverLevel.getMaxBuildHeight() - 3, feet.getY() + SURFACE_ESCAPE_SCAN_UP);
        for (int y = feet.getY() + 3; y <= scanTop; y++) {
            BlockPos target = new BlockPos(feet.getX(), y, feet.getZ());
            if (serverLevel.canSeeSky(target.above())) {
                return target.immutable();
            }
        }
        return null;
    }

    private boolean isCaveOre(ServerLevel serverLevel, BlockPos pos) {
        return this.isCaveOre(serverLevel, pos, serverLevel.getBlockState(pos));
    }

    private boolean isCaveOre(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (!this.isOreBlock(state) || !this.hasAdjacentAir(serverLevel, pos)) {
            return false;
        }

        return pos.getY() <= serverLevel.getSeaLevel() + 12 || !serverLevel.canSeeSky(pos.above());
    }

    private boolean hasAdjacentAir(ServerLevel serverLevel, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (serverLevel.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private OreTarget findNextOreInCluster(ServerLevel serverLevel, BlockPos originPos) {
        if (this.clusterFamily == OreFamily.NONE) {
            return null;
        }

        this.refreshClusterOres(serverLevel, originPos);
        if (!this.clusterOres.isEmpty()) {
            List<OreTarget> rememberedCandidates = new ArrayList<>();
            for (BlockPos clusterOre : this.clusterOres) {
                BlockState state = serverLevel.getBlockState(clusterOre);
                if (this.oreFamily(state) != this.clusterFamily
                        || this.minedClusterOres.contains(clusterOre)
                        || this.skippedOreTargets.contains(clusterOre)
                        || !this.isInsideResourceRadius(clusterOre)
                        || !this.hasUsablePickaxeFor(state)) {
                    continue;
                }

                BlockPos stand = this.findStandPos(serverLevel, clusterOre);
                if (stand != null) {
                    rememberedCandidates.add(new OreTarget(clusterOre, stand));
                }
            }

            if (!rememberedCandidates.isEmpty()) {
                BlockPos npcPos = this.playerNpc.blockPosition();
                rememberedCandidates.sort(Comparator
                        .comparingDouble((OreTarget target) -> originPos.distSqr(target.targetPos()))
                        .thenComparingDouble(target -> npcPos.distSqr(target.standPos())));
                return rememberedCandidates.get(0);
            }
        }

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<OreTarget> candidates = new ArrayList<>();

        if (this.minedClusterOres.isEmpty()) {
            BlockPos seed = originPos.immutable();
            queue.add(seed);
            visited.add(seed);
        } else {
            for (BlockPos minedOre : this.minedClusterOres) {
                queue.add(minedOre);
                visited.add(minedOre);
            }
        }

        int scannedOreBlocks = 0;
        while (!queue.isEmpty() && scannedOreBlocks < MAX_CLUSTER_SCAN_BLOCKS) {
            BlockPos current = queue.remove();
            for (BlockPos next : this.clusterNeighbors(current)) {
                if (!visited.add(next)) {
                    continue;
                }

                BlockState nextState = serverLevel.getBlockState(next);
                if (this.oreFamily(nextState) != this.clusterFamily) {
                    continue;
                }

                scannedOreBlocks++;
                queue.add(next);
                if (this.minedClusterOres.contains(next)
                        || this.skippedOreTargets.contains(next)
                        || !this.isInsideResourceRadius(next)
                        || !this.hasUsablePickaxeFor(nextState)) {
                    continue;
                }

                BlockPos stand = this.findStandPos(serverLevel, next);
                if (stand != null) {
                    candidates.add(new OreTarget(next, stand));
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        BlockPos npcPos = this.playerNpc.blockPosition();
        candidates.sort(Comparator
                .comparingDouble((OreTarget target) -> originPos.distSqr(target.targetPos()))
                .thenComparingDouble(target -> npcPos.distSqr(target.standPos())));
        return candidates.get(0);
    }

    private void refreshClusterOres(ServerLevel serverLevel, BlockPos seedPos) {
        if (this.clusterFamily == OreFamily.NONE || seedPos == null) {
            return;
        }

        Queue<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>(this.clusterOres);
        if (this.oreFamily(serverLevel.getBlockState(seedPos)) == this.clusterFamily) {
            BlockPos seed = seedPos.immutable();
            queue.add(seed);
            visited.add(seed);
            this.clusterOres.add(seed);
        }
        for (BlockPos knownOre : this.clusterOres) {
            if (this.oreFamily(serverLevel.getBlockState(knownOre)) == this.clusterFamily) {
                queue.add(knownOre);
            }
        }
        for (BlockPos minedOre : this.minedClusterOres) {
            queue.add(minedOre);
            visited.add(minedOre);
        }

        int scannedOreBlocks = 0;
        while (!queue.isEmpty() && scannedOreBlocks < MAX_CLUSTER_SCAN_BLOCKS) {
            BlockPos current = queue.remove();
            for (BlockPos next : this.clusterNeighbors(current)) {
                if (!visited.add(next)) {
                    continue;
                }

                if (!this.isInsideResourceRadius(next)
                        || this.oreFamily(serverLevel.getBlockState(next)) != this.clusterFamily) {
                    continue;
                }

                scannedOreBlocks++;
                this.clusterOres.add(next.immutable());
                queue.add(next);
            }
        }
    }

    private List<BlockPos> clusterNeighbors(BlockPos center) {
        List<BlockPos> neighbors = new ArrayList<>(26);
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            if (!pos.equals(center)) {
                neighbors.add(pos.immutable());
            }
        }
        return neighbors;
    }

    private boolean switchToNextClusterOre(ServerLevel serverLevel, OreTarget nextOre) {
        if (nextOre == null) {
            return false;
        }

        BlockState nextState = serverLevel.getBlockState(nextOre.targetPos());
        if (!this.equipPickaxeFor(nextState)) {
            return false;
        }

        this.targetPos = nextOre.targetPos();
        this.standPos = nextOre.standPos();
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.updateTaskDetail(serverLevel);
        this.moveToTarget();
        return true;
    }

    private void skipCurrentOreTarget() {
        if (this.targetPos == null) {
            return;
        }

        if (this.skippedOreTargets.size() > 64) {
            this.skippedOreTargets.clear();
        }
        this.skippedOreTargets.add(this.targetPos.immutable());
    }

    private BlockPos findStandPos(ServerLevel serverLevel, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        candidates.add(target.below());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(target.relative(direction));
            candidates.add(target.relative(direction).above());
            candidates.add(target.relative(direction).below());
        }
        candidates.add(target.above());

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canReachStand(serverLevel, immutable, target)
                    && immutable.distSqr(target) <= BREAK_DISTANCE_SQR + 2.0D) {
                return immutable;
            }
        }
        return null;
    }

    private boolean canReachStand(ServerLevel serverLevel, BlockPos pos, BlockPos target) {
        if (!this.canStandAt(serverLevel, pos)) {
            return false;
        }

        if (this.playerNpc.blockPosition().equals(pos)
                || this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) <= 2.0D * 2.0D) {
            return true;
        }

        Path path = this.playerNpc.getNavigation().createPath(pos, 0);
        return path != null && path.canReach()
                || this.canReachBySafeDrop(serverLevel, pos)
                || this.hasLocalPathObstructionToward(serverLevel, pos, target);
    }

    private boolean canReachBySafeDrop(ServerLevel serverLevel, BlockPos destination) {
        BlockPos feet = this.playerNpc.blockPosition();
        if (destination.getY() >= feet.getY()) {
            return false;
        }

        for (Direction direction : this.directionsToward(feet, destination)) {
            BlockPos edgeFeet = feet.relative(direction);
            BlockPos landingFeet = this.findSafeDropLanding(serverLevel, edgeFeet);
            if (landingFeet != null && landingFeet.distSqr(destination) <= feet.distSqr(destination) + 4.0D) {
                return true;
            }
        }
        return false;
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private boolean tryStepDownToward(ServerLevel serverLevel, BlockPos destination) {
        if (destination == null
                || !this.playerNpc.onGround()
                || destination.getY() >= this.playerNpc.blockPosition().getY()) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        List<Direction> directions = this.directionsToward(feet, destination);
        for (Direction direction : directions) {
            BlockPos edgeFeet = feet.relative(direction);
            BlockPos landingFeet = this.findSafeDropLanding(serverLevel, edgeFeet);
            if (landingFeet == null || landingFeet.distSqr(destination) > feet.distSqr(destination) + 4.0D) {
                continue;
            }

            double dx = edgeFeet.getX() + 0.5D - this.playerNpc.getX();
            double dz = edgeFeet.getZ() + 0.5D - this.playerNpc.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 0.001D) {
                continue;
            }

            Vec3 motion = this.playerNpc.getDeltaMovement();
            this.playerNpc.getNavigation().stop();
            this.playerNpc.setDeltaMovement(dx / length * 0.28D, Math.min(motion.y, -0.08D), dz / length * 0.28D);
            this.playerNpc.hasImpulse = true;
            return true;
        }

        return false;
    }

    private List<Direction> directionsToward(BlockPos from, BlockPos to) {
        List<Direction> directions = new ArrayList<>();
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz) && dx != 0) {
            directions.add(dx > 0 ? Direction.EAST : Direction.WEST);
        } else if (dz != 0) {
            directions.add(dz > 0 ? Direction.SOUTH : Direction.NORTH);
        }

        if (dx != 0) {
            Direction xDirection = dx > 0 ? Direction.EAST : Direction.WEST;
            if (!directions.contains(xDirection)) {
                directions.add(xDirection);
            }
        }
        if (dz != 0) {
            Direction zDirection = dz > 0 ? Direction.SOUTH : Direction.NORTH;
            if (!directions.contains(zDirection)) {
                directions.add(zDirection);
            }
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (!directions.contains(direction)) {
                directions.add(direction);
            }
        }
        return directions;
    }

    private BlockPos findSafeDropLanding(ServerLevel serverLevel, BlockPos edgeFeet) {
        if (!this.hasOpenBodySpace(serverLevel, edgeFeet)) {
            return null;
        }

        for (int drop = 1; drop <= MAX_SAFE_DROP_BLOCKS; drop++) {
            BlockPos landingFeet = edgeFeet.below(drop);
            if (this.hasOpenBodySpace(serverLevel, landingFeet)
                    && this.canStandAt(serverLevel, landingFeet)) {
                return landingFeet;
            }
        }
        return null;
    }

    private boolean hasOpenBodySpace(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
    }

    private boolean moveToTarget() {
        if (this.standPos == null) {
            return false;
        }

        Path path = this.playerNpc.getNavigation().createPath(this.standPos, 0);
        if (path == null || !path.canReach()) {
            return false;
        }
        return this.playerNpc.getNavigation().moveTo(path, this.speed);
    }

    private boolean tryStartPathObstructionMining(ServerLevel serverLevel) {
        BlockPos obstruction = this.findPathObstructionTarget(serverLevel);
        if (obstruction == null) {
            return false;
        }

        this.pathObstructionPos = obstruction;
        this.pathObstructionMineTicks = 0;
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.playerNpc.getNavigation().stop();
        return this.tickPathObstruction(serverLevel);
    }

    private boolean tickPathObstruction(ServerLevel serverLevel) {
        if (this.pathObstructionPos == null) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(this.pathObstructionPos);
        if (!this.isPathObstructionBlock(serverLevel, this.pathObstructionPos, state)) {
            this.clearPathObstruction();
            return false;
        }
        if (this.playerNpc.distanceToSqr(
                this.pathObstructionPos.getX() + 0.5D,
                this.pathObstructionPos.getY() + 0.5D,
                this.pathObstructionPos.getZ() + 0.5D
        ) > PATH_OBSTRUCTION_BREAK_DISTANCE_SQR) {
            this.skippedPathObstructions.add(this.pathObstructionPos.immutable());
            this.clearPathObstruction();
            return false;
        }
        if (!this.equipToolForPathObstruction(state)) {
            this.skippedPathObstructions.add(this.pathObstructionPos.immutable());
            this.clearPathObstruction();
            return false;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.pathObstructionPos.getX() + 0.5D,
                this.pathObstructionPos.getY() + 0.5D,
                this.pathObstructionPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        if (this.pathObstructionMineTicks % 8 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, this.pathObstructionPos, state, this.playerNpc);
        }

        this.pathObstructionMineTicks++;
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, this.pathObstructionPos, state);
        this.playerNpc.showBlockBreakProgress(this.pathObstructionPos, this.pathObstructionMineTicks, requiredMineTicks);
        this.updatePathObstructionDetail(serverLevel, state, requiredMineTicks);
        if (this.pathObstructionMineTicks < requiredMineTicks) {
            return true;
        }

        BlockPos clearedPos = this.pathObstructionPos;
        if (PlayerNpcBlockBreakUtil.destroyBlock(serverLevel, clearedPos, state, this.playerNpc)) {
            this.playerNpc.hurtMainHandItem(1);
            this.failedPathTicks = 0;
            this.repathTicks = 0;
            this.moveToTarget();
        } else {
            this.skippedPathObstructions.add(clearedPos.immutable());
        }
        this.clearPathObstruction();
        return true;
    }

    private void clearPathObstruction() {
        if (this.pathObstructionPos != null) {
            this.playerNpc.clearBlockBreakProgress(this.pathObstructionPos);
        }
        this.pathObstructionPos = null;
        this.pathObstructionMineTicks = 0;
    }

    private BlockPos findPathObstructionTarget(ServerLevel serverLevel) {
        if (this.standPos == null || this.targetPos == null) {
            return null;
        }

        return this.findPathObstructionToward(serverLevel, this.standPos, this.targetPos);
    }

    private boolean hasLocalPathObstructionToward(ServerLevel serverLevel, BlockPos destination, BlockPos target) {
        return this.playerNpc.blockPosition().distSqr(destination) <= 6.0D * 6.0D
                && this.findPathObstructionToward(serverLevel, destination, target) != null;
    }

    private BlockPos findPathObstructionToward(ServerLevel serverLevel, BlockPos destination, BlockPos target) {
        BlockPos feet = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(feet.above());
        candidates.add(feet.above(2));
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            candidates.add(side);
            candidates.add(side.above());
            if (destination.getY() > feet.getY()) {
                candidates.add(side.above(2));
            }
        }

        Set<BlockPos> seen = new HashSet<>();
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> pos.distSqr(destination))
                .thenComparingDouble(this::distanceToBlockCenterSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!seen.add(immutable)
                    || this.skippedPathObstructions.contains(immutable)
                    || target != null && immutable.equals(target)
                    || !serverLevel.isInWorldBounds(immutable)
                    || !serverLevel.getWorldBorder().isWithinBounds(immutable)) {
                continue;
            }

            BlockState state = serverLevel.getBlockState(immutable);
            if (this.isPathObstructionBlock(serverLevel, immutable, state)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean isPathObstructionBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return !state.isAir()
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && !state.getCollisionShape(serverLevel, pos).isEmpty()
                && state.getFluidState().isEmpty()
                && !CraftBasicGearGoal.isTemporaryCraftingTable(this.playerNpc, serverLevel, pos)
                && !this.isProtectedHomeBlock(pos)
                && serverLevel.getBlockEntity(pos) == null
                && (!state.requiresCorrectToolForDrops() || this.hasUsablePickaxeFor(state));
    }

    private boolean equipToolForPathObstruction(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE) || state.requiresCorrectToolForDrops()) {
            return this.equipPickaxeFor(state);
        }
        return true;
    }

    private double distanceToBlockCenterSqr(BlockPos pos) {
        return this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(this.playerNpc);
        return homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), pos);
    }

    private boolean isInsideResourceRadius(BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideActivityRadius(this.playerNpc, pos, LOCAL_RESOURCE_RADIUS, true);
    }

    private boolean isOreBlock(BlockState state) {
        return state.is(Blocks.IRON_ORE)
                || state.is(Blocks.DEEPSLATE_IRON_ORE)
                || state.is(Blocks.COAL_ORE)
                || state.is(Blocks.DEEPSLATE_COAL_ORE)
                || state.is(Blocks.COPPER_ORE)
                || state.is(Blocks.DEEPSLATE_COPPER_ORE);
    }

    private int orePriority(BlockState state) {
        if (state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)) {
            return 0;
        }
        if (state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)) {
            return 1;
        }
        return 2;
    }

    private OreFamily oreFamily(BlockState state) {
        if (state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)) {
            return OreFamily.IRON;
        }
        if (state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)) {
            return OreFamily.COAL;
        }
        if (state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE)) {
            return OreFamily.COPPER;
        }
        return OreFamily.NONE;
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockState state) {
        return this.getRequiredMineTicks(serverLevel, this.targetPos, state);
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(serverLevel, pos);
        if (hardness < 0.0F) {
            return MAX_MINE_TICKS;
        }

        ItemStack heldStack = this.playerNpc.getMainHandItem();
        float toolSpeed = heldStack.isEmpty() ? 1.0F : heldStack.getDestroySpeed(state);
        if (toolSpeed <= 0.0F) {
            toolSpeed = 1.0F;
        }

        boolean correctTool = !state.requiresCorrectToolForDrops() || heldStack.isCorrectToolForDrops(state);
        float progressPerTick = toolSpeed / hardness / (correctTool ? 30.0F : 100.0F);
        if (progressPerTick <= 0.0F) {
            return MAX_MINE_TICKS;
        }

        return Math.min(MAX_MINE_TICKS, Math.max(1, (int) Math.ceil(1.0F / progressPerTick)));
    }

    private boolean hasAnyPickaxe() {
        return this.playerNpc.getMainHandItem().getItem() instanceof PickaxeItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof PickaxeItem);
    }

    private boolean hasUsablePickaxeFor(BlockState state) {
        return this.isUsablePickaxeFor(this.playerNpc.getMainHandItem(), state)
                || InventoryUtils.hasItem(this.playerNpc, stack -> this.isUsablePickaxeFor(stack, state));
    }

    private boolean isUsablePickaxeFor(ItemStack stack, BlockState state) {
        return !stack.isEmpty()
                && stack.getItem() instanceof PickaxeItem
                && (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state));
    }

    private boolean equipPickaxeFor(BlockState state) {
        if (this.isUsablePickaxeFor(this.playerNpc.getMainHandItem(), state)) {
            return true;
        }

        ItemStack pickaxe = this.playerNpc.consumeInventoryItem(stack -> this.isUsablePickaxeFor(stack, state), 1)
                .orElse(ItemStack.EMPTY);
        if (pickaxe.isEmpty()) {
            return false;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryPickaxe) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryPickaxe = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, pickaxe);
        return true;
    }

    private void tickTorchPlacement(ServerLevel serverLevel) {
        if (this.mineTicks > 0) {
            return;
        }

        if (this.torchPlaceTicks-- > 0) {
            return;
        }
        this.torchPlaceTicks = TORCH_PLACE_INTERVAL_TICKS + this.playerNpc.getRandom().nextInt(TORCH_PLACE_INTERVAL_TICKS);

        if (this.playerNpc.getRandom().nextFloat() > TORCH_PLACE_CHANCE) {
            return;
        }

        if (!InventoryUtils.hasItem(this.playerNpc, Items.TORCH)
                && !PlayerNpcCraftingUtil.tryCraftTorches(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
            return;
        }

        BlockPos torchPos = this.findTorchPlacement(serverLevel);
        if (torchPos == null) {
            return;
        }

        ItemStack torch = this.playerNpc.consumeInventoryItem(Items.TORCH, 1).orElse(ItemStack.EMPTY);
        if (torch.isEmpty()) {
            return;
        }

        this.placingBlockAi.placeBlock(serverLevel, torchPos, Blocks.TORCH.defaultBlockState());
        this.playerNpc.getLookControl().setLookAt(torchPos.getX() + 0.5D, torchPos.getY() + 0.5D, torchPos.getZ() + 0.5D, 40.0F, 40.0F);
    }

    private BlockPos findTorchPlacement(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        if (serverLevel.getBrightness(LightLayer.BLOCK, center) > TORCH_LOW_LIGHT_LEVEL
                || this.hasNearbyTorch(serverLevel, center)) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(center);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(center.relative(direction));
            candidates.add(center.relative(direction).below());
        }

        BlockState torchState = Blocks.TORCH.defaultBlockState();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (serverLevel.getBlockState(immutable).canBeReplaced()
                    && serverLevel.getFluidState(immutable).isEmpty()
                    && torchState.canSurvive(serverLevel, immutable)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean hasNearbyTorch(ServerLevel serverLevel, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-TORCH_NEARBY_RADIUS, -2, -TORCH_NEARBY_RADIUS),
                center.offset(TORCH_NEARBY_RADIUS, 2, TORCH_NEARBY_RADIUS))) {
            if (serverLevel.getBlockState(pos).is(Blocks.TORCH)) {
                return true;
            }
        }
        return false;
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryPickaxe) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryPickaxe = false;
    }

    private void updateTaskDetail(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        BlockState state = serverLevel.getBlockState(this.targetPos);
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String blockName = blockId == null ? state.getBlock().getDescriptionId() : blockId.toString();
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, state);
        boolean inBreakRange = this.playerNpc.distanceToSqr(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D
        ) <= BREAK_DISTANCE_SQR;
        this.playerNpc.setCurrentAiDetail(String.format(
                Locale.ROOT,
                "%s @ %d %d %d %s",
                blockName,
                this.targetPos.getX(),
                this.targetPos.getY(),
                this.targetPos.getZ(),
                inBreakRange ? String.format(Locale.ROOT, "%d/%dt", Math.min(this.mineTicks, requiredMineTicks), requiredMineTicks) : "walking"
        ));
    }

    private void updatePathObstructionDetail(ServerLevel serverLevel, BlockState state, int requiredMineTicks) {
        if (this.pathObstructionPos == null) {
            return;
        }

        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String blockName = blockId == null ? state.getBlock().getDescriptionId() : blockId.toString();
        this.playerNpc.setCurrentAiDetail(String.format(
                Locale.ROOT,
                "clearing ore path %s @ %d %d %d %d/%dt",
                blockName,
                this.pathObstructionPos.getX(),
                this.pathObstructionPos.getY(),
                this.pathObstructionPos.getZ(),
                Math.min(this.pathObstructionMineTicks, requiredMineTicks),
                requiredMineTicks
        ));
    }

    private boolean inventoryIsMostlyFull() {
        SimpleContainer inventory = this.playerNpc.getInventory();
        int freeSlots = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                freeSlots++;
            }
        }
        return freeSlots <= 2;
    }

    private enum OreFamily {
        NONE,
        IRON,
        COAL,
        COPPER
    }

    private record OreTarget(BlockPos targetPos, BlockPos standPos) {}
}
