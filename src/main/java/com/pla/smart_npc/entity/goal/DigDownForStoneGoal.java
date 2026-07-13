package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.BreakingBlockAi;
import com.pla.smart_npc.entity.ai.ClearBlockAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.ToolAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class DigDownForStoneGoal extends Goal {
    private static final int MIN_HOME_DISTANCE = 18;
    private static final int DIG_SITE_MIN_RADIUS = 10;
    private static final int DIG_SITE_MAX_RADIUS = 24;
    private static final int LOCAL_RESOURCE_RADIUS = 96;
    private static final int MAX_GOAL_TICKS = 20 * 120;
    private static final int MAX_STAIR_STEPS = 24;
    private static final int MAX_MINE_TICKS = 20 * 8;
    private static final double BREAK_DISTANCE_SQR = 4.0D * 4.0D;
    private static final double LOCAL_STEP_DISTANCE_SQR = 3.0D * 3.0D;
    private static final int REPATH_INTERVAL_TICKS = 15;
    private static final int COOLDOWN_TICKS = 20 * 18;
    private static final int FAILED_WALK_COOLDOWN_TICKS = 20 * 2;
    private static final int MAX_DIG_SITE_WALK_TICKS = 20 * 25;
    private static final int MAX_DIG_SITE_SAFE_DROP_BLOCKS = 3;
    private static final int CLEAR_OBSTRUCTION_TICKS = 24;
    private static final double CLEAR_OBSTRUCTION_DISTANCE_SQR = 5.0D * 5.0D;
    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final ToolAi toolAi;
    private final BreakingBlockAi breakingBlockAi;
    private final ClearBlockAi clearBlockAi;
    private final PathNavigationAi pathNavigationAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private BlockPos digOrigin;
    private BlockPos targetPos;
    private Direction digDirection;
    private int goalTicks;
    private int repathTicks;
    private int digSiteWalkTicks;
    private int stairSteps;
    private int stoneBlocksMined;
    private int stoneBlocksNeeded;
    private boolean minedStone;
    private boolean foundGatherStoneTarget;
    private boolean reachedDigSite;
    private boolean finished;

    public DigDownForStoneGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.toolAi = new ToolAi(playerNpc);
        this.breakingBlockAi = new BreakingBlockAi(playerNpc, this.toolAi);
        this.clearBlockAi = new ClearBlockAi(playerNpc, this.breakingBlockAi);
        this.pathNavigationAi = new PathNavigationAi(playerNpc);
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
                || this.playerNpc.getGatherCooldown() > 0) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }
        if (this.shouldStayHomeForWeather(serverLevel)
                || !this.hasPickaxe()
                || !GatherStoneGoal.isStoneSupplyPhaseActive(this.playerNpc, serverLevel)
                || GatherStoneGoal.hasNearbyStoneTarget(this.playerNpc, serverLevel)) {
            return false;
        }

        this.digOrigin = this.findDigOrigin(serverLevel);
        if (this.digOrigin == null) {
            return false;
        }
        this.digDirection = this.chooseDigDirection();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.goalTicks < MAX_GOAL_TICKS
                && this.stairSteps < MAX_STAIR_STEPS
                && this.stoneBlocksMined < this.stoneBlocksNeeded
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && GatherStoneGoal.isStoneSupplyPhaseActive(this.playerNpc, serverLevel)
                && !this.shouldStayHomeForWeather(serverLevel)
                && this.hasPreparedBaseForStone(serverLevel);
    }

    @Override
    public void start() {
        this.goalTicks = 0;
        this.repathTicks = 0;
        this.digSiteWalkTicks = 0;
        this.stairSteps = 0;
        this.stoneBlocksMined = 0;
        this.stoneBlocksNeeded = Math.max(1, this.playerNpc.getCobblestoneSupplyTarget() - this.countStone());
        this.targetPos = null;
        this.minedStone = false;
        this.foundGatherStoneTarget = false;
        this.reachedDigSite = false;
        this.finished = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.digging_down_for_stone");
        this.updateWalkDetail();
        if (this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.moveTo(serverLevel, this.digOrigin);
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.digOrigin == null || this.digDirection == null) {
            this.finished = true;
            return;
        }

        this.goalTicks++;
        if (this.shouldYieldToGatherStone(serverLevel)) {
            this.foundGatherStoneTarget = true;
            this.finished = true;
            this.clearBlockAi.stop();
            this.breakingBlockAi.stop();
            this.playerNpc.getNavigation().stop();
            this.playerNpc.setCurrentAiDetail("stone exposed for gathering");
            return;
        }

        if (this.tickClearBlock(serverLevel)) {
            return;
        }

        if (!this.hasReached(this.digOrigin)) {
            this.digSiteWalkTicks++;
            this.updateWalkDetail();
            if (this.digSiteWalkTicks > MAX_DIG_SITE_WALK_TICKS) {
                this.finished = true;
                return;
            }
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                if (!this.moveTo(serverLevel, this.digOrigin)) {
                    if (this.shouldAbandonUnreachableInitialSite() || !this.startClearingDigRoute(serverLevel)) {
                        this.finished = true;
                    }
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            return;
        }
        this.reachedDigSite = true;
        this.digSiteWalkTicks = 0;

        if (this.targetPos == null) {
            this.targetPos = this.findNextDigTarget(serverLevel);
            if (this.targetPos == null) {
                if (!this.hasReached(this.digOrigin)) {
                    return;
                }
                this.finished = true;
                return;
            }
        }

        this.tickMineTarget(serverLevel);
    }

    @Override
    public void stop() {
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.clearBlockAi.stop();
        this.breakingBlockAi.stop();
        this.toolAi.restoreMainHand();
        if (!this.playerNpc.level().isClientSide) {
            int cooldown = this.foundGatherStoneTarget
                    ? 0
                    : this.minedStone
                    ? COOLDOWN_TICKS
                    : this.reachedDigSite
                    ? COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 12)
                    : FAILED_WALK_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 2);
            this.playerNpc.setGatherCooldown(cooldown);
        }
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.digOrigin = null;
        this.targetPos = null;
        this.digDirection = null;
        this.goalTicks = 0;
        this.repathTicks = 0;
        this.digSiteWalkTicks = 0;
        this.stairSteps = 0;
        this.minedStone = false;
        this.foundGatherStoneTarget = false;
        this.reachedDigSite = false;
        this.finished = false;
    }

    private BlockPos findDigOrigin(ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return null;
        }

        BlockPos center = PlayerNpcHomeUtil.center(home.get());
        List<BlockPos> candidates = new ArrayList<>();
        for (int x = center.getX() - DIG_SITE_MAX_RADIUS; x <= center.getX() + DIG_SITE_MAX_RADIUS; x++) {
            for (int z = center.getZ() - DIG_SITE_MAX_RADIUS; z <= center.getZ() + DIG_SITE_MAX_RADIUS; z++) {
                int dx = x - center.getX();
                int dz = z - center.getZ();
                int distSqr = dx * dx + dz * dz;
                if (distSqr < DIG_SITE_MIN_RADIUS * DIG_SITE_MIN_RADIUS || distSqr > DIG_SITE_MAX_RADIUS * DIG_SITE_MAX_RADIUS) {
                    continue;
                }
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (this.canStandAt(serverLevel, candidate)
                        && this.isAwayFromHome(candidate)
                        && this.isInsideResourceRadius(candidate)) {
                    candidates.add(candidate.immutable());
                }
            }
        }

        int checks = Math.min(candidates.size(), 32);
        for (int i = 0; i < checks && !candidates.isEmpty(); i++) {
            BlockPos candidate = candidates.remove(this.playerNpc.getRandom().nextInt(candidates.size()));
            if (this.pathNavigationAi.canReachOrSafelyDropTo(serverLevel, candidate, MAX_DIG_SITE_SAFE_DROP_BLOCKS)) {
                return candidate;
            }
        }
        return null;
    }

    private Direction chooseDigDirection() {
        Direction[] directions = Direction.Plane.HORIZONTAL.stream().toArray(Direction[]::new);
        return directions[this.playerNpc.getRandom().nextInt(directions.length)];
    }

    private BlockPos findNextDigTarget(ServerLevel serverLevel) {
        BlockPos feet = this.playerNpc.blockPosition();
        BlockPos forwardHead = feet.relative(this.digDirection);
        BlockPos forwardFeet = forwardHead.below();

        if (this.shouldYieldToGatherStone(serverLevel)) {
            this.foundGatherStoneTarget = true;
            this.finished = true;
            return null;
        }

        if (this.isDiggable(serverLevel, forwardHead, serverLevel.getBlockState(forwardHead))) {
            return forwardHead.immutable();
        }
        if (this.isDiggable(serverLevel, forwardFeet, serverLevel.getBlockState(forwardFeet))) {
            return forwardFeet.immutable();
        }
        if (this.canStandAt(serverLevel, forwardFeet)) {
            this.digOrigin = forwardFeet.immutable();
            this.reachedDigSite = false;
            this.digSiteWalkTicks = 0;
            this.stairSteps++;
            this.moveTo(serverLevel, this.digOrigin);
            return null;
        }
        return null;
    }

    private void tickMineTarget(ServerLevel serverLevel) {
        BlockState state = serverLevel.getBlockState(this.targetPos);
        if (!this.isDiggable(serverLevel, this.targetPos, state)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.breakingBlockAi.stop();
            this.targetPos = null;
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        if (this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D) > BREAK_DISTANCE_SQR) {
            this.breakingBlockAi.stop();
            this.targetPos = null;
            return;
        }

        this.toolAi.equipBestToolFor(state);
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, this.targetPos, state);
        boolean targetIsStone = this.isStoneMaterial(state);
        BlockPos minedPos = this.targetPos;
        BreakingBlockAi.TickResult result = this.breakingBlockAi.tick(
                serverLevel,
                this.targetPos,
                targetState -> this.isDiggable(serverLevel, minedPos, targetState),
                requiredMineTicks,
                targetIsStone ? "mining dig-site stone" : "digging stone search path"
        );
        if (result == BreakingBlockAi.TickResult.RUNNING) {
            return;
        }

        if (result == BreakingBlockAi.TickResult.DONE && targetIsStone) {
            this.minedStone = true;
            this.stoneBlocksMined++;
        }
        this.playerNpc.clearBlockBreakProgress(minedPos);
        this.targetPos = null;
    }

    private boolean isDiggable(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !state.isAir()
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && serverLevel.getBlockEntity(pos) == null
                && !this.isProtectedHomeBlock(pos);
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

    private boolean tickClearBlock(ServerLevel serverLevel) {
        if (!this.clearBlockAi.isRunning()) {
            return false;
        }

        ClearBlockAi.TickResult result = this.clearBlockAi.tick(serverLevel);
        if (result == ClearBlockAi.TickResult.RUNNING) {
            return true;
        }

        this.repathTicks = 0;
        return false;
    }

    private boolean startClearingDigRoute(ServerLevel serverLevel) {
        if (this.digOrigin == null) {
            return false;
        }

        List<BlockPos> candidates = new ArrayList<>();
        BlockPos feet = this.playerNpc.blockPosition();
        addBodyColumn(candidates, feet);
        addBodyColumn(candidates, this.digOrigin);
        addLineCandidates(candidates, feet.above(), this.digOrigin.above(), 12);
        addLineCandidates(candidates, feet, this.digOrigin, 12);
        addLocalRouteCandidates(candidates, feet, this.digOrigin);
        candidates.removeIf(pos -> this.isProtectedHomeBlock(pos));

        return this.clearBlockAi.startNearest(
                serverLevel,
                candidates,
                this::isClearablePathState,
                "clearing dig path",
                CLEAR_OBSTRUCTION_TICKS,
                CLEAR_OBSTRUCTION_DISTANCE_SQR
        );
    }

    private boolean isClearablePathState(BlockState state) {
        return ClearBlockAi.isPhysicalObstructionState(state);
    }

    private boolean moveTo(ServerLevel serverLevel, BlockPos pos) {
        if (pos == null) {
            return false;
        }
        boolean moved = this.stairSteps == 0
                ? this.pathNavigationAi.moveTo(serverLevel, pos, this.speed, MAX_DIG_SITE_SAFE_DROP_BLOCKS)
                : this.pathNavigationAi.moveToExact(serverLevel, pos, this.speed, MAX_DIG_SITE_SAFE_DROP_BLOCKS);
        if (moved) {
            return true;
        }
        if (this.playerNpc.blockPosition().distSqr(pos) <= LOCAL_STEP_DISTANCE_SQR) {
            this.playerNpc.getMoveControl().setWantedPosition(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, this.speed);
            return true;
        }
        return false;
    }

    private boolean shouldAbandonUnreachableInitialSite() {
        return this.stairSteps == 0
                && this.digOrigin != null
                && this.playerNpc.blockPosition().distSqr(this.digOrigin) > LOCAL_STEP_DISTANCE_SQR;
    }

    private boolean shouldYieldToGatherStone(ServerLevel serverLevel) {
        return GatherStoneGoal.hasNearbyStoneTarget(this.playerNpc, serverLevel);
    }

    private boolean hasReached(BlockPos pos) {
        return pos != null && this.playerNpc.blockPosition().equals(pos);
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos);
    }

    private boolean isAwayFromHome(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return false;
        }
        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        BlockPos homeCenter = PlayerNpcHomeUtil.center(homeArea);
        return homeCenter.distSqr(pos) >= MIN_HOME_DISTANCE * MIN_HOME_DISTANCE;
    }

    private boolean isInsideResourceRadius(BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideActivityRadius(this.playerNpc, pos, LOCAL_RESOURCE_RADIUS, true);
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos);
    }

    private boolean hasPreparedBaseForStone(ServerLevel serverLevel) {
        return PlayerNpcHomeUtil.getHome(this.playerNpc).isPresent()
                && !TerraformBuildSiteGoal.hasActionablePrepWork(this.playerNpc, serverLevel);
    }

    private boolean shouldStayHomeForWeather(ServerLevel serverLevel) {
        return PlayerNpcHomeUtil.getHome(this.playerNpc).isPresent()
                && (serverLevel.isNight() || serverLevel.isThundering());
    }

    private boolean hasPickaxe() {
        return this.playerNpc.getMainHandItem().getItem() instanceof PickaxeItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof PickaxeItem);
    }

    private int countStone() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    private boolean isStoneMaterial(BlockState state) {
        return state.is(Blocks.STONE)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.DEEPSLATE)
                || state.is(Blocks.COBBLED_DEEPSLATE)
                || state.is(BlockTags.MINEABLE_WITH_PICKAXE);
    }

    private void updateWalkDetail() {
        if (this.digOrigin == null) {
            this.playerNpc.setCurrentAiDetail("walking to dig site");
            return;
        }
        this.playerNpc.setCurrentAiDetail("walking to dig site @ "
                + this.digOrigin.getX() + " "
                + this.digOrigin.getY() + " "
                + this.digOrigin.getZ());
    }

    private static void addBodyColumn(List<BlockPos> candidates, BlockPos feet) {
        if (feet == null) {
            return;
        }
        candidates.add(feet);
        candidates.add(feet.above());
    }

    private static void addLocalRouteCandidates(List<BlockPos> candidates, BlockPos feet, BlockPos target) {
        if (feet == null) {
            return;
        }

        if (target == null) {
            return;
        }

        int stepX = Integer.compare(target.getX(), feet.getX());
        int stepZ = Integer.compare(target.getZ(), feet.getZ());
        if (stepX != 0) {
            addBodyColumn(candidates, feet.offset(stepX, 0, 0));
            candidates.add(feet.offset(stepX, -1, 0));
        }
        if (stepZ != 0) {
            addBodyColumn(candidates, feet.offset(0, 0, stepZ));
            candidates.add(feet.offset(0, -1, stepZ));
        }
        if (stepX != 0 && stepZ != 0) {
            addBodyColumn(candidates, feet.offset(stepX, 0, stepZ));
            candidates.add(feet.offset(stepX, -1, stepZ));
        }
    }

    private static void addLineCandidates(List<BlockPos> candidates, BlockPos start, BlockPos target, int maxSteps) {
        if (start == null || target == null) {
            return;
        }
        double dx = target.getX() - start.getX();
        double dy = target.getY() - start.getY();
        double dz = target.getZ() - start.getZ();
        int steps = Math.max(1, Math.min(maxSteps, (int) Math.ceil(Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))))));
        for (int i = 1; i <= steps; i++) {
            double progress = i / (double) steps;
            int x = start.getX() + (int) Math.round(dx * progress);
            int y = start.getY() + (int) Math.round(dy * progress);
            int z = start.getZ() + (int) Math.round(dz * progress);
            candidates.add(new BlockPos(x, y, z));
        }
    }
}
