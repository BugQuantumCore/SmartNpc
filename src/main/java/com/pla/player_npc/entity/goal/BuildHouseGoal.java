package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcBuildLayout;
import com.pla.player_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.player_npc.util.PlayerNpcCraftingUtil;
import com.pla.player_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

public class BuildHouseGoal extends Goal {
    private static final int COOLDOWN_TICKS = 20 * 90;
    private static final int BUILD_SEARCH_RADIUS = 14;
    private static final int MAX_RANDOM_LAYOUT_ATTEMPTS = 24;

    private final PlayerNpcEntity playerNpc;
    private final List<BuildPlacement> blueprint = new ArrayList<>();
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private PlayerNpcBuildLayout selectedLayout;
    private BlockPos origin;
    private int placeDelay;

    public BuildHouseGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
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
        PlayerNpcHomeUtil.setHome(this.playerNpc, this.homeArea);
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
            this.blueprint.clear();
            return;
        }

        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        serverLevel.playSound(null, target, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        this.blueprint.remove(0);
    }

    @Override
    public void stop() {
        if (!this.playerNpc.level().isClientSide) {
            this.playerNpc.setBuildHouseCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 60));
        }
        this.blueprint.clear();
        this.homeArea = null;
        this.selectedLayout = null;
        this.origin = null;
        this.placeDelay = 0;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private List<BuildPlacement> createBlueprint(PlayerNpcBuildLayout layout, BlockPos origin) {
        return layout.blocks().stream()
                .map(block -> new BuildPlacement(block.toWorld(origin), block.role()))
                .toList();
    }

    private BuildSelection findBuildSelection(ServerLevel serverLevel, int availableBlocks) {
        List<PlayerNpcBuildLayout> layouts = PlayerNpcBuildLayoutLoader.getLayouts().stream()
                .filter(layout -> this.requiredBuildingBlocks(layout) <= availableBlocks)
                .toList();
        if (layouts.isEmpty()) {
            return null;
        }

        java.util.Optional<PlayerNpcHomeUtil.HomeArea> existingHome = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (existingHome.isPresent()) {
            PlayerNpcHomeUtil.HomeArea homeArea = existingHome.get();
            for (PlayerNpcBuildLayout layout : layouts) {
                if (layout.width() == homeArea.width()
                        && layout.depth() == homeArea.depth()
                        && this.canBuildAt(serverLevel, layout, homeArea.origin(), true)) {
                    return new BuildSelection(layout, homeArea.origin());
                }
            }
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
        for (BlockPos originCandidate : BlockPos.betweenClosed(
                center.offset(-BUILD_SEARCH_RADIUS, -1, -BUILD_SEARCH_RADIUS),
                center.offset(BUILD_SEARCH_RADIUS, 1, BUILD_SEARCH_RADIUS))) {
            origins.add(originCandidate.immutable());
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
        for (long packedFootprint : layout.footprint()) {
            int x = (int) (packedFootprint >> 32);
            int z = (int) packedFootprint;
            BlockPos floor = origin.offset(x, 0, z);
            if (!serverLevel.getBlockState(floor.below()).isSolidRender(serverLevel, floor.below())) {
                return false;
            }
            for (int y = 0; y < layout.height(); y++) {
                BlockPos checkPos = origin.offset(x, y, z);
                if (!serverLevel.isInWorldBounds(checkPos)
                        || !serverLevel.getWorldBorder().isWithinBounds(checkPos)
                        || (!serverLevel.getBlockState(checkPos).isAir()
                        && !(allowHomeUtilities && y > 0 && this.isHomeUtility(serverLevel.getBlockState(checkPos))))) {
                    return false;
                }
            }
        }
        return true;
    }

    private int countBuildingBlocks() {
        int count = 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (!stack.isEmpty() && this.isBuildingBlock(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private int requiredBuildingBlocks(PlayerNpcBuildLayout layout) {
        return (int) layout.blocks().stream()
                .filter(block -> !this.isOptionalUtilityRole(block.role()))
                .count();
    }

    private boolean placeBlueprintBlock(ServerLevel serverLevel, BuildPlacement placement) {
        String role = placement.role().toLowerCase(Locale.ROOT);
        if (role.contains("door")) {
            this.placeDoor(serverLevel, placement.pos());
            return true;
        }
        if (role.contains("torch")) {
            this.placeOptionalBlock(serverLevel, placement.pos(), this.consumeTorch());
            return true;
        }
        if (role.contains("fence")) {
            this.placeOptionalBlock(serverLevel, placement.pos(), this.consumeFence());
            return true;
        }
        if (role.contains("trapdoor")) {
            this.placeOptionalBlock(serverLevel, placement.pos(), this.consumeTrapdoor());
            return true;
        }
        if (role.contains("stair")) {
            this.placeOptionalBlock(serverLevel, placement.pos(), this.consumeStair());
            return true;
        }

        if (!serverLevel.getBlockState(placement.pos()).isAir()) {
            return true;
        }

        ItemStack blockStack = InventoryUtils.consumeItem(this.playerNpc, this::isBuildingBlock, 1)
                .orElse(ItemStack.EMPTY);
        if (blockStack.isEmpty() || !(blockStack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }

        serverLevel.setBlockAndUpdate(placement.pos(), blockItem.getBlock().defaultBlockState());
        return true;
    }

    private void placeDoor(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.getBlockState(pos).isAir() || !serverLevel.getBlockState(pos.above()).isAir()) {
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
        serverLevel.setBlockAndUpdate(pos, lower);
        serverLevel.setBlockAndUpdate(pos.above(), upper);
    }

    private void placeOptionalBlock(ServerLevel serverLevel, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        if (!serverLevel.getBlockState(pos).isAir() || !(stack.getItem() instanceof BlockItem blockItem)) {
            this.returnStack(stack);
            return;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        if (!state.canSurvive(serverLevel, pos)) {
            this.returnStack(stack);
            return;
        }
        serverLevel.setBlockAndUpdate(pos, state);
    }

    private ItemStack consumeDoor() {
        ItemStack door = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof DoorBlock, 1).orElse(ItemStack.EMPTY);
        if (!door.isEmpty()) {
            return door;
        }
        if (PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 6)) {
            return new ItemStack(Items.OAK_DOOR);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack consumeTorch() {
        ItemStack torch = this.playerNpc.consumeInventoryItem(Items.TORCH, 1).orElse(ItemStack.EMPTY);
        if (!torch.isEmpty()) {
            return torch;
        }
        if (PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.STICK)) >= 1
                && PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COAL) || stack.is(Items.CHARCOAL)) >= 1
                && PlayerNpcCraftingUtil.consumeItem(this.playerNpc.getInventory(), stack -> stack.is(Items.STICK), 1)
                && PlayerNpcCraftingUtil.consumeItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COAL) || stack.is(Items.CHARCOAL), 1)) {
            return new ItemStack(Items.TORCH);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack consumeFence() {
        ItemStack fence = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof FenceBlock, 1).orElse(ItemStack.EMPTY);
        if (!fence.isEmpty()) {
            return fence;
        }
        if (PlayerNpcCraftingUtil.tryConsumePlanksAndSticks(this.playerNpc.getInventory(), 4, 2)) {
            return new ItemStack(Items.OAK_FENCE);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack consumeTrapdoor() {
        ItemStack trapdoor = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof TrapDoorBlock, 1).orElse(ItemStack.EMPTY);
        if (!trapdoor.isEmpty()) {
            return trapdoor;
        }
        if (PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 6)) {
            return new ItemStack(Items.OAK_TRAPDOOR);
        }
        return ItemStack.EMPTY;
    }

    private ItemStack consumeStair() {
        ItemStack stair = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof StairBlock, 1).orElse(ItemStack.EMPTY);
        if (!stair.isEmpty()) {
            return stair;
        }
        if (PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 6)) {
            return new ItemStack(Items.OAK_STAIRS);
        }
        return ItemStack.EMPTY;
    }

    private void returnStack(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }

    private boolean isOptionalUtilityRole(String role) {
        String normalizedRole = role.toLowerCase(Locale.ROOT);
        return normalizedRole.contains("door")
                || normalizedRole.contains("torch")
                || normalizedRole.contains("fence")
                || normalizedRole.contains("trapdoor")
                || normalizedRole.contains("stair");
    }

    private boolean isBuildingBlock(ItemStack stack) {
        return !stack.isEmpty()
                && stack.getItem() instanceof BlockItem
                && !stack.is(Items.CRAFTING_TABLE)
                && !stack.is(Items.FURNACE)
                && !stack.is(Items.CHEST)
                && !(stack.getItem() instanceof BedItem);
    }

    private boolean isHomeUtility(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
                || state.is(net.minecraft.world.level.block.Blocks.CHEST)
                || state.getBlock() instanceof net.minecraft.world.level.block.BedBlock;
    }

    private record BuildPlacement(BlockPos pos, String role) {}

    private record BuildSelection(PlayerNpcBuildLayout layout, BlockPos origin) {}
}
