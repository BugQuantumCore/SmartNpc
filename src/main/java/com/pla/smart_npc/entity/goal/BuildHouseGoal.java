package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.smart_npc.util.PlayerNpcBuildLayout;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class BuildHouseGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 90;
    private static final int MATERIAL_RETRY_COOLDOWN_TICKS = 20 * 20;
    private static final int MIN_BASE_BUILD_BLOCKS = 16;
    public static final int MIN_HOME_BUILD_COMMIT_BLOCKS = 32;
    private static final int BUILD_SEARCH_RADIUS = 14;
    private static final int MAX_RANDOM_LAYOUT_ATTEMPTS = 24;
    private static final int MAX_TERRAIN_CLEARS_PER_BUILD_SITE = 36;
    private static final double BUILD_DISTANCE_SQR = 4.0D * 4.0D;

    private final PlayerNpcEntity playerNpc;
    private final List<PlayerNpcBuildLayout.RelativeBlock> blueprint = new ArrayList<>();
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private PlayerNpcBuildLayout selectedLayout;
    private BlockPos origin;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int placeDelay;
    private int completedPlacements;
    private int totalPlacements;
    private boolean ranOutOfMaterials;
    private boolean showingPlacementItem;
    private boolean waitingForPlacementClearance;
    private boolean placedBlockThisTick;
    private BlockState placedBlockStateThisTick;
    private BlockPos placedBlockSoundPos;
    private String missingMaterial = "";

    public BuildHouseGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean hasReadyHomeBuildWork(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!playerNpc.hasInterest(PlayerNpcInterest.BUILDING) || playerNpc.getBuildHouseCooldown() > 0) {
            return false;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> existingHome = PlayerNpcHomeUtil.getHome(playerNpc);
        if (existingHome.isEmpty()) {
            return false;
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc)
                .flatMap(layoutId -> PlayerNpcBuildLayoutLoader.getLayouts().stream()
                        .filter(candidate -> candidate.id().equals(layoutId))
                        .findFirst());
        if (layout.isEmpty()) {
            return false;
        }

        BuildHouseGoal checker = new BuildHouseGoal(playerNpc);
        PlayerNpcHomeUtil.HomeArea homeArea = existingHome.get();
        return layout.get().width() == homeArea.width()
                && layout.get().depth() == homeArea.depth()
                && checker.canBuildAt(serverLevel, layout.get(), homeArea.origin(), true)
                && checker.hasUnfinishedPlacement(serverLevel, layout.get(), homeArea.origin())
                && checker.hasMaterialForNextPlacement(serverLevel, layout.get(), homeArea.origin());
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
                || !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null
                || this.playerNpc.getBuildHouseCooldown() > 0) {
            return false;
        }

        BuildSelection selection = this.findBuildSelection(serverLevel);
        if (selection == null) {
            return false;
        }

        this.selectedLayout = selection.layout();
        this.origin = selection.origin();
        this.homeArea = new PlayerNpcHomeUtil.HomeArea(this.origin, this.selectedLayout.width(), this.selectedLayout.depth());
        PlayerNpcHomeUtil.setHome(this.playerNpc, this.homeArea, this.selectedLayout.id());
        if (TerraformBuildSiteGoal.hasActionablePrepWork(this.playerNpc, serverLevel)) {
            return false;
        }
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
        this.blueprint.addAll(this.selectedLayout.blocks());
        this.blueprint.sort(Comparator
                .comparingInt(PlayerNpcBuildLayout.RelativeBlock::y)
                .thenComparingInt(block -> block.isSecondHalfOfSingleItemBlock() ? 1 : 0)
                .thenComparingInt(PlayerNpcBuildLayout.RelativeBlock::x)
                .thenComparingInt(PlayerNpcBuildLayout.RelativeBlock::z));
        this.placeDelay = 0;
        this.completedPlacements = 0;
        this.totalPlacements = countRequiredPlacements(this.blueprint);
        this.ranOutOfMaterials = false;
        this.previousMainHand = ItemStack.EMPTY;
        this.showingPlacementItem = false;
        this.waitingForPlacementClearance = false;
        this.placedBlockThisTick = false;
        this.placedBlockStateThisTick = null;
        this.placedBlockSoundPos = null;
        this.missingMaterial = "";
        this.playerNpc.setCurrentAiState("ai.player_npc.building_house");
        this.updateTaskDetail("starting");
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.origin == null || this.blueprint.isEmpty()) {
            return;
        }

        PlayerNpcBuildLayout.RelativeBlock block = this.nextUnfinishedBlock(serverLevel);
        if (block == null) {
            this.blueprint.clear();
            return;
        }

        BlockPos target = block.toWorld(this.origin);
        if (this.tryPlaceBuildSiteCraftingTable(serverLevel)
                || this.tryCraftMaterialAtBuildSiteCraftingTable(serverLevel, block)) {
            return;
        }

        this.previewPlacementItem(block);
        this.playerNpc.getLookControl().setLookAt(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.playerNpc.distanceToSqr(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D) > BUILD_DISTANCE_SQR) {
            this.playerNpc.getNavigation().moveTo(target.getX() + 0.5D, target.getY(), target.getZ() + 0.5D, 1.0D);
            this.updateTaskDetail("walking to", block);
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.placeDelay++ < 8) {
            this.updateTaskDetail("placing", block);
            return;
        }
        this.placeDelay = 0;

        if (!this.placeExactBlock(serverLevel, block)) {
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
        this.blueprint.remove(block);
        if (countsTowardBuildProgress(block)) {
            this.completedPlacements++;
        }
        this.updateTaskDetail("placed", block);
    }

    @Override
    public void stop() {
        this.restorePreviousMainHand();
        if (!this.playerNpc.level().isClientSide) {
            int cooldown = this.ranOutOfMaterials
                    ? MATERIAL_RETRY_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 20)
                    : COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 60);
            this.playerNpc.setBuildHouseCooldown(cooldown);
            this.playerNpc.setManageHomeCooldown(0);
        }
        this.blueprint.clear();
        this.homeArea = null;
        this.selectedLayout = null;
        this.origin = null;
        this.placeDelay = 0;
        this.completedPlacements = 0;
        this.totalPlacements = 0;
        this.ranOutOfMaterials = false;
        this.waitingForPlacementClearance = false;
        this.placedBlockThisTick = false;
        this.placedBlockStateThisTick = null;
        this.placedBlockSoundPos = null;
        this.missingMaterial = "";
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private BuildSelection findBuildSelection(ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> existingHome = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (existingHome.isPresent()) {
            PlayerNpcHomeUtil.HomeArea homeArea = existingHome.get();
            Optional<PlayerNpcBuildLayout> homeLayout = PlayerNpcHomeUtil.getHomeLayoutId(this.playerNpc)
                    .flatMap(layoutId -> PlayerNpcBuildLayoutLoader.getLayouts().stream()
                            .filter(layout -> layout.id().equals(layoutId))
                            .findFirst());
            if (homeLayout.isPresent()
                    && homeLayout.get().width() == homeArea.width()
                    && homeLayout.get().depth() == homeArea.depth()
                    && this.canBuildAt(serverLevel, homeLayout.get(), homeArea.origin(), true)
                    && this.hasUnfinishedPlacement(serverLevel, homeLayout.get(), homeArea.origin())) {
                return new BuildSelection(homeLayout.get(), homeArea.origin());
            }
            return null;
        }

        if (!this.hasReadyFirstBuildReserves()) {
            return null;
        }

        int availableBlocks = countAvailableBuildingBlocks(this.playerNpc);
        if (availableBlocks < MIN_BASE_BUILD_BLOCKS) {
            return null;
        }

        List<PlayerNpcBuildLayout> layouts = PlayerNpcBuildLayoutLoader.getLayouts();
        if (layouts.isEmpty()) {
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

    private boolean canBuildAt(ServerLevel serverLevel, PlayerNpcBuildLayout layout, BlockPos origin, boolean allowExistingHouseBlocks) {
        int terrainClears = 0;
        int maxTerrainClears = Math.max(MAX_TERRAIN_CLEARS_PER_BUILD_SITE, layout.width() * layout.height() * layout.depth() / 3);
        for (long packedFootprint : layout.footprint()) {
            int x = (int) (packedFootprint >> 32);
            int z = (int) packedFootprint;
            BlockPos floor = origin.offset(x, 0, z);
            if (!serverLevel.getBlockState(floor.below()).isSolidRender(serverLevel, floor.below())
                    && !serverLevel.getBlockState(floor.below(2)).isSolidRender(serverLevel, floor.below(2))) {
                return false;
            }
        }

        for (PlayerNpcBuildLayout.RelativeBlock block : layout.blocks()) {
            BlockPos checkPos = block.toWorld(origin);
            if (!serverLevel.isInWorldBounds(checkPos)
                    || !serverLevel.getWorldBorder().isWithinBounds(checkPos)) {
                return false;
            }
            if (allowExistingHouseBlocks && this.isBuiltMatch(serverLevel, checkPos, block.state())) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(checkPos);
            if (PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, checkPos)) {
                continue;
            }
            if (!this.canClearForBuild(serverLevel, checkPos, state)
                    || ++terrainClears > maxTerrainClears) {
                return false;
            }
        }
        return true;
    }

    private boolean hasUnfinishedPlacement(ServerLevel serverLevel, PlayerNpcBuildLayout layout, BlockPos origin) {
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.blocks()) {
            if (!block.optional() && !this.isBuiltMatch(serverLevel, block.toWorld(origin), block.state())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMaterialForNextPlacement(ServerLevel serverLevel, PlayerNpcBuildLayout layout, BlockPos origin) {
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.blocks()) {
            if (this.isBuiltMatch(serverLevel, block.toWorld(origin), block.state())) {
                continue;
            }
            return PlayerNpcBuildMaterialUtil.hasMaterialFor(serverLevel, this.playerNpc, block, origin);
        }
        return false;
    }

    private boolean hasReadyFirstBuildReserves() {
        return this.countRawLogs() >= this.playerNpc.getRawLogReserveTarget()
                && this.countCobblestone() >= this.playerNpc.getCobblestoneSupplyTarget();
    }

    private int countRawLogs() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(ItemTags.LOGS));
    }

    private int countCobblestone() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    private boolean tryPlaceBuildSiteCraftingTable(ServerLevel serverLevel) {
        if (this.findBuildSiteCraftingTable(serverLevel) != null) {
            return false;
        }

        BlockPos placement = this.findBuildSiteCraftingTablePlacement(serverLevel);
        if (placement == null) {
            return false;
        }

        ItemStack table = this.playerNpc.consumeInventoryItem(Items.CRAFTING_TABLE, 1).orElse(ItemStack.EMPTY);
        if (table.isEmpty()
                && !PlayerNpcCraftingUtil.tryCraftWithLogConversion(serverLevel, this.playerNpc.getInventory(), Items.CRAFTING_TABLE, false, 0)) {
            return false;
        }
        if (table.isEmpty()) {
            table = this.playerNpc.consumeInventoryItem(Items.CRAFTING_TABLE, 1).orElse(ItemStack.EMPTY);
        }
        if (table.isEmpty()) {
            return false;
        }

        this.showPlacementItem(table);
        if (!serverLevel.setBlockAndUpdate(placement, Blocks.CRAFTING_TABLE.defaultBlockState())) {
            this.returnStack(table);
            return false;
        }
        this.playerNpc.getLookControl().setLookAt(placement.getX() + 0.5D, placement.getY() + 0.5D, placement.getZ() + 0.5D, 40.0F, 40.0F);
        this.playerNpc.triggerMainHandUseAnimation();
        PlayerNpcBlockSoundUtil.playPlaceSound(serverLevel, placement, Blocks.CRAFTING_TABLE.defaultBlockState(), this.playerNpc);
        this.updateTaskDetail("placed build crafting table", Blocks.CRAFTING_TABLE.defaultBlockState(), placement);
        return true;
    }

    private boolean tryCraftMaterialAtBuildSiteCraftingTable(ServerLevel serverLevel, PlayerNpcBuildLayout.RelativeBlock block) {
        if (!PlayerNpcBuildMaterialUtil.needsCraftingForPlacement(serverLevel, this.playerNpc, block)) {
            return false;
        }

        BlockPos tablePos = this.findBuildSiteCraftingTable(serverLevel);
        if (tablePos == null) {
            return false;
        }

        this.restorePreviousMainHand();
        this.playerNpc.getLookControl().setLookAt(tablePos.getX() + 0.5D, tablePos.getY() + 0.5D, tablePos.getZ() + 0.5D, 40.0F, 40.0F);
        if (this.playerNpc.distanceToSqr(tablePos.getX() + 0.5D, tablePos.getY(), tablePos.getZ() + 0.5D) > BUILD_DISTANCE_SQR) {
            this.playerNpc.getNavigation().moveTo(tablePos.getX() + 0.5D, tablePos.getY(), tablePos.getZ() + 0.5D, 1.0D);
            this.updateTaskDetail("walking to craft", block);
            return true;
        }

        this.playerNpc.getNavigation().stop();
        if (this.placeDelay++ < 8) {
            this.updateTaskDetail("crafting", block);
            return true;
        }
        this.placeDelay = 0;

        if (!PlayerNpcBuildMaterialUtil.craftMaterialFor(serverLevel, this.playerNpc, block)) {
            return false;
        }

        this.playerNpc.triggerMainHandUseAnimation();
        this.playerNpc.level().playSound(null, tablePos, net.minecraft.sounds.SoundEvents.WOOD_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 0.45F, 1.0F);
        this.updateTaskDetail("crafted", block);
        return true;
    }

    private BlockPos findBuildSiteCraftingTable(ServerLevel serverLevel) {
        if (this.origin == null || this.selectedLayout == null) {
            return null;
        }

        for (BlockPos pos : BlockPos.betweenClosed(
                this.origin.offset(-2, 0, -2),
                this.origin.offset(this.selectedLayout.width() + 1, 3, this.selectedLayout.depth() + 1))) {
            if (serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findBuildSiteCraftingTablePlacement(ServerLevel serverLevel) {
        if (this.origin == null || this.selectedLayout == null) {
            return null;
        }

        List<BlockPos> candidates = new ArrayList<>();
        for (int x = -1; x <= this.selectedLayout.width(); x++) {
            candidates.add(this.origin.offset(x, 0, -1));
            candidates.add(this.origin.offset(x, 0, this.selectedLayout.depth()));
        }
        for (int z = 0; z < this.selectedLayout.depth(); z++) {
            candidates.add(this.origin.offset(-1, 0, z));
            candidates.add(this.origin.offset(this.selectedLayout.width(), 0, z));
        }

        candidates.sort(Comparator.comparingDouble(this.playerNpc.blockPosition()::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canPlaceBuildSiteUtilityAt(serverLevel, immutable)) {
                return immutable;
            }
        }
        return null;
    }

    private boolean canPlaceBuildSiteUtilityAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && !this.isInsideSelectedBuildFootprint(pos)
                && serverLevel.getBlockState(pos).canBeReplaced()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getBlockEntity(pos) == null
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private boolean isInsideSelectedBuildFootprint(BlockPos pos) {
        if (this.origin == null || this.selectedLayout == null) {
            return false;
        }

        return pos.getX() >= this.origin.getX()
                && pos.getX() < this.origin.getX() + this.selectedLayout.width()
                && pos.getZ() >= this.origin.getZ()
                && pos.getZ() < this.origin.getZ() + this.selectedLayout.depth();
    }

    private PlayerNpcBuildLayout.RelativeBlock nextUnfinishedBlock(ServerLevel serverLevel) {
        while (!this.blueprint.isEmpty()) {
            PlayerNpcBuildLayout.RelativeBlock block = this.blueprint.get(0);
            if (this.isBuiltMatch(serverLevel, block.toWorld(this.origin), block.state())) {
                this.blueprint.remove(0);
                if (countsTowardBuildProgress(block)) {
                    this.completedPlacements++;
                }
                continue;
            }
            if (block.optional() && !PlayerNpcBuildMaterialUtil.hasMaterialFor(serverLevel, this.playerNpc, block, this.origin)) {
                this.blueprint.remove(0);
                continue;
            }
            return block;
        }
        return null;
    }

    private boolean placeExactBlock(ServerLevel serverLevel, PlayerNpcBuildLayout.RelativeBlock block) {
        this.waitingForPlacementClearance = false;
        this.placedBlockThisTick = false;
        this.placedBlockStateThisTick = null;
        this.placedBlockSoundPos = null;
        BlockPos pos = block.toWorld(this.origin);
        BlockState targetState = block.state();

        if (this.isBuiltMatch(serverLevel, pos, targetState)) {
            return true;
        }

        BlockState existing = serverLevel.getBlockState(pos);
        if (!PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)) {
            if (!this.canClearForBuild(serverLevel, pos, existing)) {
                return false;
            }
            this.updateTaskDetail("clearing", existing, pos);
            serverLevel.destroyBlock(pos, true, this.playerNpc);
            this.playerNpc.triggerMainHandUseAnimation();
            this.waitingForPlacementClearance = true;
            this.placeDelay = 0;
            return false;
        }

        if (targetState.isAir()) {
            return true;
        }

        Optional<PlayerNpcBuildMaterialUtil.PlacementMaterial> placementMaterial = PlayerNpcBuildMaterialUtil.resolvePlacement(serverLevel, this.playerNpc, block, this.origin);
        if (placementMaterial.isEmpty()) {
            if (block.optional()) {
                return true;
            }
            this.ranOutOfMaterials = true;
            this.missingMaterial = PlayerNpcBuildMaterialUtil.describeTarget(targetState);
            this.updateTaskDetail("missing", targetState, pos);
            return false;
        }

        PlayerNpcBuildMaterialUtil.PlacementMaterial material = placementMaterial.get();
        BlockState placementState = material.state();
        Optional<PairedPlacement> pairedPlacement = this.pairedPlacementFor(pos, targetState, placementState);
        ItemStack consumed = material.consumedItem();
        if (pairedPlacement.isPresent() && !this.preparePairedPlacementTarget(serverLevel, pairedPlacement.get())) {
            this.returnPlacementMaterial(material);
            return false;
        }

        if (!this.canPlaceWithoutClipping(serverLevel, pos, placementState)) {
            this.returnPlacementMaterial(material);
            this.waitForPlacementClearance(pos);
            return false;
        }
        if (pairedPlacement.isPresent() && !this.canPlaceWithoutClipping(serverLevel, pairedPlacement.get().pos(), pairedPlacement.get().state())) {
            this.returnPlacementMaterial(material);
            this.waitForPlacementClearance(pairedPlacement.get().pos());
            return false;
        }

        this.showPlacementItem(consumed);
        if (!serverLevel.setBlockAndUpdate(pos, placementState)) {
            this.returnPlacementMaterial(material);
            return false;
        }
        pairedPlacement.ifPresent(pair -> serverLevel.setBlockAndUpdate(pair.pos(), pair.state()));
        this.applyBlockEntityData(serverLevel, pos, block.blockEntityTag());
        this.markPlacedBlock(pos, placementState);
        return true;
    }

    private Optional<PairedPlacement> pairedPlacementFor(BlockPos pos, BlockState targetState, BlockState placementState) {
        if (targetState.getBlock() instanceof DoorBlock
                && placementState.getBlock() instanceof DoorBlock
                && targetState.hasProperty(DoorBlock.HALF)
                && placementState.hasProperty(DoorBlock.HALF)
                && targetState.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
            return Optional.of(new PairedPlacement(pos.above(), placementState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)));
        }

        if (targetState.getBlock() instanceof DoublePlantBlock
                && placementState.getBlock() instanceof DoublePlantBlock
                && targetState.hasProperty(DoublePlantBlock.HALF)
                && placementState.hasProperty(DoublePlantBlock.HALF)
                && targetState.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) {
            return Optional.of(new PairedPlacement(pos.above(), placementState.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER)));
        }

        if (targetState.getBlock() instanceof BedBlock
                && placementState.getBlock() instanceof BedBlock
                && targetState.hasProperty(BedBlock.PART)
                && targetState.hasProperty(BedBlock.FACING)
                && placementState.hasProperty(BedBlock.PART)
                && placementState.hasProperty(BedBlock.FACING)
                && targetState.getValue(BedBlock.PART) == BedPart.FOOT) {
            Direction facing = placementState.getValue(BedBlock.FACING);
            return Optional.of(new PairedPlacement(pos.relative(facing), placementState.setValue(BedBlock.PART, BedPart.HEAD)));
        }

        return Optional.empty();
    }

    private boolean preparePairedPlacementTarget(ServerLevel serverLevel, PairedPlacement pairedPlacement) {
        BlockPos pos = pairedPlacement.pos();
        BlockState existing = serverLevel.getBlockState(pos);
        if (PlayerNpcBuildMaterialUtil.matches(existing, pairedPlacement.state())
                || PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)) {
            return true;
        }

        if (!this.canClearForBuild(serverLevel, pos, existing)) {
            return false;
        }

        this.updateTaskDetail("clearing", existing, pos);
        serverLevel.destroyBlock(pos, true, this.playerNpc);
        this.playerNpc.triggerMainHandUseAnimation();
        this.waitingForPlacementClearance = true;
        this.placeDelay = 0;
        return false;
    }

    private void applyBlockEntityData(ServerLevel serverLevel, BlockPos pos, CompoundTag blockEntityTag) {
        if (blockEntityTag == null || blockEntityTag.isEmpty()) {
            return;
        }

        BlockEntity blockEntity = serverLevel.getBlockEntity(pos);
        if (blockEntity == null) {
            return;
        }

        CompoundTag tag = blockEntityTag.copy();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        blockEntity.load(tag);
        blockEntity.setChanged();
        serverLevel.sendBlockUpdated(pos, serverLevel.getBlockState(pos), serverLevel.getBlockState(pos), 3);
    }

    private boolean isBuiltMatch(ServerLevel serverLevel, BlockPos pos, BlockState targetState) {
        return PlayerNpcBuildMaterialUtil.matches(serverLevel.getBlockState(pos), targetState);
    }

    private void markPlacedBlock(BlockPos pos, BlockState state) {
        this.placedBlockThisTick = true;
        this.placedBlockStateThisTick = state;
        this.placedBlockSoundPos = pos.immutable();
    }

    private void previewPlacementItem(PlayerNpcBuildLayout.RelativeBlock block) {
        if (this.playerNpc.level() instanceof ServerLevel serverLevel && this.origin != null) {
            this.showPlacementItem(PlayerNpcBuildMaterialUtil.previewItem(serverLevel, this.playerNpc, block));
            return;
        }
        this.showPlacementItem(block.requiredItem());
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

    private boolean canClearForBuild(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return !state.isAir()
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && state.getFluidState().isEmpty()
                && serverLevel.getBlockEntity(pos) == null
                && (state.canBeReplaced()
                || state.is(BlockTags.MINEABLE_WITH_SHOVEL)
                || state.is(BlockTags.MINEABLE_WITH_AXE)
                || state.is(BlockTags.MINEABLE_WITH_PICKAXE)
                || state.is(BlockTags.LEAVES));
    }

    private boolean canPlaceWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        return state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .noneMatch(box -> box.move(pos).intersects(this.playerNpc.getBoundingBox().inflate(0.05D)));
    }

    private void waitForPlacementClearance(BlockPos pos) {
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

    private void returnStack(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private void returnPlacementMaterial(PlayerNpcBuildMaterialUtil.PlacementMaterial material) {
        this.returnStack(material.consumedItem());
        for (ItemStack stack : material.extraConsumedItems()) {
            this.returnStack(stack);
        }
    }

    private void updateTaskDetail(String stage) {
        this.updateTaskDetail(stage, null, null);
    }

    private void updateTaskDetail(String stage, PlayerNpcBuildLayout.RelativeBlock block) {
        if (block == null || this.origin == null) {
            this.updateTaskDetail(stage, null, null);
            return;
        }

        this.updateTaskDetail(stage, block.state(), block.toWorld(this.origin));
    }

    private void updateTaskDetail(String stage, BlockState state, BlockPos pos) {
        if (this.selectedLayout == null) {
            return;
        }

        String progress = this.completedPlacements + "/" + this.totalPlacements;
        StringBuilder detail = new StringBuilder("Build: ").append(this.selectedLayout.name());
        if (state == null || state.isAir() || pos == null) {
            detail.append('\n')
                    .append(formatTaskStage(stage))
                    .append('\n')
                    .append("Progress: ")
                    .append(progress);
        } else if ("missing".equals(stage)) {
            detail.append('\n')
                    .append("Missing: ")
                    .append(this.missingMaterial.isBlank() ? describeTaskState(state) : this.missingMaterial)
                    .append('\n')
                    .append(describeTaskState(state))
                    .append(" @ ")
                    .append(formatTaskPos(pos))
                    .append(' ')
                    .append(progress);
        } else {
            detail.append('\n')
                    .append(formatTaskStage(stage))
                    .append(": ")
                    .append(describeTaskState(state))
                    .append('\n')
                    .append("@ ")
                    .append(formatTaskPos(pos))
                    .append(' ')
                    .append(progress);
        }

        this.playerNpc.setCurrentAiDetail(detail.toString());
    }

    private static String formatTaskStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return "Working";
        }

        String trimmed = stage.trim();
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    private static String describeTaskState(BlockState state) {
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockId == null) {
            return state.getBlock().getDescriptionId();
        }
        return "minecraft".equals(blockId.getNamespace()) ? blockId.getPath() : blockId.toString();
    }

    private static String formatTaskPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static int countRequiredPlacements(List<PlayerNpcBuildLayout.RelativeBlock> blocks) {
        int count = 0;
        for (PlayerNpcBuildLayout.RelativeBlock block : blocks) {
            if (countsTowardBuildProgress(block)) {
                count++;
            }
        }
        return count;
    }

    private static boolean countsTowardBuildProgress(PlayerNpcBuildLayout.RelativeBlock block) {
        return block != null && !block.optional() && !block.state().isAir();
    }



    private static boolean isBuildingBlockItem(ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || stack.is(ItemTags.LOGS)
                || stack.is(ItemTags.SAPLINGS)
                || stack.is(Items.TORCH)
                || stack.getItem() instanceof BedItem) {
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        return !state.is(Blocks.AIR)
                && !state.canBeReplaced()
                && state.getFluidState().isEmpty();
    }

    private record PairedPlacement(BlockPos pos, BlockState state) {}

    private record BuildSelection(PlayerNpcBuildLayout layout, BlockPos origin) {}
}
