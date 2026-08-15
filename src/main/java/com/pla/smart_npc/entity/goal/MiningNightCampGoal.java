package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.FurnaceAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.entity.ai.SneakingAi;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

public class MiningNightCampGoal extends Goal {
    public static final String AI_STATE = "ai.player_npc.mining_night_camp";

    private static final int FURNACE_SCAN_RADIUS = 6;
    private static final int FURNACE_PLACEMENT_RADIUS = 3;
    private static final int CAMP_WALK_RADIUS = 5;
    private static final int CAMP_WALK_VERTICAL_RADIUS = 2;
    private static final int ACTION_DELAY_TICKS = 12;
    private static final int FURNACE_ACTION_COOLDOWN_TICKS = 20;
    private static final int FURNACE_FAIL_COOLDOWN_TICKS = 20 * 3;
    private static final int MAX_FURNACE_RECOVERY_TICKS = 20 * 10;
    private static final double FURNACE_USE_DISTANCE_SQR = 2.25D * 2.25D;
    private static final double FURNACE_STAND_REACHED_SQR = 1.25D * 1.25D;
    private static final int TORCH_CHECK_INTERVAL_TICKS = 20 * 5;
    private static final int TORCH_NEARBY_RADIUS = 6;
    private static final int TORCH_LOW_LIGHT_LEVEL = 7;
    private static final int MIN_ACTIVITY_TICKS = 20 * 4;
    private static final int RANDOM_ACTIVITY_TICKS = 20 * 6;
    private static final int MIN_STATIONARY_TICKS = 20 * 2;
    private static final int RANDOM_STATIONARY_TICKS = 20 * 4;
    private static final int MIN_LOOK_TICKS = 20;
    private static final int RANDOM_LOOK_TICKS = 20 * 3;
    private static final int WALK_REPATH_TICKS = 20;
    private static final String CAMP_FURNACE_X = "PlayerNpcNightCampFurnaceX";
    private static final String CAMP_FURNACE_Y = "PlayerNpcNightCampFurnaceY";
    private static final String CAMP_FURNACE_Z = "PlayerNpcNightCampFurnaceZ";
    private static final String CAMP_FURNACE_DIMENSION = "PlayerNpcNightCampFurnaceDimension";
    private static final String CAMP_FURNACE_OWNER = "PlayerNpcNightCampFurnaceOwner";

    private final PlayerNpcEntity playerNpc;
    private final FurnaceAi furnaceAi;
    private final PlacingBlockAi placingBlockAi;
    private final SneakingAi sneakingAi;
    private final double speed;
    private BlockPos campCenter;
    private BlockPos furnacePos;
    private BlockPos furnaceStandPos;
    private BlockPos walkTarget;
    private FurnaceMode furnaceMode = FurnaceMode.NONE;
    private ActivityMode activityMode = ActivityMode.LOOK;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int actionDelayTicks;
    private int furnaceCooldownTicks;
    private int torchCheckTicks;
    private int activityTicks;
    private int stationaryTicks;
    private int lookTicks;
    private int repathTicks;
    private int furnaceRecoveryTicks;
    private boolean finished;
    private boolean placedTorch;
    private boolean walkSneaking;
    private boolean usingTemporaryMainHand;
    private boolean returnTemporaryMainHandOnRestore;

    public MiningNightCampGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.furnaceAi = new FurnaceAi(playerNpc);
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.sneakingAi = new SneakingAi(playerNpc);
        this.speed = Math.min(speed, 1.0D);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean shouldPauseMiningForNightCamp(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null || serverLevel == null) {
            return false;
        }

