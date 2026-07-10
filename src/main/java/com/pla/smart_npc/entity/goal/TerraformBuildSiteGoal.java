package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcBlockBreakUtil;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.smart_npc.util.PlayerNpcBuildLayout;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class TerraformBuildSiteGoal extends Goal {
    private static final double WORK_DISTANCE_SQR = 5.5D * 5.5D;
    private static final int MINE_HIT_INTERVAL_TICKS = 8;
    private static final int MAX_MINE_TICKS = 20 * 20;
    private static final int PLACE_DELAY_TICKS = 8;
    private static final int SCAFFOLD_JUMP_WINDUP_TICKS = 2;
    private static final int SCAFFOLD_PLACE_DELAY_TICKS = 1;
    private static final int SCAFFOLD_MAX_PLACE_WAIT_TICKS = 32;
    private static final int SCAFFOLD_FORCE_PLACE_TICKS = 3;
    private static final int MAX_SUPPORT_FILL_FAILURES = 12;
    private static final int SUPPORT_FILL_RETRY_COOLDOWN_TICKS = 20 * 5;
    private static final int TARGET_SEARCH_RETRY_COOLDOWN_TICKS = 10;
    private static final double SCAFFOLD_PLACE_CLEARANCE_Y = 0.65D;
    private static final double SCAFFOLD_FALLBACK_PLACE_CLEARANCE_Y = 0.55D;
    private static final double SCAFFOLD_HORIZONTAL_REACHED_SQR = 1.5D * 1.5D;
    private static final int SUPPORT_SCAN_DEPTH = 3;
    private static final List<net.minecraft.world.item.Item> FILL_ITEMS = List.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT,
            Items.GRASS_BLOCK,
            Items.PODZOL,
            Items.SAND,
            Items.RED_SAND,
            Items.GRAVEL,
            Items.MUD,
            Items.COBBLESTONE,
            Items.COBBLED_DEEPSLATE
    );
    private static final Map<PlayerNpcBuildLayout, List<PlayerNpcBuildLayout.RelativeBlock>> SORTED_CLEAR_BLOCKS = new IdentityHashMap<>();

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final List<BlockPos> temporaryScaffold = new ArrayList<>();
    private final List<BlockPos> skippedSupportTargets = new ArrayList<>();
    private TerraformTarget target;
    private BlockPos lastSupportFillFailurePos;
    private BlockPos scaffoldPlacePos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int workTicks;
    private int repathTicks;
    private int supportFillFailures;
    private int supportFillRetryCooldownTicks;
    private int scaffoldJumpDelayTicks;
    private int scaffoldPlaceDelayTicks;
    private int scaffoldPlaceWaitTicks;
    private int targetSearchRetryCooldownTicks;
    private boolean usingTemporaryMainHand;

    public TerraformBuildSiteGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean hasActionablePrepWork(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return findNextTarget(serverLevel, playerNpc, true).isPresent();
    }

    public static boolean needsShovelForPrep(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        Optional<TerraformTarget> target = findNextTarget(serverLevel, playerNpc, true);
        return target.isPresent()
                && target.get().phase() == TerraformPhase.CLEAR
                && serverLevel.getBlockState(target.get().pos()).is(BlockTags.MINEABLE_WITH_SHOVEL)
                && !hasTool(playerNpc, ShovelItem.class);
    }

    @Override
    public boolean canUse() {
        if (this.supportFillRetryCooldownTicks > 0) {
            this.supportFillRetryCooldownTicks--;
            return false;
        }
        if (this.targetSearchRetryCooldownTicks > 0) {
            this.targetSearchRetryCooldownTicks--;
            return false;
        }

        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null) {
            return false;
        }

        Optional<TerraformTarget> nextTarget = findNextTarget(serverLevel, this.playerNpc, true);
        if (nextTarget.isEmpty()) {
            this.targetSearchRetryCooldownTicks = TARGET_SEARCH_RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(10);
            return false;
        }

        if (nextTarget.get().phase() == TerraformPhase.CLEAR
                && serverLevel.getBlockState(nextTarget.get().pos()).is(BlockTags.MINEABLE_WITH_SHOVEL)
                && !hasTool(this.playerNpc, ShovelItem.class)) {
            return false;
        }

        this.target = nextTarget.get();
        this.targetSearchRetryCooldownTicks = 0;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return (this.target != null || !this.temporaryScaffold.isEmpty())
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null;
    }

    @Override
    public void start() {
        this.workTicks = 0;
        this.repathTicks = 0;
        this.scaffoldPlacePos = null;
        this.scaffoldJumpDelayTicks = 0;
        this.scaffoldPlaceDelayTicks = 0;
        this.scaffoldPlaceWaitTicks = 0;
        this.supportFillFailures = 0;
        this.lastSupportFillFailurePos = null;
        this.temporaryScaffold.clear();
        this.skippedSupportTargets.clear();
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryMainHand = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.terraforming_build_site");
        this.updateTaskDetail();
        this.moveToTarget();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (this.target == null) {
            this.tickScaffoldCleanup(serverLevel);
            return;
        }

        if (this.scaffoldPlacePos != null) {
            this.tickScaffoldPlacement(serverLevel);
            return;
        }

        if (!this.isTargetStillValid(serverLevel, this.target)) {
            this.playerNpc.clearBlockBreakProgress(this.target.pos());
            this.workTicks = 0;
            if (!this.temporaryScaffold.isEmpty()) {
                this.target = null;
                this.restorePreviousMainHand();
                this.updateTaskDetail();
                return;
            }
            this.target = this.findNextTarget(serverLevel, true).orElse(null);
            this.updateTaskDetail();
            if (this.target == null) {
                this.targetSearchRetryCooldownTicks = TARGET_SEARCH_RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(10);
                return;
            }
        }

        BlockPos pos = this.target.pos();
        this.playerNpc.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.shouldScaffoldToward(serverLevel, pos) && this.beginScaffoldStep(serverLevel, this.playerNpc.blockPosition())) {
            this.playerNpc.clearBlockBreakProgress(pos);
            return;
        }

        if (this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > WORK_DISTANCE_SQR) {
            this.playerNpc.clearBlockBreakProgress(pos);
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                this.repathTicks = 20;
                this.moveToTarget();
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        switch (this.target.phase()) {
            case CLEAR, CLEAR_WATER -> this.tickClear(serverLevel);
            case FILL_SUPPORT -> this.tickFillSupport(serverLevel);
        }
    }

    @Override
    public void stop() {
        if (this.target != null) {
            this.playerNpc.clearBlockBreakProgress(this.target.pos());
        }
        this.playerNpc.clearBlockBreakProgress(this.scaffoldPlacePos);
        if (!this.temporaryScaffold.isEmpty()) {
            this.playerNpc.clearBlockBreakProgress(this.temporaryScaffold.get(this.temporaryScaffold.size() - 1));
        }
        this.restorePreviousMainHand();
        this.playerNpc.getNavigation().stop();
        this.target = null;
        this.scaffoldPlacePos = null;
        this.temporaryScaffold.clear();
        this.skippedSupportTargets.clear();
        this.lastSupportFillFailurePos = null;
        this.workTicks = 0;
        this.repathTicks = 0;
        this.supportFillFailures = 0;
        this.scaffoldJumpDelayTicks = 0;
        this.scaffoldPlaceDelayTicks = 0;
        this.scaffoldPlaceWaitTicks = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private void tickClear(ServerLevel serverLevel) {
        BlockPos pos = this.target.pos();
        BlockState state = serverLevel.getBlockState(pos);
        if (this.target.phase() == TerraformPhase.CLEAR_WATER) {
            if (state.getFluidState().isEmpty()) {
                this.target = null;
                return;
            }
            if (this.workTicks++ < PLACE_DELAY_TICKS) {
                return;
            }
            serverLevel.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            this.playerNpc.triggerMainHandUseAnimation();
            this.target = null;
            this.workTicks = 0;
            this.updateTaskDetail();
            return;
        }

        if (!this.canClearForBuild(serverLevel, pos, state)) {
            this.playerNpc.clearBlockBreakProgress(pos);
            this.target = null;
            this.workTicks = 0;
            return;
        }

        this.equipToolFor(state);
        if (this.workTicks % MINE_HIT_INTERVAL_TICKS == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, pos, state, this.playerNpc);
        }

        this.workTicks++;
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, pos, state);
        this.playerNpc.showBlockBreakProgress(pos, this.workTicks, requiredMineTicks);
        if (this.workTicks < requiredMineTicks) {
            return;
        }

        if (PlayerNpcBlockBreakUtil.destroyBlock(serverLevel, pos, state, this.playerNpc)) {
            this.playerNpc.hurtMainHandItem(1);
        }
        this.playerNpc.clearBlockBreakProgress(pos);
        this.target = null;
        this.workTicks = 0;
        this.restorePreviousMainHand();
        this.updateTaskDetail();
    }

    private void tickFillSupport(ServerLevel serverLevel) {
        if (this.workTicks++ < PLACE_DELAY_TICKS) {
            return;
        }
        this.workTicks = 0;

        BlockPos pos = this.target.pos();
        Optional<ItemStack> fillStack = this.useFillBlockInMainHand();
        if (fillStack.isEmpty()) {
            this.target = null;
            return;
        }

        BlockState fillState = ((BlockItem) fillStack.get().getItem()).getBlock().defaultBlockState();
        if (!PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)) {
            this.noteSupportFillFailure(serverLevel, pos, "blocked");
            return;
        }
        if (!this.canPlaceSupportWithoutClipping(serverLevel, pos, fillState)) {
            this.noteSupportFillFailure(serverLevel, pos, "clipping");
            return;
        }

        if (!serverLevel.setBlockAndUpdate(pos, fillState)) {
            this.noteSupportFillFailure(serverLevel, pos, "place_failed");
            return;
        }

        this.playerNpc.triggerMainHandUseAnimation();
        PlayerNpcBlockSoundUtil.playPlaceSound(serverLevel, pos, fillState, this.playerNpc);
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        mainHand.shrink(1);
        if (mainHand.isEmpty()) {
            this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        this.supportFillFailures = 0;
        this.lastSupportFillFailurePos = null;
        this.target = null;
    }

    private void noteSupportFillFailure(ServerLevel serverLevel, BlockPos pos, String reason) {
        BlockPos immutable = pos.immutable();
        if (!immutable.equals(this.lastSupportFillFailurePos)) {
            this.lastSupportFillFailurePos = immutable;
            this.supportFillFailures = 0;
        }

        this.supportFillFailures++;
        this.playerNpc.setCurrentAiDetail("fill support " + reason + " "
                + this.supportFillFailures + "/" + MAX_SUPPORT_FILL_FAILURES
                + " @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
        if (this.supportFillFailures < MAX_SUPPORT_FILL_FAILURES) {
            return;
        }

        if (!this.skippedSupportTargets.contains(immutable)) {
            this.skippedSupportTargets.add(immutable);
        }
        this.supportFillFailures = 0;
        this.lastSupportFillFailurePos = null;
        this.target = this.findNextTarget(serverLevel, true).orElse(null);
        if (this.target == null) {
            this.supportFillRetryCooldownTicks = SUPPORT_FILL_RETRY_COOLDOWN_TICKS;
        }
        this.updateTaskDetail();
    }

    private boolean shouldScaffoldToward(ServerLevel serverLevel, BlockPos pos) {
        if (this.target == null
                || this.target.phase() != TerraformPhase.CLEAR
                || this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= WORK_DISTANCE_SQR
                || !hasScaffoldBlock(this.playerNpc)) {
            return false;
        }

        BlockPos feet = this.playerNpc.blockPosition();
        double dx = this.playerNpc.getX() - (pos.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (pos.getZ() + 0.5D);
        if (dx * dx + dz * dz > SCAFFOLD_HORIZONTAL_REACHED_SQR) {
            return false;
        }

        if (pos.getY() <= feet.getY() + 2) {
            return false;
        }

        int availableBlocks = this.countScaffoldBlocks();
        if (availableBlocks <= 0) {
            return false;
        }

        int maxPlacements = Math.min(availableBlocks, Math.max(1, pos.getY() - feet.getY() + 1));
        for (int placed = 0; placed <= maxPlacements; placed++) {
            BlockPos feetAtHeight = feet.above(placed);
            if (!this.hasOpenBodySpace(serverLevel, feetAtHeight)) {
                return feetAtHeight.distSqr(pos) <= WORK_DISTANCE_SQR + 1.0D;
            }
            if (feetAtHeight.distSqr(pos) <= WORK_DISTANCE_SQR) {
                return true;
            }
        }
        return false;
    }

    private boolean beginScaffoldStep(ServerLevel serverLevel, BlockPos feet) {
        if (!this.playerNpc.onGround()) {
            this.lookDownAt(feet);
            return true;
        }

        boolean replaceable = serverLevel.getBlockState(feet).canBeReplaced();
        boolean openBodySpace = this.hasOpenBodySpace(serverLevel, feet);
        boolean otherEntity = this.hasOtherEntityInBlock(serverLevel, feet);
        boolean equippedBlock = replaceable && openBodySpace && !otherEntity && this.useScaffoldBlockInMainHand().isPresent();
        if (!replaceable || !openBodySpace || otherEntity || !equippedBlock) {
            return false;
        }

        this.scaffoldPlacePos = feet.immutable();
        this.scaffoldJumpDelayTicks = SCAFFOLD_JUMP_WINDUP_TICKS;
        this.scaffoldPlaceDelayTicks = SCAFFOLD_PLACE_DELAY_TICKS;
        this.scaffoldPlaceWaitTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.lookDownAt(this.scaffoldPlacePos);
        this.updateTaskDetail();
        return true;
    }

    private void tickScaffoldPlacement(ServerLevel serverLevel) {
        if (this.scaffoldPlacePos == null) {
            return;
        }

        if (this.scaffoldJumpDelayTicks > 0) {
            if (this.useScaffoldBlockInMainHand().isEmpty()) {
                this.clearScaffoldPlacement();
                return;
            }
            this.playerNpc.getNavigation().stop();
            this.lookDownAt(this.scaffoldPlacePos);
            this.scaffoldJumpDelayTicks--;
            if (this.scaffoldJumpDelayTicks <= 0) {
                this.playerNpc.shortPillarJump();
            }
            return;
        }

        if (this.scaffoldPlaceDelayTicks > 0) {
            this.scaffoldPlaceDelayTicks--;
            return;
        }

        this.scaffoldPlaceWaitTicks++;
        if (this.scaffoldPlaceWaitTicks > SCAFFOLD_MAX_PLACE_WAIT_TICKS) {
            this.clearScaffoldPlacement();
            return;
        }

        if (!this.hasScaffoldPlacementClearance()) {
            this.lookDownAt(this.scaffoldPlacePos);
            return;
        }

        Optional<ItemStack> scaffoldStack = this.useScaffoldBlockInMainHand();
        if (!serverLevel.getBlockState(this.scaffoldPlacePos).canBeReplaced() || scaffoldStack.isEmpty()) {
            this.clearScaffoldPlacement();
            return;
        }

        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (mainHand.isEmpty() || !(mainHand.getItem() instanceof BlockItem blockItem)) {
            this.clearScaffoldPlacement();
            return;
        }

        BlockState placeState = blockItem.getBlock().defaultBlockState();
        if (!this.canPlaceScaffoldWithoutClipping(serverLevel, this.scaffoldPlacePos, placeState)) {
            this.lookDownAt(this.scaffoldPlacePos);
            return;
        }

        this.lookDownAt(this.scaffoldPlacePos);
        if (!serverLevel.setBlockAndUpdate(this.scaffoldPlacePos, placeState)) {
            this.clearScaffoldPlacement();
            return;
        }

        this.snapAboveScaffoldIfNeeded(this.scaffoldPlacePos);
        this.playerNpc.triggerMainHandUseAnimation();
        PlayerNpcBlockSoundUtil.playPlaceSound(serverLevel, this.scaffoldPlacePos, placeState, this.playerNpc);
        this.temporaryScaffold.add(this.scaffoldPlacePos.immutable());
        mainHand.shrink(1);
        if (mainHand.isEmpty()) {
            this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        this.clearScaffoldPlacement();
        this.updateTaskDetail();
    }

    private void tickScaffoldCleanup(ServerLevel serverLevel) {
        if (this.temporaryScaffold.isEmpty()) {
            return;
        }

        if (!this.playerNpc.onGround()) {
            this.lookDownAt(this.temporaryScaffold.get(this.temporaryScaffold.size() - 1));
            this.updateTaskDetail();
            return;
        }

        BlockPos pos = this.temporaryScaffold.get(this.temporaryScaffold.size() - 1);
        BlockState state = serverLevel.getBlockState(pos);
        if (state.isAir()) {
            this.playerNpc.clearBlockBreakProgress(pos);
            this.temporaryScaffold.remove(this.temporaryScaffold.size() - 1);
            this.workTicks = 0;
            this.updateTaskDetail();
            return;
        }

        this.equipToolFor(state);
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.workTicks % MINE_HIT_INTERVAL_TICKS == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, pos, state, this.playerNpc);
        }

        this.workTicks++;
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, pos, state);
        this.playerNpc.showBlockBreakProgress(pos, this.workTicks, requiredMineTicks);
        this.updateTaskDetail();
        if (this.workTicks < requiredMineTicks) {
            return;
        }

        if (PlayerNpcBlockBreakUtil.destroyBlock(serverLevel, pos, state, this.playerNpc)) {
            this.playerNpc.hurtMainHandItem(1);
        }
        this.playerNpc.clearBlockBreakProgress(pos);
        this.temporaryScaffold.remove(this.temporaryScaffold.size() - 1);
        this.workTicks = 0;
        if (this.temporaryScaffold.isEmpty()) {
            this.restorePreviousMainHand();
        }
        this.updateTaskDetail();
    }

    private void clearScaffoldPlacement() {
        this.scaffoldPlacePos = null;
        this.scaffoldJumpDelayTicks = 0;
        this.scaffoldPlaceDelayTicks = 0;
        this.scaffoldPlaceWaitTicks = 0;
    }

    private void moveToTarget() {
        if (this.target == null) {
            return;
        }

        BlockPos pos = this.target.pos();
        double y = Math.min(this.playerNpc.getY(), pos.getY());
        this.playerNpc.getNavigation().moveTo(pos.getX() + 0.5D, y, pos.getZ() + 0.5D, this.speed);
    }

    private void updateTaskDetail() {
        if (this.scaffoldPlacePos != null) {
            this.playerNpc.setCurrentAiDetail("pillar up @ "
                    + this.scaffoldPlacePos.getX() + " "
                    + this.scaffoldPlacePos.getY() + " "
                    + this.scaffoldPlacePos.getZ());
            return;
        }
        if (!this.temporaryScaffold.isEmpty() && this.target == null) {
            BlockPos pos = this.temporaryScaffold.get(this.temporaryScaffold.size() - 1);
            this.playerNpc.setCurrentAiDetail("pillar down @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
            return;
        }
        if (this.target == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        String action = switch (this.target.phase()) {
            case CLEAR -> "clear";
            case CLEAR_WATER -> "drain";
            case FILL_SUPPORT -> "fill support";
        };
        BlockPos pos = this.target.pos();
        this.playerNpc.setCurrentAiDetail(action + " @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }

    private boolean isTargetStillValid(ServerLevel serverLevel, TerraformTarget target) {
        BlockState existing = serverLevel.getBlockState(target.pos());
        return switch (target.phase()) {
            case CLEAR_WATER -> !existing.getFluidState().isEmpty();
            case CLEAR -> target.targetState().isAir()
                    ? !existing.isAir() && this.canClearForBuild(serverLevel, target.pos(), existing)
                    : !PlayerNpcBuildMaterialUtil.matches(existing, target.targetState())
                    && !PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, target.pos())
                    && this.canClearForBuild(serverLevel, target.pos(), existing);
            case FILL_SUPPORT -> !isGoodFloorBlock(serverLevel, target.pos(), existing)
                    && PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, target.pos())
                    && hasFillBlock(this.playerNpc);
        };
    }

    private static Optional<TerraformTarget> findNextTarget(ServerLevel serverLevel, PlayerNpcEntity playerNpc, boolean requireFillItem) {
        return findNextTarget(serverLevel, playerNpc, requireFillItem, List.of());
    }

    private Optional<TerraformTarget> findNextTarget(ServerLevel serverLevel, boolean requireFillItem) {
        return findNextTarget(serverLevel, this.playerNpc, requireFillItem, this.skippedSupportTargets);
    }

    private static Optional<TerraformTarget> findNextTarget(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            boolean requireFillItem,
            List<BlockPos> skippedSupportTargets
    ) {
        Optional<BuildContext> context = findBuildContext(playerNpc);
        if (context.isEmpty()) {
            return Optional.empty();
        }

        Optional<TerraformTarget> clearTarget = findClearTarget(serverLevel, playerNpc, context.get());
        if (clearTarget.isPresent()) {
            return clearTarget;
        }

        return findSupportFillTarget(serverLevel, playerNpc, context.get(), requireFillItem, skippedSupportTargets);
    }

    private static Optional<BuildContext> findBuildContext(PlayerNpcEntity playerNpc) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return Optional.empty();
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()
                || layout.get().width() != home.get().width()
                || layout.get().depth() != home.get().depth()) {
            return Optional.empty();
        }

        return Optional.of(new BuildContext(layout.get(), home.get().origin()));
    }

    private static Optional<TerraformTarget> findClearTarget(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BuildContext context) {
        for (PlayerNpcBuildLayout.RelativeBlock block : sortedClearBlocks(context.layout())) {
            Optional<TerraformTarget> target = clearTargetForBlock(serverLevel, playerNpc, context.origin(), block);
            if (target.isPresent()) {
                return target;
            }
        }
        return Optional.empty();
    }

    private static List<PlayerNpcBuildLayout.RelativeBlock> sortedClearBlocks(PlayerNpcBuildLayout layout) {
        synchronized (SORTED_CLEAR_BLOCKS) {
            return SORTED_CLEAR_BLOCKS.computeIfAbsent(layout, key -> key.blocks().stream()
                    .sorted(Comparator
                            .comparingInt(PlayerNpcBuildLayout.RelativeBlock::y)
                            .thenComparingInt(PlayerNpcBuildLayout.RelativeBlock::x)
                            .thenComparingInt(PlayerNpcBuildLayout.RelativeBlock::z))
                    .toList());
        }
    }

    private static Optional<TerraformTarget> clearTargetForBlock(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos origin, PlayerNpcBuildLayout.RelativeBlock block) {
        BlockPos pos = block.toWorld(origin);
        BlockState targetState = block.state();
        BlockState existing = serverLevel.getBlockState(pos);
        if (!existing.getFluidState().isEmpty() && !existing.getFluidState().equals(targetState.getFluidState())) {
            return Optional.of(new TerraformTarget(pos.immutable(), TerraformPhase.CLEAR_WATER, targetState));
        }
        if (targetState.isAir()) {
            return !existing.isAir() && canClearForBuild(serverLevel, playerNpc, pos, existing)
                    ? Optional.of(new TerraformTarget(pos.immutable(), TerraformPhase.CLEAR, targetState))
                    : Optional.empty();
        }
        if (PlayerNpcBuildMaterialUtil.matches(existing, targetState)
                || PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)
                || !canClearForBuild(serverLevel, playerNpc, pos, existing)) {
            return Optional.empty();
        }
        return Optional.of(new TerraformTarget(pos.immutable(), TerraformPhase.CLEAR, targetState));
    }

    private static Optional<TerraformTarget> findSupportFillTarget(
            ServerLevel serverLevel,
            PlayerNpcEntity playerNpc,
            BuildContext context,
            boolean requireFillItem,
            List<BlockPos> skippedSupportTargets
    ) {
        if (requireFillItem && !hasFillBlock(playerNpc)) {
            return Optional.empty();
        }

        PlayerNpcBuildLayout layout = context.layout();
        BlockPos origin = context.origin();
        for (int x = 0; x < layout.width(); x++) {
            for (int z = 0; z < layout.depth(); z++) {
                if (!layout.isInFootprint(x, z)) {
                    continue;
                }

                BlockPos topSupport = origin.offset(x, -1, z);
                if (isGoodFloorBlock(serverLevel, topSupport, serverLevel.getBlockState(topSupport))) {
                    continue;
                }

                for (int dy = -SUPPORT_SCAN_DEPTH; dy <= -1; dy++) {
                    BlockPos pos = origin.offset(x, dy, z);
                    BlockState state = serverLevel.getBlockState(pos);
                    if (skippedSupportTargets.contains(pos)) {
                        continue;
                    }
                    if (!isGoodFloorBlock(serverLevel, pos, state)
                            && PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)) {
                        return Optional.of(new TerraformTarget(pos.immutable(), TerraformPhase.FILL_SUPPORT, Blocks.DIRT.defaultBlockState()));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private boolean canClearForBuild(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return canClearForBuild(serverLevel, this.playerNpc, pos, state);
    }

    private static boolean canClearForBuild(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockPos pos, BlockState state) {
        return !state.isAir()
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && !CraftBasicGearGoal.isTemporaryCraftingTable(playerNpc, serverLevel, pos)
                && serverLevel.getBlockEntity(pos) == null
                && (state.canBeReplaced()
                || state.is(BlockTags.MINEABLE_WITH_SHOVEL)
                || state.is(BlockTags.MINEABLE_WITH_AXE)
                || state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                || state.is(BlockTags.LEAVES));
    }

    private static boolean isGoodFloorBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return !state.isAir()
                && state.getFluidState().isEmpty()
                && (state.isFaceSturdy(serverLevel, pos, Direction.UP) || state.isSolidRender(serverLevel, pos))
                && !state.is(Blocks.BEDROCK);
    }

    private static boolean hasFillBlock(PlayerNpcEntity playerNpc) {
        return isFillStack(playerNpc.getMainHandItem())
                || InventoryUtils.hasItem(playerNpc, TerraformBuildSiteGoal::isFillStack);
    }

    private static boolean hasScaffoldBlock(PlayerNpcEntity playerNpc) {
        return isScaffoldStack(playerNpc.getMainHandItem())
                || InventoryUtils.hasItem(playerNpc, TerraformBuildSiteGoal::isScaffoldStack);
    }

    private static boolean hasTool(PlayerNpcEntity playerNpc, Class<?> toolClass) {
        return toolClass.isInstance(playerNpc.getMainHandItem().getItem())
                || toolClass.isInstance(playerNpc.getOffhandItem().getItem())
                || InventoryUtils.hasItem(playerNpc, stack -> toolClass.isInstance(stack.getItem()));
    }

    private static boolean isFillStack(ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem)
                || stack.is(ItemTags.LOGS)
                || stack.is(ItemTags.PLANKS)
                || stack.is(ItemTags.SAPLINGS)
                || stack.is(Items.TORCH)
                || stack.getItem() instanceof BedItem) {
            return false;
        }
        for (net.minecraft.world.item.Item item : FILL_ITEMS) {
            if (stack.is(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isScaffoldStack(ItemStack stack) {
        if (!isFillStack(stack)
                || stack.is(Items.SAND)
                || stack.is(Items.RED_SAND)
                || stack.is(Items.GRAVEL)
                || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        return state.getFluidState().isEmpty()
                && !state.canBeReplaced()
                && state.canOcclude();
    }

    private Optional<ItemStack> useFillBlockInMainHand() {
        if (isFillStack(this.playerNpc.getMainHandItem())) {
            return Optional.of(this.playerNpc.getMainHandItem());
        }

        ItemStack fill = this.playerNpc.consumeInventoryItem(TerraformBuildSiteGoal::isFillStack, 1)
                .orElse(ItemStack.EMPTY);
        if (fill.isEmpty()) {
            return Optional.empty();
        }

        this.setTemporaryMainHand(fill);
        return Optional.of(fill);
    }

    private Optional<ItemStack> useScaffoldBlockInMainHand() {
        if (isScaffoldStack(this.playerNpc.getMainHandItem())) {
            return Optional.of(this.playerNpc.getMainHandItem());
        }

        ItemStack block = this.playerNpc.consumeInventoryItem(TerraformBuildSiteGoal::isScaffoldStack, 1)
                .orElse(ItemStack.EMPTY);
        if (block.isEmpty()) {
            return Optional.empty();
        }

        this.setTemporaryMainHand(block);
        return Optional.of(block);
    }

    private int countScaffoldBlocks() {
        int count = isScaffoldStack(this.playerNpc.getMainHandItem()) ? this.playerNpc.getMainHandItem().getCount() : 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (isScaffoldStack(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private boolean canPlaceWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .noneMatch(box -> box.move(pos).intersects(this.playerNpc.getBoundingBox().inflate(0.05D)));
    }

    private boolean canPlaceSupportWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        List<AABB> boxes = state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .map(box -> box.move(pos))
                .toList();
        AABB currentBox = this.playerNpc.getBoundingBox();
        if (boxes.stream().noneMatch(box -> box.intersects(currentBox))) {
            return true;
        }

        double blockTopY = pos.getY() + 1.0D;
        if (blockTopY <= currentBox.minY + 0.02D) {
            return true;
        }

        double snapUp = blockTopY - currentBox.minY;
        if (snapUp < -0.05D || snapUp > 0.35D) {
            return false;
        }

        AABB snappedBox = currentBox.move(0.0D, snapUp + 0.01D, 0.0D);
        return boxes.stream().noneMatch(box -> box.intersects(snappedBox.inflate(0.001D)))
                && serverLevel.noCollision(this.playerNpc, snappedBox);
    }

    private boolean hasScaffoldPlacementClearance() {
        if (this.scaffoldPlacePos == null) {
            return false;
        }

        double clearedY = this.playerNpc.getBoundingBox().minY - this.scaffoldPlacePos.getY();
        return clearedY >= SCAFFOLD_PLACE_CLEARANCE_Y
                || this.scaffoldPlaceWaitTicks >= SCAFFOLD_FORCE_PLACE_TICKS
                && clearedY >= SCAFFOLD_FALLBACK_PLACE_CLEARANCE_Y
                && this.playerNpc.getDeltaMovement().y <= 0.05D;
    }

    private boolean canPlaceScaffoldWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (this.hasOtherEntityInBlock(serverLevel, pos)) {
            return false;
        }

        List<AABB> boxes = state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .map(box -> box.move(pos))
                .toList();
        if (boxes.stream().noneMatch(box -> box.intersects(this.playerNpc.getBoundingBox().inflate(0.02D)))) {
            return true;
        }

        double snapUp = pos.getY() + 1.0D - this.playerNpc.getBoundingBox().minY;
        if (snapUp < -0.05D || snapUp > 0.35D) {
            return false;
        }

        AABB snappedBox = this.playerNpc.getBoundingBox().move(0.0D, snapUp + 0.01D, 0.0D);
        return boxes.stream().noneMatch(box -> box.intersects(snappedBox.inflate(0.001D)))
                && serverLevel.noCollision(this.playerNpc, snappedBox);
    }

    private void snapAboveScaffoldIfNeeded(BlockPos pos) {
        double topY = pos.getY() + 1.0D;
        if (this.playerNpc.getBoundingBox().minY >= topY) {
            return;
        }

        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.setPos(this.playerNpc.getX(), topY, this.playerNpc.getZ());
        this.playerNpc.setDeltaMovement(motion.x, Math.max(0.0D, motion.y), motion.z);
        this.playerNpc.fallDistance = 0.0F;
    }

    private boolean hasOtherEntityInBlock(ServerLevel serverLevel, BlockPos pos) {
        return !serverLevel.getEntities(
                this.playerNpc,
                new AABB(pos).inflate(0.05D),
                entity -> entity.isAlive() && !(entity instanceof ItemEntity)
        ).isEmpty();
    }

    private boolean hasOpenBodySpace(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty();
    }

    private void lookDownAt(BlockPos pos) {
        this.playerNpc.getLookControl().setLookAt(
                pos.getX() + 0.5D,
                pos.getY() - 0.5D,
                pos.getZ() + 0.5D,
                60.0F,
                60.0F
        );
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

        return Math.max(1, Math.min(MAX_MINE_TICKS, (int) Math.ceil(1.0F / progressPerTick)));
    }

    private void equipToolFor(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            if (!this.equipTool(ShovelItem.class)) {
                this.equipEmptyHand();
            }
        } else if (state.is(BlockTags.MINEABLE_WITH_AXE) || state.is(BlockTags.LOGS)) {
            if (!this.equipTool(AxeItem.class)) {
                this.equipEmptyHand();
            }
        } else if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            if (!this.equipTool(PickaxeItem.class)) {
                this.equipEmptyHand();
            }
        } else if (state.is(BlockTags.LEAVES)) {
            this.equipEmptyHand();
        }
    }

    private boolean equipTool(Class<?> toolClass) {
        if (toolClass.isInstance(this.playerNpc.getMainHandItem().getItem())) {
            return true;
        }
        if (this.restorePreviousMainHandForTool(toolClass)) {
            return true;
        }

        ItemStack tool = this.playerNpc.consumeInventoryItem(stack -> toolClass.isInstance(stack.getItem()), 1)
                .orElse(ItemStack.EMPTY);
        if (tool.isEmpty()) {
            return false;
        }

        this.setTemporaryMainHand(tool);
        return true;
    }

    private void equipEmptyHand() {
        if (!this.playerNpc.getMainHandItem().isEmpty()) {
            this.setTemporaryMainHand(ItemStack.EMPTY);
        }
    }

    private void setTemporaryMainHand(ItemStack stack) {
        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!this.usingTemporaryMainHand) {
            this.previousMainHand = currentMainHand;
            this.usingTemporaryMainHand = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, stack);
    }

    private boolean restorePreviousMainHandForTool(Class<?> toolClass) {
        if (!this.usingTemporaryMainHand || !toolClass.isInstance(this.previousMainHand.getItem())) {
            return false;
        }

        ItemStack currentMainHand = this.playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, this.previousMainHand)
                && !InventoryUtils.addItem(this.playerNpc, currentMainHand)) {
            this.playerNpc.spawnAtLocation(currentMainHand);
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryMainHand = false;
        return true;
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryMainHand) {
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
        this.usingTemporaryMainHand = false;
    }

    private record BuildContext(PlayerNpcBuildLayout layout, BlockPos origin) {
    }

    private record TerraformTarget(BlockPos pos, TerraformPhase phase, BlockState targetState) {
    }

    private enum TerraformPhase {
        CLEAR,
        CLEAR_WATER,
        FILL_SUPPORT
    }
}
