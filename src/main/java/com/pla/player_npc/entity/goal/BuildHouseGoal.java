package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.player_npc.util.PlayerNpcBuildLayout;
import com.pla.player_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.player_npc.util.PlayerNpcCraftingUtil;
import com.pla.player_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public class BuildHouseGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 90;
    private static final int MATERIAL_RETRY_COOLDOWN_TICKS = 20 * 12;
    private static final int MIN_BASE_BUILD_BLOCKS = 16;
    public static final int MIN_HOME_BUILD_COMMIT_BLOCKS = 32;
    private static final int PARTIAL_BASE_MATERIAL_TOLERANCE = 12;
    private static final int BUILD_SEARCH_RADIUS = 14;
    private static final int MAX_RANDOM_LAYOUT_ATTEMPTS = 24;
    private static final int MAX_TERRAIN_CLEARS_PER_BUILD_SITE = 36;

    private final PlayerNpcEntity playerNpc;
    private final List<BuildPlacement> blueprint = new ArrayList<>();
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private PlayerNpcBuildLayout selectedLayout;
    private BlockPos origin;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int placeDelay;
    private boolean ranOutOfMaterials;
    private boolean showingPlacementItem;
    private boolean waitingForPlacementClearance;
    private boolean placedBlockThisTick;
    private BlockState placedBlockStateThisTick;
    private BlockPos placedBlockSoundPos;

    public BuildHouseGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean hasReadyHomeBuildWork(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc.getBuildHouseCooldown() > 0) {
            return false;
        }

        int availableBlocks = countAvailableBuildingBlocks(playerNpc);
        if (availableBlocks < MIN_HOME_BUILD_COMMIT_BLOCKS) {
            return false;
        }

        java.util.Optional<PlayerNpcHomeUtil.HomeArea> existingHome = PlayerNpcHomeUtil.getHome(playerNpc);
        if (existingHome.isEmpty()) {
            return false;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = existingHome.get();
        List<PlayerNpcBuildLayout> layouts = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc)
                .flatMap(layoutId -> PlayerNpcBuildLayoutLoader.getLayouts().stream()
                        .filter(layout -> layout.id().equals(layoutId))
                        .findFirst())
                .map(List::of)
                .orElse(PlayerNpcBuildLayoutLoader.getLayouts());
        if (layouts.isEmpty()) {
            return false;
        }

        BuildHouseGoal checker = new BuildHouseGoal(playerNpc);
        for (PlayerNpcBuildLayout layout : layouts) {
            if (layout.width() == homeArea.width()
                    && layout.depth() == homeArea.depth()
                    && checker.canAffordLayoutStart(layout, availableBlocks, true)
                    && checker.canBuildAtExistingHome(serverLevel, layout, homeArea.origin())
                    && checker.hasUnfinishedPlacement(serverLevel, layout, homeArea.origin())) {
                return true;
            }
        }
        return false;
    }

    public static int countAvailableBuildingBlocks(PlayerNpcEntity playerNpc) {
        int count = 0;
        for (int i = 0; i < playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = playerNpc.getInventory().getItem(i);
            if (!stack.isEmpty()
                    && isBuildingBlockItem(stack)
                    && !PlayerNpcCraftingUtil.isPlanks(stack)) {
                count += stack.getCount();
            }
        }
        return count + PlayerNpcCraftingUtil.countPlankEquivalent(playerNpc.getInventory(), playerNpc.getRawLogReserveTarget());
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getBuildHouseCooldown() > 0) {
            return false;
        }

        int availableBlocks = this.countBuildingBlocks();
        BuildSelection selection = this.findBuildSelection(serverLevel, availableBlocks);
        if (selection == null) {
            return false;
        }

        this.selectedLayout = selection.layout();
        this.origin = selection.origin();
        this.homeArea = new PlayerNpcHomeUtil.HomeArea(this.origin, this.selectedLayout.width(), this.selectedLayout.depth());
        PlayerNpcHomeUtil.setHome(this.playerNpc, this.homeArea, this.selectedLayout.id());
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.origin != null
                && !this.blueprint.isEmpty()
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null;
    }

    @Override
    public void start() {
        this.blueprint.clear();
        this.blueprint.addAll(this.createBlueprint(this.selectedLayout, this.origin));
        this.blueprint.sort(Comparator.comparingInt(placement -> placement.pos().getY()));
        this.placeDelay = 0;
        this.ranOutOfMaterials = false;
        this.previousMainHand = ItemStack.EMPTY;
        this.showingPlacementItem = false;
        this.waitingForPlacementClearance = false;
        this.placedBlockThisTick = false;
        this.placedBlockStateThisTick = null;
        this.placedBlockSoundPos = null;
        this.playerNpc.setCurrentAiState("ai.player_npc.building_house");
        this.playerNpc.setCurrentAiDetail(this.selectedLayout.id());
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.origin == null || this.blueprint.isEmpty()) {
            return;
        }

        BuildPlacement placement = this.blueprint.get(0);
        BlockPos target = placement.pos();
        this.previewPlacementItem(serverLevel, placement);
        this.playerNpc.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.playerNpc.distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D) > 4.0D * 4.0D) {
            this.playerNpc.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D);
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.placeDelay++ < 8) {
            return;
        }
        this.placeDelay = 0;

        if (!this.placeBlueprintBlock(serverLevel, placement)) {
            if (this.waitingForPlacementClearance) {
                return;
            }
            this.blueprint.clear();
            return;
        }

        if (this.placedBlockThisTick && this.placedBlockStateThisTick != null) {
            this.playerNpc.triggerMainHandUseAnimation();
            PlayerNpcBlockSoundUtil.playPlaceSound(
                    serverLevel,
                    this.placedBlockSoundPos == null ? target : this.placedBlockSoundPos,
                    this.placedBlockStateThisTick,
                    this.playerNpc
            );
        }
        this.blueprint.remove(0);
    }

    @Override
    public void stop() {
        this.restorePreviousMainHand();
        if (!this.playerNpc.level().isClientSide) {
            int cooldown = this.ranOutOfMaterials
                    ? MATERIAL_RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 8)
                    : COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 60);
            this.playerNpc.setBuildHouseCooldown(cooldown);
            this.playerNpc.setManageHomeCooldown(0);
        }
        this.blueprint.clear();
        this.homeArea = null;
        this.selectedLayout = null;
        this.origin = null;
        this.placeDelay = 0;
        this.ranOutOfMaterials = false;
        this.waitingForPlacementClearance = false;
        this.placedBlockThisTick = false;
        this.placedBlockStateThisTick = null;
        this.placedBlockSoundPos = null;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private List<BuildPlacement> createBlueprint(PlayerNpcBuildLayout layout, BlockPos origin) {
        return layout.blocks().stream()
                .map(block -> new BuildPlacement(block.toWorld(origin), block.role()))
                .toList();
    }

    private BuildSelection findBuildSelection(ServerLevel serverLevel, int availableBlocks) {
        java.util.Optional<PlayerNpcHomeUtil.HomeArea> existingHome = PlayerNpcHomeUtil.getHome(this.playerNpc);
        List<PlayerNpcBuildLayout> layouts = PlayerNpcBuildLayoutLoader.getLayouts().stream()
                .filter(layout -> this.canAffordLayoutStart(layout, availableBlocks, existingHome.isPresent()))
                .toList();
        if (layouts.isEmpty()) {
            return null;
        }

        if (existingHome.isPresent()) {
            PlayerNpcHomeUtil.HomeArea homeArea = existingHome.get();
            List<PlayerNpcBuildLayout> homeLayouts = PlayerNpcHomeUtil.getHomeLayoutId(this.playerNpc)
                    .flatMap(layoutId -> layouts.stream().filter(layout -> layout.id().equals(layoutId)).findFirst())
                    .map(layout -> List.of(layout))
                    .orElse(layouts);
            for (PlayerNpcBuildLayout layout : homeLayouts) {
                if (layout.width() == homeArea.width()
                        && layout.depth() == homeArea.depth()
                        && this.canBuildAtExistingHome(serverLevel, layout, homeArea.origin())
                        && this.hasUnfinishedPlacement(serverLevel, layout, homeArea.origin())) {
                    return new BuildSelection(layout, homeArea.origin());
                }
            }
            return null;
        }

        for (int i = 0; i < Math.min(MAX_RANDOM_LAYOUT_ATTEMPTS, layouts.size() * 2); i++) {
            PlayerNpcBuildLayout layout = layouts.get(this.playerNpc.getRandom().nextInt(layouts.size()));
            BlockPos origin = this.findBuildOrigin(serverLevel, layout);
            if (origin != null) {
                return new BuildSelection(layout, origin);
            }
        }

        for (PlayerNpcBuildLayout layout : layouts) {
            BlockPos origin = this.findBuildOrigin(serverLevel, layout);
            if (origin != null) {
                return new BuildSelection(layout, origin);
            }
        }
        return null;
    }

    private boolean hasUnfinishedPlacement(ServerLevel serverLevel, PlayerNpcBuildLayout layout, BlockPos origin) {
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.blocks()) {
            BuildPlacement placement = new BuildPlacement(block.toWorld(origin), block.role());
            if (this.isAlreadyBuilt(serverLevel, placement)) {
                continue;
            }

            String role = placement.role().toLowerCase(Locale.ROOT);
            if (!this.isOptionalUtilityRole(role) || this.canProvideOptionalRole(role)) {
                return true;
            }
        }
        return false;
    }

    private boolean canProvideOptionalRole(String role) {
        if (role.contains("door")) {
            return !this.peekDoor().isEmpty();
        }
        if (role.contains("torch")) {
            return !this.peekTorch().isEmpty();
        }
        if (role.contains("fence")) {
            return !this.peekFence().isEmpty();
        }
        if (role.contains("trapdoor")) {
            return !this.peekTrapdoor().isEmpty();
        }
        if (role.contains("stair")) {
            return !this.peekStair().isEmpty();
        }
        return false;
    }

    private boolean canAffordLayoutStart(PlayerNpcBuildLayout layout, int availableBlocks, boolean hasExistingHome) {
        if (this.requiredBuildingBlocks(layout) <= availableBlocks) {
            return true;
        }
        return availableBlocks >= MIN_BASE_BUILD_BLOCKS
                && this.requiredBaseBlocks(layout) <= availableBlocks + PARTIAL_BASE_MATERIAL_TOLERANCE;
    }

    private BlockPos findBuildOrigin(ServerLevel serverLevel, PlayerNpcBuildLayout layout) {
        BlockPos center = this.playerNpc.blockPosition();
        List<BlockPos> origins = new ArrayList<>();
        for (int x = center.getX() - BUILD_SEARCH_RADIUS; x <= center.getX() + BUILD_SEARCH_RADIUS; x++) {
            for (int z = center.getZ() - BUILD_SEARCH_RADIUS; z <= center.getZ() + BUILD_SEARCH_RADIUS; z++) {
                int y = serverLevel.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                origins.add(new BlockPos(x, y, z));
            }
        }

        origins.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos originCandidate : origins) {
            if (this.canBuildAt(serverLevel, layout, originCandidate, false)) {
                return originCandidate;
            }
        }
        return null;
    }

    private boolean canBuildAt(ServerLevel serverLevel, PlayerNpcBuildLayout layout, BlockPos origin, boolean allowHomeUtilities) {
        return this.canBuildAt(serverLevel, layout, origin, allowHomeUtilities, false);
    }

    private boolean canBuildAtExistingHome(ServerLevel serverLevel, PlayerNpcBuildLayout layout, BlockPos origin) {
        return this.canBuildAt(serverLevel, layout, origin, true, true);
    }

    private boolean canBuildAt(ServerLevel serverLevel, PlayerNpcBuildLayout layout, BlockPos origin, boolean allowHomeUtilities, boolean allowExistingHouseBlocks) {
        int terrainClears = 0;
        for (long packedFootprint : layout.footprint()) {
            int x = (int) (packedFootprint >> 32);
            int z = (int) packedFootprint;
            BlockPos floor = origin.offset(x, 0, z);
            if (!serverLevel.getBlockState(floor.below()).isSolidRender(serverLevel, floor.below())
                    && !serverLevel.getBlockState(floor.below(2)).isSolidRender(serverLevel, floor.below(2))) {
                return false;
            }
            for (int y = 0; y < layout.height(); y++) {
                BlockPos checkPos = origin.offset(x, y, z);
                if (!serverLevel.isInWorldBounds(checkPos)
                        || !serverLevel.getWorldBorder().isWithinBounds(checkPos)) {
                    return false;
                }
                BlockState state = serverLevel.getBlockState(checkPos);
                if (PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, checkPos)
                        || allowHomeUtilities && y > 0 && this.isHomeUtility(state)
                        || allowExistingHouseBlocks && this.isLikelyPlacedHouseBlock(serverLevel, checkPos, state)) {
                    continue;
                }
                if (!this.canClearForBuild(serverLevel, checkPos, state)
                        || ++terrainClears > MAX_TERRAIN_CLEARS_PER_BUILD_SITE) {
                    return false;
                }
            }
        }
        return true;
    }

    private int countBuildingBlocks() {
        return countAvailableBuildingBlocks(this.playerNpc);
    }

    private int requiredBuildingBlocks(PlayerNpcBuildLayout layout) {
        return (int) layout.blocks().stream()
                .filter(block -> !this.isOptionalUtilityRole(block.role()))
                .count();
    }

    private int requiredBaseBlocks(PlayerNpcBuildLayout layout) {
        return (int) layout.blocks().stream()
                .filter(block -> this.isBaseRole(block.role()))
                .count();
    }

    private boolean placeBlueprintBlock(ServerLevel serverLevel, BuildPlacement placement) {
        this.waitingForPlacementClearance = false;
        this.placedBlockThisTick = false;
        this.placedBlockStateThisTick = null;
        this.placedBlockSoundPos = null;
        if (this.isAlreadyBuilt(serverLevel, placement)) {
            return true;
        }
        if (!this.preparePlacementPos(serverLevel, placement.pos())) {
            return false;
        }

        String role = placement.role().toLowerCase(Locale.ROOT);
        if (this.isBaseRole(role)) {
            ItemStack floorStack = this.consumeBuildBlock();
            if (floorStack.isEmpty()) {
                this.ranOutOfMaterials = true;
                return false;
            }
            return this.placeOptionalBlock(serverLevel, placement.pos(), floorStack, true)
                    && !this.waitingForPlacementClearance;
        }
        if (role.contains("door")) {
            this.placeDoor(serverLevel, placement.pos());
            return !this.waitingForPlacementClearance;
        }
        if (role.contains("torch")) {
            this.placeOptionalBlock(serverLevel, placement.pos(), this.consumeTorch(), false);
            return !this.waitingForPlacementClearance;
        }
        if (role.contains("fence")) {
            this.placeOptionalBlock(serverLevel, placement.pos(), this.consumeFence(), false);
            return !this.waitingForPlacementClearance;
        }
        if (role.contains("trapdoor")) {
            this.placeOptionalBlock(serverLevel, placement.pos(), this.consumeTrapdoor(), false);
            return !this.waitingForPlacementClearance;
        }
        if (role.contains("stair")) {
            this.placeOptionalBlock(serverLevel, placement.pos(), this.consumeStair(), false);
            return !this.waitingForPlacementClearance;
        }

        if (!PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, placement.pos())) {
            return true;
        }

        ItemStack blockStack = this.consumeBuildBlock();
        if (blockStack.isEmpty() || !(blockStack.getItem() instanceof BlockItem blockItem)) {
            this.ranOutOfMaterials = true;
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        if (!this.canPlaceWithoutClipping(serverLevel, placement.pos(), state)) {
            this.waitForPlacementClearance(placement.pos(), blockStack);
            return false;
        }

        this.showPlacementItem(blockStack);
        if (!serverLevel.setBlockAndUpdate(placement.pos(), state)) {
            this.returnStack(blockStack);
            return false;
        }
        this.markPlacedBlock(placement.pos(), state);
        return true;
    }

    private ItemStack consumeBuildBlock() {
        ItemStack blockStack = InventoryUtils.consumeItem(this.playerNpc, this::isBuildingBlock, 1)
                .orElse(ItemStack.EMPTY);
        if (!blockStack.isEmpty()) {
            return blockStack;
        }
        if (PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 1, this.playerNpc.getRawLogReserveTarget())) {
            return new ItemStack(Items.OAK_PLANKS);
        }
        return ItemStack.EMPTY;
    }

    private void placeDoor(ServerLevel serverLevel, BlockPos pos) {
        if (!this.preparePlacementPos(serverLevel, pos)
                || !this.preparePlacementPos(serverLevel, pos.above())
                || !PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)
                || !PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos.above())) {
            return;
        }

        ItemStack doorStack = this.consumeDoor();
        if (doorStack.isEmpty() || !(doorStack.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof DoorBlock doorBlock)) {
            this.returnStack(doorStack);
            return;
        }

        Direction facing = this.playerNpc.getDirection();
        BlockState lower = doorBlock.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState upper = doorBlock.defaultBlockState()
                .setValue(DoorBlock.FACING, facing)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        if (!this.canPlaceWithoutClipping(serverLevel, pos, lower)
                || !this.canPlaceWithoutClipping(serverLevel, pos.above(), upper)) {
            this.waitForPlacementClearance(pos, doorStack);
            return;
        }
        this.showPlacementItem(doorStack);
        serverLevel.setBlockAndUpdate(pos, lower);
        serverLevel.setBlockAndUpdate(pos.above(), upper);
        this.markPlacedBlock(pos, lower);
    }

    private boolean placeOptionalBlock(ServerLevel serverLevel, BlockPos pos, ItemStack stack, boolean required) {
        if (stack.isEmpty()) {
            if (required) {
                this.ranOutOfMaterials = true;
            }
            return !required;
        }
        if (!this.preparePlacementPos(serverLevel, pos)
                || !PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)
                || !(stack.getItem() instanceof BlockItem blockItem)) {
            this.returnStack(stack);
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        if (!state.canSurvive(serverLevel, pos)) {
            this.returnStack(stack);
            return false;
        }
        if (!this.canPlaceWithoutClipping(serverLevel, pos, state)) {
            this.waitForPlacementClearance(pos, stack);
            return false;
        }
        this.showPlacementItem(stack);
        if (!serverLevel.setBlockAndUpdate(pos, state)) {
            this.returnStack(stack);
            return false;
        }
        this.markPlacedBlock(pos, state);
        return true;
    }

    private void markPlacedBlock(BlockPos pos, BlockState state) {
        this.placedBlockThisTick = true;
        this.placedBlockStateThisTick = state;
        this.placedBlockSoundPos = pos.immutable();
    }

    private void previewPlacementItem(ServerLevel serverLevel, BuildPlacement placement) {
        ItemStack preview = this.getPlacementPreview(serverLevel, placement);
        if (this.playerNpc.getMainHandAttackAnimationTicks() > 0
                && ItemStack.isSameItemSameTags(this.playerNpc.getMainHandItem(), preview)) {
            return;
        }
        this.showPlacementItem(preview);
    }

    private ItemStack getPlacementPreview(ServerLevel serverLevel, BuildPlacement placement) {
        String role = placement.role().toLowerCase(Locale.ROOT);
        if (this.isBaseRole(role)) {
            return this.peekBuildBlock();
        }
        if (role.contains("door")) {
            return this.peekDoor();
        }
        if (role.contains("torch")) {
            return this.peekTorch();
        }
        if (role.contains("fence")) {
            return this.peekFence();
        }
        if (role.contains("trapdoor")) {
            return this.peekTrapdoor();
        }
        if (role.contains("stair")) {
            return this.peekStair();
        }
        return PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, placement.pos())
                ? this.peekBuildBlock()
                : ItemStack.EMPTY;
    }

    private ItemStack peekBuildBlock() {
        ItemStack stack = this.peekInventoryItem(this::isBuildingBlock);
        if (!stack.isEmpty()) {
            return stack;
        }
        return PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()) > 0
                ? new ItemStack(Items.OAK_PLANKS)
                : ItemStack.EMPTY;
    }

    private ItemStack peekDoor() {
        ItemStack stack = this.peekInventoryItem(candidate -> candidate.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof DoorBlock);
        if (!stack.isEmpty()) {
            return stack;
        }
        return PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()) >= 6
                ? new ItemStack(Items.OAK_DOOR)
                : ItemStack.EMPTY;
    }

    private ItemStack peekTorch() {
        if (InventoryUtils.hasItem(this.playerNpc, Items.TORCH)) {
            return new ItemStack(Items.TORCH);
        }
        return PlayerNpcCraftingUtil.canCraftTorches(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())
                ? new ItemStack(Items.TORCH)
                : ItemStack.EMPTY;
    }

    private ItemStack peekFence() {
        ItemStack stack = this.peekInventoryItem(candidate -> candidate.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof FenceBlock);
        if (!stack.isEmpty()) {
            return stack;
        }
        return PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 4, 2, this.playerNpc.getRawLogReserveTarget())
                ? new ItemStack(Items.OAK_FENCE)
                : ItemStack.EMPTY;
    }

    private ItemStack peekTrapdoor() {
        ItemStack stack = this.peekInventoryItem(candidate -> candidate.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof TrapDoorBlock);
        if (!stack.isEmpty()) {
            return stack;
        }
        return PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()) >= 6
                ? new ItemStack(Items.OAK_TRAPDOOR)
                : ItemStack.EMPTY;
    }

    private ItemStack peekStair() {
        ItemStack stack = this.peekInventoryItem(candidate -> candidate.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof StairBlock);
        if (!stack.isEmpty()) {
            return stack;
        }
        return PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()) >= 6
                ? new ItemStack(Items.OAK_STAIRS)
                : ItemStack.EMPTY;
    }

    private ItemStack peekInventoryItem(Predicate<ItemStack> matcher) {
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                ItemStack preview = stack.copy();
                preview.setCount(1);
                return preview;
            }
        }
        return ItemStack.EMPTY;
    }

    private void showPlacementItem(ItemStack stack) {
        if (stack.isEmpty()) {
            if (this.showingPlacementItem) {
                this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
            return;
        }

        if (!this.showingPlacementItem) {
            this.previousMainHand = this.playerNpc.getMainHandItem().copy();
            this.showingPlacementItem = true;
        }

        ItemStack preview = stack.copy();
        preview.setCount(1);
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, preview);
    }

    private void restorePreviousMainHand() {
        if (!this.showingPlacementItem) {
            return;
        }

        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.showingPlacementItem = false;
    }

    private boolean preparePlacementPos(ServerLevel serverLevel, BlockPos pos) {
        if (PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)) {
            return true;
        }

        BlockState state = serverLevel.getBlockState(pos);
        if (this.isHomeUtility(state)) {
            return true;
        }
        if (!this.canClearForBuild(serverLevel, pos, state)) {
            return false;
        }

        serverLevel.destroyBlock(pos, true, this.playerNpc);
        return PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos);
    }

    private boolean canClearForBuild(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return !state.isAir()
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && serverLevel.getBlockEntity(pos) == null
                && !this.isHomeUtility(state)
                && (state.canBeReplaced()
                || state.is(BlockTags.MINEABLE_WITH_SHOVEL)
                || state.is(BlockTags.LEAVES));
    }

    private boolean isAlreadyBuilt(ServerLevel serverLevel, BuildPlacement placement) {
        BlockState state = serverLevel.getBlockState(placement.pos());
        if (PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, placement.pos())) {
            return false;
        }

        String role = placement.role().toLowerCase(Locale.ROOT);
        if (role.contains("door")) {
            return state.getBlock() instanceof DoorBlock;
        }
        if (role.contains("torch")) {
            return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH);
        }
        if (role.contains("fence")) {
            return state.getBlock() instanceof FenceBlock;
        }
        if (role.contains("trapdoor")) {
            return state.getBlock() instanceof TrapDoorBlock;
        }
        if (role.contains("stair")) {
            return state.getBlock() instanceof StairBlock;
        }
        return this.isLikelyPlacedHouseBlock(serverLevel, placement.pos(), state);
    }

    private boolean isLikelyPlacedHouseBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (state.isAir()
                || !state.getFluidState().isEmpty()
                || serverLevel.getBlockEntity(pos) != null) {
            return false;
        }
        if (this.isHomeUtility(state)
                || state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof FenceBlock
                || state.getBlock() instanceof StairBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.is(Blocks.TORCH)
                || state.is(Blocks.WALL_TORCH)) {
            return true;
        }

        ItemStack blockStack = new ItemStack(state.getBlock());
        return this.isBuildingBlock(blockStack)
                && (state.is(BlockTags.MINEABLE_WITH_AXE) || state.is(BlockTags.MINEABLE_WITH_PICKAXE));
    }

    private ItemStack consumeDoor() {
        ItemStack door = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof DoorBlock, 1).orElse(ItemStack.EMPTY);
        if (!door.isEmpty()) {
            return door;
        }
        if (PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 6, this.playerNpc.getRawLogReserveTarget())) {
            return new ItemStack(Items.OAK_DOOR);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack consumeTorch() {
        ItemStack torch = this.playerNpc.consumeInventoryItem(Items.TORCH, 1).orElse(ItemStack.EMPTY);
        if (!torch.isEmpty()) {
            return torch;
        }
        if (PlayerNpcCraftingUtil.tryCraftTorches(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
            return this.playerNpc.consumeInventoryItem(Items.TORCH, 1).orElse(ItemStack.EMPTY);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack consumeFence() {
        ItemStack fence = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof FenceBlock, 1).orElse(ItemStack.EMPTY);
        if (!fence.isEmpty()) {
            return fence;
        }
        if (PlayerNpcCraftingUtil.tryCraftFences(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
            return this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof FenceBlock, 1).orElse(ItemStack.EMPTY);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack consumeTrapdoor() {
        ItemStack trapdoor = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof TrapDoorBlock, 1).orElse(ItemStack.EMPTY);
        if (!trapdoor.isEmpty()) {
            return trapdoor;
        }
        if (PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 6, this.playerNpc.getRawLogReserveTarget())) {
            return new ItemStack(Items.OAK_TRAPDOOR);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack consumeStair() {
        ItemStack stair = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof StairBlock, 1).orElse(ItemStack.EMPTY);
        if (!stair.isEmpty()) {
            return stair;
        }
        if (PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 6, this.playerNpc.getRawLogReserveTarget())) {
            return new ItemStack(Items.OAK_STAIRS);
        }
        return ItemStack.EMPTY;
    }

    private void returnStack(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private boolean canPlaceWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .noneMatch(box -> box.move(pos).intersects(this.playerNpc.getBoundingBox().inflate(0.05D)));
    }

    private void waitForPlacementClearance(BlockPos pos, ItemStack stack) {
        this.returnStack(stack);
        this.waitingForPlacementClearance = true;
        this.placeDelay = 0;
        this.moveAwayFromPlacement(pos);
    }

    private void moveAwayFromPlacement(BlockPos pos) {
        double dx = this.playerNpc.getX() - (pos.getX() + 0.5D);
        double dz = this.playerNpc.getZ() - (pos.getZ() + 0.5D);
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-4D) {
            Direction direction = this.playerNpc.getDirection().getOpposite();
            dx = direction.getStepX();
            dz = direction.getStepZ();
            distance = 1.0D;
        }

        double targetX = this.playerNpc.getX() + dx / distance * 1.2D;
        double targetZ = this.playerNpc.getZ() + dz / distance * 1.2D;
        this.playerNpc.getNavigation().moveTo(targetX, this.playerNpc.getY(), targetZ, 1.0D);
    }

    private boolean isOptionalUtilityRole(String role) {
        String normalizedRole = role.toLowerCase(Locale.ROOT);
        return normalizedRole.contains("door")
                || normalizedRole.contains("torch")
                || normalizedRole.contains("fence")
                || normalizedRole.contains("trapdoor")
                || normalizedRole.contains("stair");
    }

    private boolean isBaseRole(String role) {
        String normalizedRole = role.toLowerCase(Locale.ROOT);
        return normalizedRole.contains("floor") || normalizedRole.contains("foundation");
    }

    private boolean isBuildingBlock(ItemStack stack) {
        return isBuildingBlockItem(stack);
    }

    private static boolean isBuildingBlockItem(ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || stack.is(ItemTags.LOGS)
                || stack.is(ItemTags.SAPLINGS)
                || stack.is(Items.CRAFTING_TABLE)
                || stack.is(Items.FURNACE)
                || stack.is(Items.CHEST)
                || stack.is(Items.TORCH)
                || stack.getItem() instanceof BedItem) {
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        return state.canOcclude()
                && !state.canBeReplaced()
                && state.getFluidState().isEmpty();
    }

    private boolean isHomeUtility(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
                || state.is(net.minecraft.world.level.block.Blocks.CHEST)
                || state.getBlock() instanceof net.minecraft.world.level.block.BedBlock;
    }

    private record BuildPlacement(BlockPos pos, String role) {}

    private record BuildSelection(PlayerNpcBuildLayout layout, BlockPos origin) {}
}
