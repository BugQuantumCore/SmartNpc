package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class ExploreBiomeForLogsGoal extends Goal {
    private static final int LOG_SCAN_RADIUS = 48;
    private static final int LOCAL_RESOURCE_RADIUS = 96;
    private static final int LOG_SCAN_DOWN = 2;
    private static final int LOG_SCAN_UP = 12;
    private static final int MAX_LOG_PATH_CHECKS_PER_SCAN = 16;
    private static final int MIN_BUILD_SUPPLY = 24;
    private static final int MIN_TRAVEL_DISTANCE = 42;
    private static final int MAX_TRAVEL_DISTANCE = 86;
    private static final int MAX_LATERAL_OFFSET = 18;
    private static final int MAX_TARGET_ATTEMPTS = 18;
    private static final int MAX_EXPLORE_TICKS = 20 * 45;
    private static final int REPATH_INTERVAL_TICKS = 20 * 3;
    private static final int LOG_SCAN_INTERVAL_TICKS = 20;
    private static final int COOLDOWN_TICKS = 20 * 6;
    private static final int FOUND_LOG_COOLDOWN_TICKS = 10;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private BlockPos travelTarget;
    private double directionX;
    private double directionZ;
    private int exploreTicks;
    private int repathTicks;
    private int logScanTicks;
    private boolean foundLog;

    public ExploreBiomeForLogsGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
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
                || this.playerNpc.getBiomeExploreCooldown() > 0
                || this.inventoryIsMostlyFull()
                || !this.needsSearchSupply()) {
            return false;
        }

        this.chooseDirection();
        this.travelTarget = this.findTravelTarget(serverLevel);
        if (this.travelTarget == null) {
            this.playerNpc.setBiomeExploreCooldown(20);
        }
        return this.travelTarget != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.travelTarget != null
                && this.exploreTicks > 0
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && !this.foundLog
                && !this.inventoryIsMostlyFull();
    }

    @Override
    public void start() {
        this.exploreTicks = MAX_EXPLORE_TICKS;
        this.repathTicks = 0;
        this.logScanTicks = 0;
        this.foundLog = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.exploring_biome");
        this.updateTaskDetail();
        this.moveToTravelTarget();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.travelTarget == null) {
            return;
        }

        this.exploreTicks--;
        if (this.logScanTicks-- <= 0) {
            this.logScanTicks = LOG_SCAN_INTERVAL_TICKS;
            if (this.hasNearbyLog(serverLevel)) {
                this.foundLog = true;
                this.playerNpc.setGatherCooldown(0);
                this.playerNpc.getNavigation().stop();
                return;
            }
        }

        this.playerNpc.getLookControl().setLookAt(
                this.travelTarget.getX() + 0.5D,
                this.travelTarget.getY(),
                this.travelTarget.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
            if (this.playerNpc.distanceToSqr(this.travelTarget.getX() + 0.5D, this.travelTarget.getY(), this.travelTarget.getZ() + 0.5D) < 6.0D * 6.0D
                    || this.playerNpc.getNavigation().isDone()
                    || this.playerNpc.getNavigation().isStuck()) {
                BlockPos nextTarget = this.findTravelTarget(serverLevel);
                if (nextTarget != null) {
                    this.travelTarget = nextTarget;
                }
            }
            this.moveToTravelTarget();
            this.repathTicks = REPATH_INTERVAL_TICKS;
        }
        this.updateTaskDetail();
    }

    @Override
    public void stop() {
        if (!this.playerNpc.level().isClientSide) {
            int cooldown = this.foundLog ? FOUND_LOG_COOLDOWN_TICKS : COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 6);
            this.playerNpc.setBiomeExploreCooldown(cooldown);
        }
        this.travelTarget = null;
        this.directionX = 0.0D;
        this.directionZ = 0.0D;
        this.exploreTicks = 0;
        this.repathTicks = 0;
        this.logScanTicks = 0;
        this.foundLog = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void chooseDirection() {
        double angle = this.playerNpc.getRandom().nextDouble() * Math.PI * 2.0D;
        this.directionX = Math.cos(angle);
        this.directionZ = Math.sin(angle);
    }

    private BlockPos findTravelTarget(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        double sideX = -this.directionZ;
        double sideZ = this.directionX;
        int distanceRange = MAX_TRAVEL_DISTANCE - MIN_TRAVEL_DISTANCE + 1;
        int lateralRange = MAX_LATERAL_OFFSET * 2 + 1;

        for (int attempt = 0; attempt < MAX_TARGET_ATTEMPTS; attempt++) {
            int distance = MIN_TRAVEL_DISTANCE + this.playerNpc.getRandom().nextInt(distanceRange);
            int lateral = this.playerNpc.getRandom().nextInt(lateralRange) - MAX_LATERAL_OFFSET;
            int x = (int) Math.floor(origin.getX() + this.directionX * distance + sideX * lateral);
            int z = (int) Math.floor(origin.getZ() + this.directionZ * distance + sideZ * lateral);
            int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos candidate = new BlockPos(x, y, z);
            if (this.canReachTravelTarget(serverLevel, candidate)) {
                return candidate.immutable();
            }
        }

        return null;
    }

    private boolean canReachTravelTarget(ServerLevel serverLevel, BlockPos candidate) {
        if (!this.canStandAt(serverLevel, candidate) || !this.isInsideResourceRadius(candidate)) {
            return false;
        }

        Path path = this.playerNpc.getNavigation().createPath(candidate, 0);
        return path != null && path.canReach();
    }

    private void moveToTravelTarget() {
        if (this.travelTarget != null) {
            Path path = this.playerNpc.getNavigation().createPath(this.travelTarget, 0);
            if (path == null || !path.canReach()) {
                this.travelTarget = null;
                return;
            }
            this.playerNpc.getNavigation().moveTo(path, this.speed);
        }
    }

    private boolean hasNearbyLog(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        List<BlockPos> logCandidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-LOG_SCAN_RADIUS, -LOG_SCAN_DOWN, -LOG_SCAN_RADIUS),
                center.offset(LOG_SCAN_RADIUS, LOG_SCAN_UP, LOG_SCAN_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (this.isProtectedHomeBlock(immutable) || !this.isInsideResourceRadius(immutable)) {
                continue;
            }
            if (serverLevel.getBlockState(immutable).is(BlockTags.LOGS)) {
                logCandidates.add(immutable);
            }
        }

        logCandidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> center.distSqr(pos))
                .thenComparingInt(BlockPos::getY));
        int checked = 0;
        for (BlockPos candidate : logCandidates) {
            if (this.hasAccessibleStandNear(serverLevel, candidate)) {
                return true;
            }
            if (++checked >= MAX_LOG_PATH_CHECKS_PER_SCAN) {
                break;
            }
        }
        return false;
    }

    private boolean hasAccessibleStandNear(ServerLevel serverLevel, BlockPos logPos) {
        for (BlockPos pos : BlockPos.betweenClosed(logPos.offset(-2, -1, -2), logPos.offset(2, 1, 2))) {
            if (this.canReachStand(serverLevel, pos.immutable())) {
                return true;
            }
        }
        return false;
    }

    private boolean canReachStand(ServerLevel serverLevel, BlockPos pos) {
        if (!this.canStandAt(serverLevel, pos)) {
            return false;
        }

        if (this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) <= 2.0D * 2.0D) {
            return true;
        }

        Path path = this.playerNpc.getNavigation().createPath(pos, 0);
        return path != null && path.canReach();
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(this.playerNpc);
        return homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), pos);
    }

    private boolean isInsideResourceRadius(BlockPos pos) {
        return PlayerNpcHomeUtil.isInsideActivityRadius(this.playerNpc, pos, LOCAL_RESOURCE_RADIUS, true);
    }

    private boolean needsBuildSupply() {
        return this.countBuildSupply() < MIN_BUILD_SUPPLY;
    }

    private boolean needsSearchSupply() {
        return this.needsBuildSupply()
                || this.countRawLogs() < this.playerNpc.getRawLogReserveTarget()
                || this.countUsableWoodSupply() < this.playerNpc.getWoodSupplyTarget();
    }

    private boolean inventoryIsMostlyFull() {
        int freeSlots = 0;
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                freeSlots++;
            }
        }
        return freeSlots <= 2;
    }

    private int countBuildSupply() {
        SimpleContainer inventory = this.playerNpc.getInventory();
        int placeableBlocks = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && this.isBuildingBlock(stack)) {
                placeableBlocks += stack.getCount();
            }
        }

        return Math.max(placeableBlocks, this.countUsableWoodSupply());
    }

    private int countUsableWoodSupply() {
        return PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget());
    }

    private int countRawLogs() {
        int count = 0;
        SimpleContainer inventory = this.playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.is(ItemTags.LOGS)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean isBuildingBlock(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem
                && !stack.is(Items.CRAFTING_TABLE)
                && !stack.is(Items.FURNACE)
                && !stack.is(Items.CHEST)
                && !(stack.getItem() instanceof BedItem);
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

    private void updateTaskDetail() {
        if (this.travelTarget == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        this.playerNpc.setCurrentAiDetail(String.format(
                Locale.ROOT,
                "searching logs toward %d %d %d",
                this.travelTarget.getX(),
                this.travelTarget.getY(),
                this.travelTarget.getZ()
        ));
    }
}
