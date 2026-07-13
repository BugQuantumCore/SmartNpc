package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBuildLayout;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class FarmCropGoal extends Goal {
    private static final int DEFAULT_COOLDOWN_TICKS = 20 * 20;
    private static final int QUICK_COOLDOWN_TICKS = 5;
    private static final int HARVEST_RADIUS = 12;
    private static final double FARM_DISTANCE_SQR = 3.5D * 3.5D;
    private static final int HARVEST_TICKS = 12;
    private static final int MAX_ACTION_TICKS = 20 * 18;
    private static final int[][] FARM_SIZES = {
            {4, 4},
            {5, 5},
            {6, 6},
            {4, 5},
            {5, 6}
    };

    private static final String FARM_X = "PlayerNpcFarmX";
    private static final String FARM_Y = "PlayerNpcFarmY";
    private static final String FARM_Z = "PlayerNpcFarmZ";
    private static final String FARM_WIDTH = "PlayerNpcFarmWidth";
    private static final String FARM_DEPTH = "PlayerNpcFarmDepth";

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private FarmArea farmArea;
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private BlockPos targetPos;
    private BlockPos standPos;
    private PlantingCrop plantingCrop;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private Action action = Action.NONE;
    private Action completedAction = Action.NONE;
    private int harvestTicks;
    private int actionTicks;
    private boolean showingActionItem;

    public FarmCropGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
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
                || this.playerNpc.getFarmCooldown() > 0) {
            return false;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)) {
            if (home.isEmpty() || !this.isHouseFinished(serverLevel, home.get())) {
                return false;
            }
            this.homeArea = home.get();
        } else {
            this.homeArea = home.orElseGet(() -> PlayerNpcHomeUtil.getOrCreateHome(this.playerNpc, serverLevel));
        }
        this.farmArea = this.getOrCreateFarmArea(serverLevel, this.homeArea).orElse(null);
        if (this.farmArea == null) {
            return false;
        }

        return this.selectAction(serverLevel);
    }

    @Override
    public boolean canContinueToUse() {
        return this.action != Action.NONE
                && (this.targetPos != null || this.action == Action.CRAFT_BONE_MEAL)
                && this.actionTicks < MAX_ACTION_TICKS
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null;
    }

    @Override
    public void start() {
        this.playerNpc.setCurrentAiState("ai.player_npc.farming");
        this.playerNpc.setCurrentAiDetail(this.describeAction());
        this.harvestTicks = 0;
        this.actionTicks = 0;
        this.completedAction = Action.NONE;
        if (this.action != Action.CRAFT_BONE_MEAL) {
            this.moveToTarget();
        }
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        this.actionTicks++;
        if (this.action == Action.CRAFT_BONE_MEAL) {
            this.finishAction(this.craftBoneMeal(serverLevel));
            return;
        }
        if (this.targetPos == null) {
            return;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        if (this.distanceToTargetSqr() > FARM_DISTANCE_SQR) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            if (this.playerNpc.getNavigation().isDone() || this.playerNpc.getNavigation().isStuck()) {
                this.moveToTarget();
            }
            return;
        }

        this.playerNpc.getNavigation().stop();
        switch (this.action) {
            case PLACE_FENCE -> this.finishAction(this.placeFence(serverLevel));
            case PLACE_GATE -> this.finishAction(this.placeGate(serverLevel));
            case HARVEST -> this.tickHarvest(serverLevel);
            case BONE_MEAL -> this.finishAction(this.useBoneMeal(serverLevel));
            case PLANT -> this.finishAction(this.plantCrop(serverLevel));
            default -> this.finishAction(false);
        }
    }

    @Override
    public void stop() {
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.restoreActionItem();
        if (!this.playerNpc.level().isClientSide) {
            int cooldown = switch (this.completedAction) {
                case PLACE_FENCE, PLACE_GATE, PLANT, BONE_MEAL, CRAFT_BONE_MEAL -> QUICK_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(8);
                case HARVEST -> 10 + this.playerNpc.getRandom().nextInt(20);
                default -> DEFAULT_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 20);
            };
            this.playerNpc.setFarmCooldown(cooldown);
        }
        this.farmArea = null;
        this.homeArea = null;
        this.targetPos = null;
        this.standPos = null;
        this.plantingCrop = null;
        this.action = Action.NONE;
        this.completedAction = Action.NONE;
        this.harvestTicks = 0;
        this.actionTicks = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private boolean selectAction(ServerLevel serverLevel) {
        this.targetPos = this.findMissingFence(serverLevel);
        if (this.targetPos != null) {
            if (!this.canProvideFence()) {
                return false;
            }
            this.action = Action.PLACE_FENCE;
            this.standPos = this.findStandNear(serverLevel, this.targetPos);
            return this.standPos != null;
        }

        this.targetPos = this.findMissingGate(serverLevel);
        if (this.targetPos != null) {
            if (!this.canProvideGate()) {
                return false;
            }
            this.action = Action.PLACE_GATE;
            this.standPos = this.findStandNear(serverLevel, this.targetPos);
            return this.standPos != null;
        }

        if (PlayerNpcCraftingUtil.canCraftBoneMeal(this.playerNpc.getInventory())) {
            this.action = Action.CRAFT_BONE_MEAL;
            this.targetPos = null;
            this.standPos = null;
            return true;
        }

        this.targetPos = this.findMatureCrop(serverLevel);
        if (this.targetPos != null) {
            this.action = Action.HARVEST;
            this.standPos = this.findStandNear(serverLevel, this.targetPos);
            return this.standPos != null;
        }

        this.targetPos = this.findBonemealableCrop(serverLevel);
        if (this.targetPos != null && InventoryUtils.hasItem(this.playerNpc, Items.BONE_MEAL)) {
            this.action = Action.BONE_MEAL;
            this.standPos = this.findStandNear(serverLevel, this.targetPos);
            return this.standPos != null;
        }

        this.plantingCrop = this.findPlantingCrop();
        this.targetPos = this.plantingCrop == null ? null : this.findFarmPlantingPos(serverLevel);
        if (this.targetPos != null) {
            this.action = Action.PLANT;
            this.standPos = this.findStandNear(serverLevel, this.targetPos);
            return this.standPos != null;
        }

        this.action = Action.NONE;
        this.targetPos = null;
        this.standPos = null;
        return false;
    }

    private void finishAction(boolean success) {
        if (success) {
            this.completedAction = this.action;
        }
        this.targetPos = null;
        this.standPos = null;
        this.action = Action.NONE;
        this.harvestTicks = 0;
    }

    private void tickHarvest(ServerLevel serverLevel) {
        BlockState state = serverLevel.getBlockState(this.targetPos);
        if (!(state.getBlock() instanceof CropBlock cropBlock) || !cropBlock.isMaxAge(state)) {
            this.playerNpc.clearBlockBreakProgress(this.targetPos);
            this.finishAction(false);
            return;
        }

        this.harvestTicks++;
        this.playerNpc.showBlockBreakProgress(this.targetPos, this.harvestTicks, HARVEST_TICKS);
        if (this.harvestTicks % 4 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
        }
        if (this.harvestTicks < HARVEST_TICKS) {
            return;
        }

        serverLevel.destroyBlock(this.targetPos, true, this.playerNpc);
        this.playerNpc.clearBlockBreakProgress(this.targetPos);
        this.playerNpc.triggerMainHandAttackAnimation();
        serverLevel.playSound(null, this.targetPos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 0.7F, 1.0F);
        this.finishAction(true);
    }

    private boolean placeFence(ServerLevel serverLevel) {
        if (this.targetPos == null
                || serverLevel.getBlockState(this.targetPos).getBlock() instanceof FenceBlock
                || !PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, this.targetPos)
                || !serverLevel.getBlockState(this.targetPos.below()).isSolidRender(serverLevel, this.targetPos.below())) {
            return false;
        }

        ItemStack fence = this.consumeFence();
        if (fence.isEmpty() || !(fence.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof FenceBlock)) {
            this.returnStack(fence);
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        if (!state.canSurvive(serverLevel, this.targetPos)) {
            this.returnStack(fence);
            return false;
        }

        this.showActionItem(fence);
        if (!this.placingBlockAi.placeBlock(serverLevel, this.targetPos, state)) {
            this.returnStack(fence);
            return false;
        }
        this.finishPlacementActionItem();
        return true;
    }

    private boolean placeGate(ServerLevel serverLevel) {
        if (this.targetPos == null
                || serverLevel.getBlockState(this.targetPos).getBlock() instanceof FenceGateBlock
                || !PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, this.targetPos)
                || !serverLevel.getBlockState(this.targetPos.below()).isSolidRender(serverLevel, this.targetPos.below())) {
            return false;
        }

        ItemStack gate = this.consumeGate();
        if (gate.isEmpty() || !(gate.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof FenceGateBlock gateBlock)) {
            this.returnStack(gate);
            return false;
        }

        BlockState state = gateBlock.defaultBlockState()
                .setValue(FenceGateBlock.FACING, this.playerNpc.getDirection())
                .setValue(FenceGateBlock.OPEN, true);
        if (!state.canSurvive(serverLevel, this.targetPos)) {
            this.returnStack(gate);
            return false;
        }

        this.showActionItem(gate);
        if (!this.placingBlockAi.placeBlock(serverLevel, this.targetPos, state)) {
            this.returnStack(gate);
            return false;
        }
        this.finishPlacementActionItem();
        return true;
    }

    private boolean plantCrop(ServerLevel serverLevel) {
        if (this.targetPos == null || this.plantingCrop == null) {
            return false;
        }

        BlockPos groundPos = this.targetPos.below();
        BlockState groundState = serverLevel.getBlockState(groundPos);
        boolean mustTill = !groundState.is(Blocks.FARMLAND);
        if (mustTill && (!this.hasHoe() || !this.isTillableGround(groundState))) {
            return false;
        }
        if (!serverLevel.getBlockState(this.targetPos).isAir()) {
            return false;
        }

        if (mustTill) {
            serverLevel.setBlockAndUpdate(groundPos, Blocks.FARMLAND.defaultBlockState());
            this.playerNpc.hurtHeldOrInventoryItem(stack -> stack.getItem() instanceof HoeItem, 1);
        }
        if (!this.plantingCrop.state().canSurvive(serverLevel, this.targetPos)) {
            return false;
        }

        ItemStack seed = this.playerNpc.consumeInventoryItem(this.plantingCrop.item(), 1).orElse(ItemStack.EMPTY);
        if (seed.isEmpty()) {
            return false;
        }

        this.showActionItem(seed);
        serverLevel.setBlockAndUpdate(this.targetPos, this.plantingCrop.state());
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(null, groundPos, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 0.8F, 1.0F);
        return true;
    }

    private boolean craftBoneMeal(ServerLevel serverLevel) {
        boolean crafted = PlayerNpcCraftingUtil.tryCraftBoneMeal(serverLevel, this.playerNpc.getInventory());
        if (crafted) {
            this.showActionItem(new ItemStack(Items.BONE_MEAL));
            this.playerNpc.triggerMainHandUseAnimation();
            serverLevel.playSound(null, this.playerNpc.blockPosition(), SoundEvents.BONE_BLOCK_PLACE, SoundSource.PLAYERS, 0.35F, 1.35F);
        }
        return crafted;
    }

    private boolean useBoneMeal(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(this.targetPos);
        if (!(state.getBlock() instanceof BonemealableBlock bonemealableBlock)
                || !bonemealableBlock.isValidBonemealTarget(serverLevel, this.targetPos, state, false)
                || !bonemealableBlock.isBonemealSuccess(serverLevel, this.playerNpc.getRandom(), this.targetPos, state)) {
            return false;
        }

        ItemStack boneMeal = this.playerNpc.consumeInventoryItem(Items.BONE_MEAL, 1).orElse(ItemStack.EMPTY);
        if (boneMeal.isEmpty()) {
            return false;
        }

        this.showActionItem(boneMeal);
        bonemealableBlock.performBonemeal(serverLevel, this.playerNpc.getRandom(), this.targetPos, state);
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.levelEvent(1505, this.targetPos, 0);
        return true;
    }

    private void moveToTarget() {
        if (this.standPos == null) {
            return;
        }

        Path path = this.playerNpc.getNavigation().createPath(this.standPos, 0);
        if (path != null && path.canReach()) {
            this.playerNpc.getNavigation().moveTo(path, 1.0D);
            return;
        }
        this.playerNpc.getNavigation().moveTo(this.standPos.getX() + 0.5D, this.standPos.getY(), this.standPos.getZ() + 0.5D, 1.0D);
    }

    private BlockPos findMatureCrop(ServerLevel serverLevel) {
        for (BlockPos cropPos : this.farmCropPositions()) {
            BlockState state = serverLevel.getBlockState(cropPos);
            if (state.getBlock() instanceof CropBlock cropBlock && cropBlock.isMaxAge(state)) {
                return cropPos.immutable();
            }
        }

        BlockPos center = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-HARVEST_RADIUS, -2, -HARVEST_RADIUS), center.offset(HARVEST_RADIUS, 2, HARVEST_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (this.farmArea != null && !this.farmArea.containsCrop(immutable)) {
                continue;
            }
            BlockState state = serverLevel.getBlockState(immutable);
            if (state.getBlock() instanceof CropBlock cropBlock && cropBlock.isMaxAge(state)) {
                return immutable;
            }
        }
        return null;
    }

    private BlockPos findBonemealableCrop(ServerLevel serverLevel) {
        for (BlockPos cropPos : this.farmCropPositions()) {
            BlockState state = serverLevel.getBlockState(cropPos);
            if (state.getBlock() instanceof CropBlock cropBlock
                    && !cropBlock.isMaxAge(state)
                    && state.getBlock() instanceof BonemealableBlock bonemealableBlock
                    && bonemealableBlock.isValidBonemealTarget(serverLevel, cropPos, state, false)) {
                return cropPos.immutable();
            }
        }
        return null;
    }

    private BlockPos findFarmPlantingPos(ServerLevel serverLevel) {
        if (this.farmArea == null || this.plantingCrop == null) {
            return null;
        }

        for (int x = 0; x < this.farmArea.width(); x++) {
            for (int z = 0; z < this.farmArea.depth(); z++) {
                BlockPos groundPos = this.farmArea.origin().offset(x, 0, z);
                BlockPos cropPos = groundPos.above();
                BlockState groundState = serverLevel.getBlockState(groundPos);
                if ((groundState.is(Blocks.FARMLAND) || this.hasHoe() && this.isTillableGround(groundState))
                        && serverLevel.getBlockState(cropPos).isAir()) {
                    return cropPos.immutable();
                }
            }
        }
        return null;
    }

    private BlockPos findMissingFence(ServerLevel serverLevel) {
        if (this.farmArea == null) {
            return null;
        }

        for (BlockPos fencePos : this.farmFencePositions()) {
            BlockState state = serverLevel.getBlockState(fencePos);
            if (state.getBlock() instanceof FenceBlock) {
                continue;
            }
            if (PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, fencePos)
                    && serverLevel.getBlockState(fencePos.below()).isSolidRender(serverLevel, fencePos.below())) {
                return fencePos.immutable();
            }
        }
        return null;
    }

    private BlockPos findMissingGate(ServerLevel serverLevel) {
        if (this.farmArea == null || this.homeArea == null) {
            return null;
        }

        BlockPos gatePos = this.farmGatePos(this.farmArea, this.homeArea);
        BlockState state = serverLevel.getBlockState(gatePos);
        if (state.getBlock() instanceof FenceGateBlock) {
            return null;
        }
        if (PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, gatePos)
                && serverLevel.getBlockState(gatePos.below()).isSolidRender(serverLevel, gatePos.below())) {
            return gatePos.immutable();
        }
        return null;
    }

    private Optional<FarmArea> getOrCreateFarmArea(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        Optional<FarmArea> existing = this.getFarmArea();
        if (existing.isPresent() && this.canUseFarmArea(serverLevel, homeArea, existing.get())) {
            return existing;
        }

        int startIndex = this.playerNpc.getRandom().nextInt(FARM_SIZES.length);
        for (int i = 0; i < FARM_SIZES.length; i++) {
            int[] size = FARM_SIZES[(startIndex + i) % FARM_SIZES.length];
            FarmArea farmArea = this.findFarmArea(serverLevel, homeArea, size[0], size[1]);
            if (farmArea != null) {
                this.setFarmArea(farmArea);
                return Optional.of(farmArea);
            }
        }
        return Optional.empty();
    }

    private FarmArea findFarmArea(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea, int width, int depth) {
        List<BlockPos> candidates = new ArrayList<>();
        int baseY = homeArea.origin().getY();
        for (int offset = -5; offset <= 5; offset++) {
            candidates.add(new BlockPos(homeArea.origin().getX() + homeArea.width() + 2, baseY, homeArea.origin().getZ() + (homeArea.depth() - depth) / 2 + offset));
            candidates.add(new BlockPos(homeArea.origin().getX() - width - 2, baseY, homeArea.origin().getZ() + (homeArea.depth() - depth) / 2 + offset));
            candidates.add(new BlockPos(homeArea.origin().getX() + (homeArea.width() - width) / 2 + offset, baseY, homeArea.origin().getZ() + homeArea.depth() + 2));
            candidates.add(new BlockPos(homeArea.origin().getX() + (homeArea.width() - width) / 2 + offset, baseY, homeArea.origin().getZ() - depth - 2));
        }

        BlockPos homeCenter = homeArea.origin().offset(homeArea.width() / 2, 0, homeArea.depth() / 2);
        candidates.sort(Comparator.comparingDouble(homeCenter::distSqr));
        for (BlockPos candidate : candidates) {
            FarmArea farmArea = new FarmArea(candidate.immutable(), width, depth);
            if (this.canUseFarmArea(serverLevel, homeArea, farmArea)) {
                return farmArea;
            }
        }
        return null;
    }

    private boolean canUseFarmArea(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea, FarmArea farmArea) {
        for (int x = 0; x < farmArea.width(); x++) {
            for (int z = 0; z < farmArea.depth(); z++) {
                BlockPos groundPos = farmArea.origin().offset(x, 0, z);
                if (PlayerNpcHomeUtil.isInside(homeArea, groundPos) || PlayerNpcHomeUtil.isInside(homeArea, groundPos.above())) {
                    return false;
                }
                BlockState groundState = serverLevel.getBlockState(groundPos);
                if (!groundState.is(Blocks.FARMLAND) && !this.isTillableGround(groundState)) {
                    return false;
                }
                BlockState cropState = serverLevel.getBlockState(groundPos.above());
                if (!cropState.isAir() && !(cropState.getBlock() instanceof CropBlock)) {
                    return false;
                }
            }
        }

        for (BlockPos fencePos : this.farmFencePositions(farmArea, homeArea)) {
            if (PlayerNpcHomeUtil.isInside(homeArea, fencePos)) {
                return false;
            }
            BlockState state = serverLevel.getBlockState(fencePos);
            if (!(state.getBlock() instanceof FenceBlock) && !PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, fencePos)) {
                return false;
            }
            if (!serverLevel.getBlockState(fencePos.below()).isSolidRender(serverLevel, fencePos.below())) {
                return false;
            }
        }

        BlockPos gatePos = this.farmGatePos(farmArea, homeArea);
        if (PlayerNpcHomeUtil.isInside(homeArea, gatePos)) {
            return false;
        }
        BlockState gateState = serverLevel.getBlockState(gatePos);
        if (!(gateState.getBlock() instanceof FenceGateBlock) && !PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, gatePos)) {
            return false;
        }
        if (!serverLevel.getBlockState(gatePos.below()).isSolidRender(serverLevel, gatePos.below())) {
            return false;
        }
        return true;
    }

    private boolean isHouseFinished(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        Optional<String> layoutId = PlayerNpcHomeUtil.getHomeLayoutId(this.playerNpc);
        if (layoutId.isEmpty()) {
            return false;
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcBuildLayoutLoader.getLayouts().stream()
                .filter(candidate -> candidate.id().equals(layoutId.get())
                        && candidate.width() == homeArea.width()
                        && candidate.depth() == homeArea.depth())
                .findFirst();
        if (layout.isEmpty()) {
            return false;
        }

        for (PlayerNpcBuildLayout.RelativeBlock block : layout.get().blocks()) {
            if (block.optional()) {
                continue;
            }
            BlockPos pos = block.toWorld(homeArea.origin());
            if (!serverLevel.getBlockState(pos).equals(block.state())) {
                return false;
            }
        }
        return true;
    }

    private PlantingCrop findPlantingCrop() {
        if (InventoryUtils.hasItem(this.playerNpc, Items.WHEAT_SEEDS)) {
            return new PlantingCrop(Items.WHEAT_SEEDS, Blocks.WHEAT.defaultBlockState());
        }
        if (InventoryUtils.hasItem(this.playerNpc, Items.CARROT)) {
            return new PlantingCrop(Items.CARROT, Blocks.CARROTS.defaultBlockState());
        }
        if (InventoryUtils.hasItem(this.playerNpc, Items.POTATO)) {
            return new PlantingCrop(Items.POTATO, Blocks.POTATOES.defaultBlockState());
        }
        if (InventoryUtils.hasItem(this.playerNpc, Items.BEETROOT_SEEDS)) {
            return new PlantingCrop(Items.BEETROOT_SEEDS, Blocks.BEETROOTS.defaultBlockState());
        }
        return null;
    }

    private boolean hasHoe() {
        return this.playerNpc.getMainHandItem().getItem() instanceof HoeItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof HoeItem);
    }

    private boolean isTillableGround(BlockState state) {
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK);
    }

    private boolean canProvideFence() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof FenceBlock)
                || PlayerNpcCraftingUtil.canCraftFences(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget());
    }

    private boolean canProvideGate() {
        return InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof FenceGateBlock)
                || PlayerNpcCraftingUtil.canCraftFenceGate(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget());
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

    private ItemStack consumeGate() {
        ItemStack gate = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof FenceGateBlock, 1).orElse(ItemStack.EMPTY);
        if (!gate.isEmpty()) {
            return gate;
        }
        if (PlayerNpcCraftingUtil.tryCraftFenceGate(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget())) {
            return this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof FenceGateBlock, 1).orElse(ItemStack.EMPTY);
        }
        return ItemStack.EMPTY;
    }

    private void showActionItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (!this.showingActionItem) {
            this.previousMainHand = this.playerNpc.getMainHandItem().copy();
            this.showingActionItem = true;
        }
        ItemStack held = stack.copy();
        held.setCount(1);
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, held);
    }

    private void restoreActionItem() {
        if (!this.showingActionItem) {
            return;
        }
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, this.previousMainHand.copy());
        this.previousMainHand = ItemStack.EMPTY;
        this.showingActionItem = false;
    }

    private void finishPlacementActionItem() {
        if (!this.showingActionItem) {
            return;
        }
        this.placingBlockAi.finishHeldPlacement(this.previousMainHand);
        this.previousMainHand = ItemStack.EMPTY;
        this.showingActionItem = false;
    }

    private void returnStack(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private BlockPos findStandNear(ServerLevel serverLevel, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(target.relative(direction));
            candidates.add(target.relative(direction).below());
            candidates.add(target.relative(direction, 2));
            candidates.add(target.relative(direction, 2).below());
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!this.canStandAt(serverLevel, immutable)) {
                continue;
            }
            if (this.playerNpc.distanceToSqr(immutable.getX() + 0.5D, immutable.getY(), immutable.getZ() + 0.5D) <= 1.5D * 1.5D) {
                return immutable;
            }
            Path path = this.playerNpc.getNavigation().createPath(immutable, 0);
            if (path != null && path.canReach()) {
                return immutable;
            }
        }
        return null;
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).getCollisionShape(serverLevel, pos).isEmpty()
                && serverLevel.getBlockState(pos.above()).getCollisionShape(serverLevel, pos.above()).isEmpty()
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getFluidState(pos.above()).isEmpty()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private double distanceToTargetSqr() {
        return this.targetPos == null
                ? 0.0D
                : this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D);
    }

    private String describeAction() {
        return switch (this.action) {
            case PLACE_FENCE -> "placing farm fence";
            case PLACE_GATE -> "placing farm gate";
            case HARVEST -> "harvesting crop";
            case BONE_MEAL -> "using bone meal";
            case CRAFT_BONE_MEAL -> "crafting bone meal";
            case PLANT -> "planting crop";
            default -> "";
        };
    }

    private List<BlockPos> farmCropPositions() {
        List<BlockPos> positions = new ArrayList<>();
        if (this.farmArea == null) {
            return positions;
        }

        for (int x = 0; x < this.farmArea.width(); x++) {
            for (int z = 0; z < this.farmArea.depth(); z++) {
                positions.add(this.farmArea.origin().offset(x, 1, z));
            }
        }
        return positions;
    }

    private List<BlockPos> farmFencePositions() {
        return this.farmArea == null || this.homeArea == null ? List.of() : this.farmFencePositions(this.farmArea, this.homeArea);
    }

    private List<BlockPos> farmFencePositions(FarmArea farmArea, PlayerNpcHomeUtil.HomeArea homeArea) {
        List<BlockPos> positions = new ArrayList<>();
        BlockPos gatePos = this.farmGatePos(farmArea, homeArea);
        for (int x = -1; x <= farmArea.width(); x++) {
            this.addFencePosition(positions, farmArea.origin().offset(x, 1, -1), gatePos);
            this.addFencePosition(positions, farmArea.origin().offset(x, 1, farmArea.depth()), gatePos);
        }
        for (int z = 0; z < farmArea.depth(); z++) {
            this.addFencePosition(positions, farmArea.origin().offset(-1, 1, z), gatePos);
            this.addFencePosition(positions, farmArea.origin().offset(farmArea.width(), 1, z), gatePos);
        }
        return positions;
    }

    private void addFencePosition(List<BlockPos> positions, BlockPos pos, BlockPos gatePos) {
        if (!pos.equals(gatePos)) {
            positions.add(pos);
        }
    }

    private BlockPos farmGatePos(FarmArea farmArea, PlayerNpcHomeUtil.HomeArea homeArea) {
        BlockPos farmCenter = farmArea.origin().offset(farmArea.width() / 2, 0, farmArea.depth() / 2);
        BlockPos homeCenter = homeArea.origin().offset(homeArea.width() / 2, 0, homeArea.depth() / 2);
        int dx = homeCenter.getX() - farmCenter.getX();
        int dz = homeCenter.getZ() - farmCenter.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx < 0
                    ? farmArea.origin().offset(-1, 1, farmArea.depth() / 2)
                    : farmArea.origin().offset(farmArea.width(), 1, farmArea.depth() / 2);
        }
        return dz < 0
                ? farmArea.origin().offset(farmArea.width() / 2, 1, -1)
                : farmArea.origin().offset(farmArea.width() / 2, 1, farmArea.depth());
    }

    private Optional<FarmArea> getFarmArea() {
        CompoundTag tag = this.playerNpc.getPersistentData();
        if (!tag.contains(FARM_X, Tag.TAG_INT)
                || !tag.contains(FARM_Y, Tag.TAG_INT)
                || !tag.contains(FARM_Z, Tag.TAG_INT)
                || !tag.contains(FARM_WIDTH, Tag.TAG_INT)
                || !tag.contains(FARM_DEPTH, Tag.TAG_INT)) {
            return Optional.empty();
        }

        return Optional.of(new FarmArea(
                new BlockPos(tag.getInt(FARM_X), tag.getInt(FARM_Y), tag.getInt(FARM_Z)),
                Math.max(4, tag.getInt(FARM_WIDTH)),
                Math.max(4, tag.getInt(FARM_DEPTH))
        ));
    }

    private void setFarmArea(FarmArea farmArea) {
        CompoundTag tag = this.playerNpc.getPersistentData();
        tag.putInt(FARM_X, farmArea.origin().getX());
        tag.putInt(FARM_Y, farmArea.origin().getY());
        tag.putInt(FARM_Z, farmArea.origin().getZ());
        tag.putInt(FARM_WIDTH, farmArea.width());
        tag.putInt(FARM_DEPTH, farmArea.depth());
    }

    private enum Action {
        NONE,
        PLACE_FENCE,
        PLACE_GATE,
        HARVEST,
        BONE_MEAL,
        CRAFT_BONE_MEAL,
        PLANT
    }

    private record PlantingCrop(Item item, BlockState state) {}

    private record FarmArea(BlockPos origin, int width, int depth) {
        boolean containsCrop(BlockPos cropPos) {
            return cropPos.getY() == this.origin.getY() + 1
                    && cropPos.getX() >= this.origin.getX()
                    && cropPos.getX() < this.origin.getX() + this.width
                    && cropPos.getZ() >= this.origin.getZ()
                    && cropPos.getZ() < this.origin.getZ() + this.depth;
        }
    }
}