        boolean miningJob = GatherStoneGoal.isMiningJobActive(playerNpc);
        boolean fishingJob = isFishingNightCampJob(playerNpc);
        return miningJob
                && (serverLevel.isNight() || serverLevel.isThundering())
                && !serverLevel.canSeeSky(playerNpc.blockPosition().above())
                || fishingJob && serverLevel.isNight();
    }

    private static boolean isFishingNightCampJob(PlayerNpcEntity playerNpc) {
        return playerNpc != null
                && playerNpc.hasInterest(PlayerNpcInterest.FISHING)
                && playerNpc.isDailyJobActive(PlayerNpcInterest.FISHING);
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getUpwardEscapeTarget() != null) {
            return false;
        }

        this.resetPlan();
        this.campCenter = this.playerNpc.blockPosition().immutable();
        this.adoptNearbyLegacyTemporaryFurnace(serverLevel);
        CampFurnaceRef ownedFurnace = this.resolveOwnedCampFurnace();
        if (ownedFurnace != null) {
            if (!ownedFurnace.level().hasChunkAt(ownedFurnace.pos())) {
                return false;
            }
            if (!this.isOwnedCampFurnace(ownedFurnace)) {
                this.clearCampFurnaceOwnership(ownedFurnace.pos());
            } else if (!shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)
                    || !this.canReuseOwnedFurnace(serverLevel, ownedFurnace)) {
                return this.planFurnaceRecovery(ownedFurnace);
            }
        }
        if (!shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)) {
            return false;
        }
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null
                && this.playerNpc.getUpwardEscapeTarget() == null
                && this.playerNpc.level() instanceof ServerLevel serverLevel
                && (this.furnaceMode == FurnaceMode.RECOVER
                || shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)
                || this.hasOwnedCampFurnaceReference());
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.finished = false;
        this.actionDelayTicks = 0;
        this.furnaceCooldownTicks = 0;
        this.torchCheckTicks = 0;
        this.activityMode = this.randomActivityMode(null);
        this.activityTicks = this.nextActivityTicks();
        this.stationaryTicks = this.nextStationaryTicks();
        this.lookTicks = 0;
        this.repathTicks = 0;
        this.walkTarget = null;
        this.walkSneaking = false;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState(AI_STATE);
        this.playerNpc.setCurrentAiDetail("setting up mining camp");
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.finished = true;
            return;
        }

        this.playerNpc.setCurrentAiState(AI_STATE);
        if (this.furnaceMode == FurnaceMode.RECOVER) {
            this.tickRecoverFurnace(serverLevel);
            return;
        }
        if (!shouldPauseMiningForNightCamp(this.playerNpc, serverLevel)) {
            CampFurnaceRef ownedFurnace = this.resolveOwnedCampFurnace();
            if (ownedFurnace == null || !ownedFurnace.level().hasChunkAt(ownedFurnace.pos())) {
                this.finished = true;
                return;
            }
            if (!this.isOwnedCampFurnace(ownedFurnace)) {
                this.clearCampFurnaceOwnership(ownedFurnace.pos());
                this.finished = true;
                return;
            }
            this.planFurnaceRecovery(ownedFurnace);
            this.tickRecoverFurnace(serverLevel);
            return;
        }
        if (this.tickFurnaceWork(serverLevel)) {
            return;
        }
        if (this.tickTorchPlacement(serverLevel)) {
            return;
        }

        this.tickCampActivity(serverLevel);
    }

    @Override
    public void stop() {
        this.restorePreviousMainHand();
        this.sneakingAi.stopSneaking();
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.resetPlan();
    }

    private boolean tickFurnaceWork(ServerLevel serverLevel) {
        if (this.furnaceCooldownTicks > 0) {
            this.furnaceCooldownTicks--;
        }

        if (this.furnaceMode == FurnaceMode.PLACE) {
            this.tickPlaceFurnace(serverLevel);
            return true;
        }
        if (this.furnaceMode == FurnaceMode.INTERACT) {
            this.tickUseFurnace(serverLevel);
            return true;
        }
        if (this.furnaceCooldownTicks > 0) {
            return false;
        }

        BlockPos workFurnace = this.findWorkFurnace(serverLevel);
        if (workFurnace != null && this.planFurnaceInteraction(serverLevel, workFurnace)) {
            this.tickUseFurnace(serverLevel);
            return true;
        }

        CampFurnaceRef ownedFurnace = this.resolveOwnedCampFurnace();
        if (ownedFurnace != null
                && ownedFurnace.level().hasChunkAt(ownedFurnace.pos())
                && this.isOwnedCampFurnace(ownedFurnace)) {
            return false;
        }
        BlockPos trackedTemporary = this.getTemporaryFurnacePos();
        if (trackedTemporary != null) {
            return false;
        }

        if (this.furnaceAi.shouldPlaceFurnaceForWork(serverLevel)) {
            BlockPos placement = this.findFurnacePlacement(serverLevel);
            if (placement != null && this.planFurnacePlacement(serverLevel, placement)) {
                this.tickPlaceFurnace(serverLevel);
                return true;
            }
        }
        return false;
    }

    private void tickUseFurnace(ServerLevel serverLevel) {
        if (!serverLevel.getBlockState(this.furnacePos).is(Blocks.FURNACE)
                || !(serverLevel.getBlockEntity(this.furnacePos) instanceof FurnaceBlockEntity furnace)) {
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }

        if (!this.ensureFurnaceStand(serverLevel)) {
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }

        this.lookAtFurnace();
        if (!this.isAtFurnaceStand()) {
            this.playerNpc.setCurrentAiDetail(this.detail("walking to mining camp furnace", this.furnacePos));
            if (!this.moveToFurnaceStand()) {
                this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.actionDelayTicks++ < ACTION_DELAY_TICKS) {
            this.playerNpc.setCurrentAiDetail(this.detail("using mining camp furnace", this.furnacePos));
            return;
        }
        this.actionDelayTicks = 0;

        boolean moved = this.furnaceAi.takeOutput(serverLevel, this.furnacePos, furnace)
                || this.furnaceAi.fillFurnace(serverLevel, this.furnacePos, furnace);
        this.clearActiveFurnaceAction(moved ? FURNACE_ACTION_COOLDOWN_TICKS : FURNACE_FAIL_COOLDOWN_TICKS);
    }

    private void tickPlaceFurnace(ServerLevel serverLevel) {
        if (!this.ensureFurnaceStand(serverLevel)) {
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }

        this.lookAtFurnace();
        if (!this.isAtFurnaceStand()) {
            this.playerNpc.setCurrentAiDetail(this.detail("walking to furnace placement", this.furnacePos));
            if (!this.moveToFurnaceStand()) {
                this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.actionDelayTicks++ < ACTION_DELAY_TICKS) {
            this.playerNpc.setCurrentAiDetail(this.detail("preparing furnace", this.furnacePos));
            return;
        }
        this.actionDelayTicks = 0;

        ItemStack furnace = this.takeOrCraftFurnace();
        if (furnace.isEmpty()) {
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }
        if (!this.canPlaceFurnaceAt(serverLevel, this.furnacePos)) {
            this.returnStack(furnace);
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }

        this.showPlacementItem(furnace);
        if (!this.placingBlockAi.placeBlock(serverLevel, this.furnacePos, Blocks.FURNACE.defaultBlockState())) {
            this.returnStack(furnace);
            this.restorePreviousMainHand();
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }

        if (!this.saveTemporaryFurnace(serverLevel, this.furnacePos)) {
            boolean removed = serverLevel.removeBlock(this.furnacePos, false);
            this.finishPlacementMainHand();
            if (removed) {
                this.returnStack(furnace);
            }
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
            return;
        }

        this.finishPlacementMainHand();
        this.furnaceMode = FurnaceMode.INTERACT;
        this.furnaceCooldownTicks = 0;
        this.furnaceStandPos = this.findFurnaceStand(serverLevel, this.furnacePos);
        if (this.furnaceStandPos == null) {
            this.clearActiveFurnaceAction(FURNACE_FAIL_COOLDOWN_TICKS);
        }
    }

    private boolean tickTorchPlacement(ServerLevel serverLevel) {
        if (this.placedTorch) {
            return false;
        }
        if (this.torchCheckTicks > 0) {
            this.torchCheckTicks--;
            return false;
        }
        this.torchCheckTicks = TORCH_CHECK_INTERVAL_TICKS;

        BlockPos torchPos = this.findTorchPlacement(serverLevel);
        if (torchPos == null) {
            return false;
        }

        ItemStack torch = this.takeOrCraftTorch();
        if (torch.isEmpty()) {
            return false;
        }

        this.playerNpc.getNavigation().stop();
        this.lookAt(torchPos);
        this.showPlacementItem(torch);
        if (!this.placingBlockAi.placeBlock(serverLevel, torchPos, Blocks.TORCH.defaultBlockState())) {
            this.returnStack(torch);
            this.restorePreviousMainHand();
            return false;
        }

        this.finishPlacementMainHand();
        this.placedTorch = true;
        this.playerNpc.setCurrentAiDetail(this.detail("placing mining camp torch", torchPos));
        return true;
    }

    private void tickCampActivity(ServerLevel serverLevel) {
        if (this.activityTicks-- <= 0) {
            this.switchCampActivity();
        }

        if (this.activityMode == ActivityMode.WALK) {
            this.tickWalkCamp(serverLevel);
        } else if (this.activityMode == ActivityMode.SNEAK) {
            this.playerNpc.getNavigation().stop();
            this.sneakingAi.setSneaking(true);
            this.playerNpc.setCurrentAiDetail("sneaking around mining camp");
            this.lookAroundCamp();
        } else {
            this.playerNpc.getNavigation().stop();
            this.sneakingAi.setSneaking(false);
            this.playerNpc.setCurrentAiDetail("watching mining camp");
            this.lookAroundCamp();
        }
    }

    private void tickWalkCamp(ServerLevel serverLevel) {
        this.sneakingAi.setSneaking(this.walkSneaking);
        this.lookAroundCamp();

        if (this.walkTarget == null || this.hasReachedWalkTarget()) {
            this.walkTarget = null;
            this.playerNpc.getNavigation().stop();
            this.playerNpc.setCurrentAiDetail("walking around mining camp");
            if (this.stationaryTicks-- > 0) {
                return;
            }

            this.stationaryTicks = this.nextStationaryTicks();
            this.walkTarget = this.findCampWalkTarget(serverLevel);
            this.repathTicks = 0;
            this.walkSneaking = this.playerNpc.getRandom().nextFloat() < 0.35F;
            if (this.walkTarget == null) {
                return;
            }
        }

        this.playerNpc.setCurrentAiDetail(this.detail("walking around mining camp", this.walkTarget));
        if (this.repathTicks-- > 0 && !this.playerNpc.getNavigation().isDone()) {
            return;
        }
        this.repathTicks = WALK_REPATH_TICKS;
        Path path = this.playerNpc.getNavigation().createPath(this.walkTarget, 0);
        if (path == null || !path.canReach()) {
            this.walkTarget = null;
            return;
        }
        this.playerNpc.getNavigation().moveTo(path, this.speed);
    }

    private BlockPos findWorkFurnace(ServerLevel serverLevel) {
        CampFurnaceRef ownedFurnace = this.resolveOwnedCampFurnace();
        if (ownedFurnace != null
                && ownedFurnace.level() == serverLevel
                && ownedFurnace.level().hasChunkAt(ownedFurnace.pos())
                && this.isOwnedCampFurnace(ownedFurnace)
                && this.furnaceAi.hasFurnaceWork(serverLevel, ownedFurnace.pos())) {
            return ownedFurnace.pos();
        }

        BlockPos temporary = this.getTemporaryFurnacePos();
        if (temporary != null) {
            if (serverLevel.getBlockState(temporary).is(Blocks.FURNACE)) {
                if (this.furnaceAi.hasFurnaceWork(serverLevel, temporary)) {
                    return temporary.immutable();
                }
            } else {
                this.clearTemporaryFurnace();
            }
        }

        BlockPos center = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-FURNACE_SCAN_RADIUS, -2, -FURNACE_SCAN_RADIUS),
                center.offset(FURNACE_SCAN_RADIUS, 2, FURNACE_SCAN_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (serverLevel.getBlockState(immutable).is(Blocks.FURNACE)
                    && this.furnaceAi.hasFurnaceWork(serverLevel, immutable)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean planFurnaceInteraction(ServerLevel serverLevel, BlockPos pos) {
        BlockPos stand = this.findFurnaceStand(serverLevel, pos);
        if (stand == null) {
            return false;
        }
        this.furnaceMode = FurnaceMode.INTERACT;
        this.furnacePos = pos.immutable();
        this.furnaceStandPos = stand;
        this.actionDelayTicks = 0;
        return true;
    }

    private boolean planFurnacePlacement(ServerLevel serverLevel, BlockPos pos) {
        BlockPos stand = this.findFurnaceStand(serverLevel, pos);
        if (stand == null) {
            return false;
        }
        this.furnaceMode = FurnaceMode.PLACE;
        this.furnacePos = pos.immutable();
        this.furnaceStandPos = stand;
        this.actionDelayTicks = 0;
        return true;
    }

    private boolean planFurnaceRecovery(CampFurnaceRef ownedFurnace) {
        if (ownedFurnace == null || !ownedFurnace.level().hasChunkAt(ownedFurnace.pos())) {
            return false;
        }
        this.furnaceMode = FurnaceMode.RECOVER;
        this.furnacePos = ownedFurnace.pos();
        this.furnaceStandPos = ownedFurnace.level() == this.playerNpc.level()
                ? this.findFurnaceStand(ownedFurnace.level(), ownedFurnace.pos())
                : null;
        this.actionDelayTicks = 0;
        this.furnaceRecoveryTicks = 0;
        return true;
    }

    private void tickRecoverFurnace(ServerLevel currentLevel) {
        CampFurnaceRef ownedFurnace = this.resolveOwnedCampFurnace();
        if (ownedFurnace == null) {
            this.finished = true;
            return;
        }
        if (!ownedFurnace.level().hasChunkAt(ownedFurnace.pos())) {
            this.finished = true;
            return;
        }
        if (!this.isOwnedCampFurnace(ownedFurnace)) {
            this.clearCampFurnaceOwnership(ownedFurnace.pos());
            this.finished = true;
            return;
        }

        this.furnacePos = ownedFurnace.pos();
        this.furnaceRecoveryTicks++;
        if (ownedFurnace.level() != currentLevel) {
            this.finished = this.reclaimOwnedCampFurnace(ownedFurnace);
            return;
        }

        this.lookAtFurnace();
        if (!this.ensureFurnaceStand(currentLevel) || !this.isAtFurnaceStand()) {
            this.playerNpc.setCurrentAiDetail(this.detail("recovering temporary camp furnace", this.furnacePos));
            if (this.furnaceStandPos != null) {
                this.moveToFurnaceStand();
            }
            if (this.furnaceRecoveryTicks >= MAX_FURNACE_RECOVERY_TICKS) {
                this.finished = this.reclaimOwnedCampFurnace(ownedFurnace);
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.actionDelayTicks++ < ACTION_DELAY_TICKS) {
            this.playerNpc.setCurrentAiDetail(this.detail("packing temporary camp furnace", this.furnacePos));
            return;
        }
        this.finished = this.reclaimOwnedCampFurnace(ownedFurnace);
    }

    private BlockPos findFurnacePlacement(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(center.relative(direction));
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -FURNACE_PLACEMENT_RADIUS; dx <= FURNACE_PLACEMENT_RADIUS; dx++) {
                for (int dz = -FURNACE_PLACEMENT_RADIUS; dz <= FURNACE_PLACEMENT_RADIUS; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) <= 1) {
                        continue;
                    }
                    candidates.add(center.offset(dx, dy, dz));
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canPlaceFurnaceAt(serverLevel, immutable) && this.findFurnaceStand(serverLevel, immutable) != null) {
                return immutable;
            }
        }
        return null;
    }

    private boolean canPlaceFurnaceAt(ServerLevel serverLevel, BlockPos pos) {
        BlockState furnaceState = Blocks.FURNACE.defaultBlockState();
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).canBeReplaced()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below())
                && this.placingBlockAi.canPlaceWithoutClipping(serverLevel, pos, furnaceState);
    }

    private BlockPos findFurnaceStand(ServerLevel serverLevel, BlockPos pos) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(pos.relative(direction));
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!PathNavigationAi.canStandAt(serverLevel, immutable)
                    || this.distanceToFurnaceSqr(immutable, pos) > FURNACE_USE_DISTANCE_SQR) {
                continue;
            }
            if (immutable.equals(center)) {
                return immutable;
            }
            Path path = this.playerNpc.getNavigation().createPath(immutable, 0);
            if (path != null && path.canReach()) {
                return immutable;
            }
        }
        return null;
    }

    private boolean ensureFurnaceStand(ServerLevel serverLevel) {
        if (this.furnaceStandPos != null && PathNavigationAi.canStandAt(serverLevel, this.furnaceStandPos)) {
            return true;
        }
        this.furnaceStandPos = this.findFurnaceStand(serverLevel, this.furnacePos);
        return this.furnaceStandPos != null;
    }

    private boolean isAtFurnaceStand() {
        return this.furnaceStandPos != null
                && this.playerNpc.distanceToSqr(
                this.furnaceStandPos.getX() + 0.5D,
                this.furnaceStandPos.getY(),
                this.furnaceStandPos.getZ() + 0.5D
        ) <= FURNACE_STAND_REACHED_SQR
                && this.distanceToFurnaceSqr(this.playerNpc.blockPosition(), this.furnacePos) <= FURNACE_USE_DISTANCE_SQR + 1.0D;
    }

    private boolean moveToFurnaceStand() {
        if (this.furnaceStandPos == null) {
            return false;
        }
        Path path = this.playerNpc.getNavigation().createPath(this.furnaceStandPos, 0);
        return path != null && path.canReach() && this.playerNpc.getNavigation().moveTo(path, this.speed);
    }

    private double distanceToFurnaceSqr(BlockPos standPos, BlockPos pos) {
        if (standPos == null || pos == null) {
            return Double.MAX_VALUE;
        }
        double dx = standPos.getX() + 0.5D - (pos.getX() + 0.5D);
        double dy = standPos.getY() + 0.5D - (pos.getY() + 0.5D);
        double dz = standPos.getZ() + 0.5D - (pos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private BlockPos findTorchPlacement(ServerLevel serverLevel) {
        BlockPos center = this.playerNpc.blockPosition();
        if (serverLevel.getBrightness(LightLayer.BLOCK, center) > TORCH_LOW_LIGHT_LEVEL || this.hasNearbyTorch(serverLevel, center)) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(center.relative(direction));
            candidates.add(center.relative(direction).above());
        }
        candidates.add(center);

        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canPlaceTorchAt(serverLevel, immutable)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean canPlaceTorchAt(ServerLevel serverLevel, BlockPos pos) {
        BlockState torchState = Blocks.TORCH.defaultBlockState();
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).canBeReplaced()
                && serverLevel.getFluidState(pos).isEmpty()
                && torchState.canSurvive(serverLevel, pos);
    }

    private boolean hasNearbyTorch(ServerLevel serverLevel, BlockPos center) {
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-TORCH_NEARBY_RADIUS, -2, -TORCH_NEARBY_RADIUS),
                center.offset(TORCH_NEARBY_RADIUS, 2, TORCH_NEARBY_RADIUS))) {
            BlockState state = serverLevel.getBlockState(pos);
            if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findCampWalkTarget(ServerLevel serverLevel) {
        BlockPos center = this.campCenter != null ? this.campCenter : this.playerNpc.blockPosition();
        boolean keepUnderground = GatherStoneGoal.isMiningJobActive(this.playerNpc)
                && !serverLevel.canSeeSky(center.above());
        List<BlockPos> candidates = new ArrayList<>();
        for (int dy = -CAMP_WALK_VERTICAL_RADIUS; dy <= CAMP_WALK_VERTICAL_RADIUS; dy++) {
            for (int dx = -CAMP_WALK_RADIUS; dx <= CAMP_WALK_RADIUS; dx++) {
                for (int dz = -CAMP_WALK_RADIUS; dz <= CAMP_WALK_RADIUS; dz++) {
                    if (dx * dx + dz * dz > CAMP_WALK_RADIUS * CAMP_WALK_RADIUS) {
                        continue;
                    }
                    BlockPos candidate = center.offset(dx, dy, dz);
                    if (!candidate.equals(this.playerNpc.blockPosition())) {
                        candidates.add(candidate);
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(this.playerNpc.blockPosition())));
        int checks = 0;
        while (!candidates.isEmpty() && checks++ < 32) {
            BlockPos candidate = candidates.remove(this.playerNpc.getRandom().nextInt(candidates.size())).immutable();
            if (!PathNavigationAi.canStandAt(serverLevel, candidate)
                    || (keepUnderground && serverLevel.canSeeSky(candidate.above()))) {
                continue;
            }
            Path path = this.playerNpc.getNavigation().createPath(candidate, 0);
            if (path != null && path.canReach()) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasReachedWalkTarget() {
        return this.walkTarget != null
                && this.playerNpc.distanceToSqr(
                this.walkTarget.getX() + 0.5D,
                this.walkTarget.getY(),
                this.walkTarget.getZ() + 0.5D
        ) <= 1.25D;
    }

    private void switchCampActivity() {
        ActivityMode previous = this.activityMode;
        this.sneakingAi.stopSneaking();
        this.walkTarget = null;
        this.walkSneaking = false;
        this.activityMode = this.randomActivityMode(previous);
        this.activityTicks = this.nextActivityTicks();
        this.stationaryTicks = this.nextStationaryTicks();
        this.repathTicks = 0;
    }

    private ActivityMode randomActivityMode(ActivityMode previous) {
        ActivityMode next;
        do {
            float roll = this.playerNpc.getRandom().nextFloat();
            if (roll < 0.34F) {
                next = ActivityMode.SNEAK;
            } else if (roll < 0.72F) {
                next = ActivityMode.WALK;
            } else {
                next = ActivityMode.LOOK;
            }
        } while (next == previous);
        return next;
    }

    private int nextActivityTicks() {
        return MIN_ACTIVITY_TICKS + this.playerNpc.getRandom().nextInt(RANDOM_ACTIVITY_TICKS + 1);
    }

    private int nextStationaryTicks() {
        return MIN_STATIONARY_TICKS + this.playerNpc.getRandom().nextInt(RANDOM_STATIONARY_TICKS + 1);
    }

    private void lookAroundCamp() {
        if (this.lookTicks-- > 0) {
            return;
        }
        double angle = this.playerNpc.getRandom().nextDouble() * Math.PI * 2.0D;
        double distance = 3.0D + this.playerNpc.getRandom().nextDouble() * 4.0D;
        this.playerNpc.getLookControl().setLookAt(
                this.playerNpc.getX() + Math.cos(angle) * distance,
                this.playerNpc.getEyeY(),
                this.playerNpc.getZ() + Math.sin(angle) * distance,
                30.0F,
                30.0F
        );
        this.lookTicks = MIN_LOOK_TICKS + this.playerNpc.getRandom().nextInt(RANDOM_LOOK_TICKS + 1);
    }

    private void lookAtFurnace() {
        this.lookAt(this.furnacePos);
    }

    private void lookAt(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.playerNpc.getLookControl().setLookAt(
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
    }

    private ItemStack takeOrCraftFurnace() {
        ItemStack furnace = this.playerNpc.consumeInventoryItem(Items.FURNACE, 1).orElse(ItemStack.EMPTY);
        if (!furnace.isEmpty()) {
            return furnace;
        }
        if (!PlayerNpcCraftingUtil.tryCraftFurnace(this.playerNpc.getInventory())) {
            return ItemStack.EMPTY;
        }
        return this.playerNpc.consumeInventoryItem(Items.FURNACE, 1).orElse(ItemStack.EMPTY);
    }

    private ItemStack takeOrCraftTorch() {
        ItemStack torch = this.playerNpc.consumeInventoryItem(Items.TORCH, 1).orElse(ItemStack.EMPTY);
        if (!torch.isEmpty()) {
            return torch;
        }
        if (!PlayerNpcCraftingUtil.tryCraftTorches(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
            return ItemStack.EMPTY;
        }
        return this.playerNpc.consumeInventoryItem(Items.TORCH, 1).orElse(ItemStack.EMPTY);
    }

    private BlockPos getTemporaryFurnacePos() {
        if (!this.playerNpc.getPersistentData().contains(FurnaceAi.TEMP_FURNACE_X)) {
            return null;
        }

        return new BlockPos(
                this.playerNpc.getPersistentData().getInt(FurnaceAi.TEMP_FURNACE_X),
                this.playerNpc.getPersistentData().getInt(FurnaceAi.TEMP_FURNACE_Y),
                this.playerNpc.getPersistentData().getInt(FurnaceAi.TEMP_FURNACE_Z)
        );
    }

    private boolean saveTemporaryFurnace(ServerLevel serverLevel, BlockPos pos) {
        if (!(serverLevel.getBlockEntity(pos) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }

        furnace.getPersistentData().putUUID(CAMP_FURNACE_OWNER, this.playerNpc.getUUID());
        furnace.setChanged();

        CompoundTag data = this.playerNpc.getPersistentData();
        data.putInt(FurnaceAi.TEMP_FURNACE_X, pos.getX());
        data.putInt(FurnaceAi.TEMP_FURNACE_Y, pos.getY());
        data.putInt(FurnaceAi.TEMP_FURNACE_Z, pos.getZ());
        data.putInt(CAMP_FURNACE_X, pos.getX());
        data.putInt(CAMP_FURNACE_Y, pos.getY());
        data.putInt(CAMP_FURNACE_Z, pos.getZ());
        data.putString(CAMP_FURNACE_DIMENSION, serverLevel.dimension().location().toString());
        return true;
    }

    private void clearTemporaryFurnace() {
        this.playerNpc.getPersistentData().remove(FurnaceAi.TEMP_FURNACE_X);
        this.playerNpc.getPersistentData().remove(FurnaceAi.TEMP_FURNACE_Y);
        this.playerNpc.getPersistentData().remove(FurnaceAi.TEMP_FURNACE_Z);
    }

    private boolean hasOwnedCampFurnaceReference() {
        CompoundTag data = this.playerNpc.getPersistentData();
        return data.contains(CAMP_FURNACE_X)
                && data.contains(CAMP_FURNACE_Y)
                && data.contains(CAMP_FURNACE_Z)
                && data.contains(CAMP_FURNACE_DIMENSION);
    }

    private CampFurnaceRef resolveOwnedCampFurnace() {
        if (!this.hasOwnedCampFurnaceReference()) {
            return null;
        }

        CompoundTag data = this.playerNpc.getPersistentData();
        BlockPos pos = new BlockPos(
                data.getInt(CAMP_FURNACE_X),
                data.getInt(CAMP_FURNACE_Y),
                data.getInt(CAMP_FURNACE_Z)
        );
        ResourceLocation dimensionId = ResourceLocation.tryParse(data.getString(CAMP_FURNACE_DIMENSION));
        if (dimensionId == null || this.playerNpc.getServer() == null) {
            this.clearCampFurnaceOwnership(pos);
            return null;
        }

        ResourceKey<Level> levelKey = ResourceKey.create(Registries.DIMENSION, dimensionId);
        ServerLevel serverLevel = this.playerNpc.getServer().getLevel(levelKey);
        if (serverLevel == null || !serverLevel.isInWorldBounds(pos)) {
            this.clearCampFurnaceOwnership(pos);
            return null;
        }
        return new CampFurnaceRef(serverLevel, pos.immutable());
    }

    private boolean isOwnedCampFurnace(CampFurnaceRef ownedFurnace) {
        if (ownedFurnace == null
                || !ownedFurnace.level().getBlockState(ownedFurnace.pos()).is(Blocks.FURNACE)
                || !(ownedFurnace.level().getBlockEntity(ownedFurnace.pos()) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }
        CompoundTag furnaceData = furnace.getPersistentData();
        return furnaceData.hasUUID(CAMP_FURNACE_OWNER)
                && this.playerNpc.getUUID().equals(furnaceData.getUUID(CAMP_FURNACE_OWNER));
    }

    private boolean canReuseOwnedFurnace(ServerLevel currentLevel, CampFurnaceRef ownedFurnace) {
        if (ownedFurnace.level() != currentLevel) {
            return false;
        }
        return this.isWithinLocalFurnaceScan(ownedFurnace.pos());
    }

    private void adoptNearbyLegacyTemporaryFurnace(ServerLevel currentLevel) {
        if (this.hasOwnedCampFurnaceReference()) {
            return;
        }
        BlockPos legacyPos = this.getTemporaryFurnacePos();
        if (legacyPos == null
                || !currentLevel.hasChunkAt(legacyPos)
                || !this.isWithinLocalFurnaceScan(legacyPos)
                || !currentLevel.getBlockState(legacyPos).is(Blocks.FURNACE)
                || !(currentLevel.getBlockEntity(legacyPos) instanceof FurnaceBlockEntity furnace)) {
            return;
        }

        CompoundTag furnaceData = furnace.getPersistentData();
        if (furnaceData.hasUUID(CAMP_FURNACE_OWNER)
                && !this.playerNpc.getUUID().equals(furnaceData.getUUID(CAMP_FURNACE_OWNER))) {
            return;
        }
        this.saveTemporaryFurnace(currentLevel, legacyPos);
    }

    private boolean isWithinLocalFurnaceScan(BlockPos furnace) {
        BlockPos currentPos = this.playerNpc.blockPosition();
        int dx = furnace.getX() - currentPos.getX();
        int dz = furnace.getZ() - currentPos.getZ();
        return Math.abs(furnace.getY() - currentPos.getY()) <= 2
                && dx * dx + dz * dz <= FURNACE_SCAN_RADIUS * FURNACE_SCAN_RADIUS;
    }

    private boolean reclaimOwnedCampFurnace(CampFurnaceRef ownedFurnace) {
        if (ownedFurnace == null || !ownedFurnace.level().hasChunkAt(ownedFurnace.pos())) {
            return false;
        }
        if (!this.isOwnedCampFurnace(ownedFurnace)) {
            this.clearCampFurnaceOwnership(ownedFurnace.pos());
            return true;
        }
        if (!(ownedFurnace.level().getBlockEntity(ownedFurnace.pos()) instanceof FurnaceBlockEntity furnace)) {
            return false;
        }

        List<ItemStack> contents = new ArrayList<>(furnace.getContainerSize());
        for (int slot = 0; slot < furnace.getContainerSize(); slot++) {
            contents.add(furnace.getItem(slot).copy());
            furnace.setItem(slot, ItemStack.EMPTY);
        }
        furnace.setChanged();
        if (!ownedFurnace.level().removeBlock(ownedFurnace.pos(), false)) {
            for (int slot = 0; slot < contents.size(); slot++) {
                furnace.setItem(slot, contents.get(slot));
            }
            furnace.setChanged();
            return false;
        }

        this.clearCampFurnaceOwnership(ownedFurnace.pos());
        for (ItemStack stack : contents) {
            this.returnStack(stack);
        }
        this.returnStack(new ItemStack(Items.FURNACE));
        this.playerNpc.triggerMainHandUseAnimation();
        ownedFurnace.level().playSound(
                null,
                ownedFurnace.pos(),
                SoundEvents.ITEM_PICKUP,
                SoundSource.BLOCKS,
                0.5F,
                1.0F
        );
        return true;
    }

    private void clearCampFurnaceOwnership(BlockPos ownedPos) {
        if (ownedPos != null && ownedPos.equals(this.getTemporaryFurnacePos())) {
            this.clearTemporaryFurnace();
        }
        CompoundTag data = this.playerNpc.getPersistentData();
        data.remove(CAMP_FURNACE_X);
        data.remove(CAMP_FURNACE_Y);
        data.remove(CAMP_FURNACE_Z);
        data.remove(CAMP_FURNACE_DIMENSION);
    }

    private void clearActiveFurnaceAction(int cooldownTicks) {
        this.furnaceMode = FurnaceMode.NONE;
        this.furnacePos = null;
        this.furnaceStandPos = null;
        this.actionDelayTicks = 0;
        this.furnaceCooldownTicks = cooldownTicks;
    }

    private String detail(String action, BlockPos pos) {
        if (pos == null) {
            return action;
        }
        return String.format(Locale.ROOT, "%s @ %d %d %d", action, pos.getX(), pos.getY(), pos.getZ());
    }

    private void showPlacementItem(ItemStack stack) {
        this.setTemporaryMainHand(stack, false);
    }

    private void setTemporaryMainHand(ItemStack stack, boolean returnCurrentOnRestore) {
        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryMainHand) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryMainHand = true;
            this.returnTemporaryMainHandOnRestore = returnCurrentOnRestore;
        } else if (!currentMainHand.isEmpty()
                && this.returnTemporaryMainHandOnRestore
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)) {
            this.returnStack(currentMainHand);
        }

        ItemStack held = stack.copy();
        held.setCount(Math.min(1, held.getCount()));
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, held);
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryMainHand) {
            return;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && this.returnTemporaryMainHandOnRestore
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)) {
            this.returnStack(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryMainHand = false;
        this.returnTemporaryMainHandOnRestore = false;
    }

    private void finishPlacementMainHand() {
        if (!this.usingTemporaryMainHand) {
            return;
        }

        this.placingBlockAi.finishHeldPlacement(this.previousMainHand);
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryMainHand = false;
        this.returnTemporaryMainHandOnRestore = false;
    }

    private void returnStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack remaining = this.playerNpc.getInventory().addItem(stack.copy());
        if (!remaining.isEmpty()) {
            this.playerNpc.spawnAtLocation(remaining);
        }
    }

    private void resetPlan() {
        this.campCenter = null;
        this.furnacePos = null;
        this.furnaceStandPos = null;
        this.walkTarget = null;
        this.furnaceMode = FurnaceMode.NONE;
        this.previousMainHand = ItemStack.EMPTY;
        this.actionDelayTicks = 0;
        this.furnaceCooldownTicks = 0;
        this.torchCheckTicks = 0;
        this.activityTicks = 0;
        this.stationaryTicks = 0;
        this.lookTicks = 0;
        this.repathTicks = 0;
        this.furnaceRecoveryTicks = 0;
        this.finished = false;
        this.placedTorch = false;
        this.walkSneaking = false;
        this.usingTemporaryMainHand = false;
        this.returnTemporaryMainHandOnRestore = false;
    }

    private enum FurnaceMode {
        NONE,
        PLACE,
        INTERACT,
        RECOVER
    }

    private record CampFurnaceRef(ServerLevel level, BlockPos pos) {
    }

    private enum ActivityMode {
        SNEAK,
        WALK,
        LOOK
    }
}
