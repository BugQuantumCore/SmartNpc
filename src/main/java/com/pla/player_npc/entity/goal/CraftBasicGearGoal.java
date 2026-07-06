package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcCraftingUtil;
import com.pla.player_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

public class CraftBasicGearGoal extends Goal {
    public static final String TEMP_TABLE_X = "PlayerNpcTemporaryCraftingTableX";
    public static final String TEMP_TABLE_Y = "PlayerNpcTemporaryCraftingTableY";
    public static final String TEMP_TABLE_Z = "PlayerNpcTemporaryCraftingTableZ";
    private static final int COOLDOWN_TICKS = 20 * 4;
    private static final int CRITICAL_TOOL_COOLDOWN_TICKS = 5;
    private static final int CRAFTING_TABLE_SCAN_RADIUS = 5;
    private static final double HOME_CRAFTING_DISTANCE_SQR = 48.0D * 48.0D;
    private static final double CRAFTING_TABLE_USE_DISTANCE_SQR = 2.25D * 2.25D;
    private static final int CRAFT_ACTION_DELAY_TICKS = 12;

    private final PlayerNpcEntity playerNpc;
    private BlockPos craftingTablePos;
    private BlockPos craftingStandPos;
    private int actionDelayTicks;
    private boolean finished;
    private boolean craftedTool;

    public CraftBasicGearGoal(PlayerNpcEntity playerNpc) {
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
                || "ai.player_npc.gathering_materials".equals(this.playerNpc.getCurrentAiState())
                || "ai.player_npc.digging_down_for_stone".equals(this.playerNpc.getCurrentAiState())
                || (this.playerNpc.getCraftGearCooldown() > 0 && !this.needsCriticalStarterTool())) {
            return false;
        }

        this.resetPlan();
        if (!this.canCraftTool()) {
            return false;
        }

        BlockPos tablePos = this.findNearbyCraftingTable(serverLevel);
        if (tablePos != null) {
            BlockPos standPos = this.findCraftingStand(serverLevel, tablePos);
            if (standPos != null) {
                this.craftingTablePos = tablePos;
                this.craftingStandPos = standPos;
                return true;
            }
        }

        if (this.shouldPlaceCraftingTable(serverLevel)) {
            BlockPos placement = this.findCraftingTablePlacement(serverLevel);
            if (placement != null) {
                this.craftingTablePos = placement;
                this.craftingStandPos = this.playerNpc.blockPosition();
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.craftingTablePos != null
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.getTarget() == null;
    }

    @Override
    public void start() {
        this.actionDelayTicks = 0;
        this.finished = false;
        this.craftedTool = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.crafting_gear");
        this.playerNpc.setCurrentAiDetail("moving to crafting table");
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.craftingTablePos == null) {
            this.finished = true;
            return;
        }

        BlockState tableState = serverLevel.getBlockState(this.craftingTablePos);
        if (!tableState.is(Blocks.CRAFTING_TABLE)) {
            this.tickPlaceCraftingTable(serverLevel);
            return;
        }

        if (this.craftingStandPos == null || !this.canStandAt(serverLevel, this.craftingStandPos)) {
            this.craftingStandPos = this.findCraftingStand(serverLevel, this.craftingTablePos);
            if (this.craftingStandPos == null) {
                this.finished = true;
                return;
            }
        }

        this.playerNpc.getLookControl().setLookAt(
                this.craftingTablePos.getX() + 0.5D,
                this.craftingTablePos.getY() + 0.5D,
                this.craftingTablePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (!this.isAtCraftingStand()) {
            this.playerNpc.setCurrentAiDetail("walking to crafting table");
            this.moveToCraftingStand();
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.actionDelayTicks++ < CRAFT_ACTION_DELAY_TICKS) {
            return;
        }
        this.actionDelayTicks = 0;
        this.tickCraftAtTable(serverLevel);
    }

    @Override
    public void stop() {
        if (!this.playerNpc.level().isClientSide) {
            int cooldown = this.craftedTool && this.needsCriticalStarterTool() && this.playerNpc.level() instanceof ServerLevel serverLevel && this.canCraftUsefulGear(serverLevel)
                    ? CRITICAL_TOOL_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(10)
                    : this.craftedTool
                    ? COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 15)
                    : 20 + this.playerNpc.getRandom().nextInt(20);
            this.playerNpc.setCraftGearCooldown(cooldown);
        }
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.resetPlan();
    }

    private void tickPlaceCraftingTable(ServerLevel serverLevel) {
        this.playerNpc.getNavigation().stop();
        this.playerNpc.getLookControl().setLookAt(
                this.craftingTablePos.getX() + 0.5D,
                this.craftingTablePos.getY() + 0.5D,
                this.craftingTablePos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (this.actionDelayTicks++ < CRAFT_ACTION_DELAY_TICKS) {
            this.playerNpc.setCurrentAiDetail("preparing crafting table");
            return;
        }
        this.actionDelayTicks = 0;

        int rawLogReserve = this.rawLogReserveForCurrentNeed();
        if (!this.hasCarriedCraftingTable() && PlayerNpcCraftingUtil.countPlanks(this.playerNpc.getInventory()) < 4) {
            if (PlayerNpcCraftingUtil.countLogs(this.playerNpc.getInventory()) > rawLogReserve
                    && PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), rawLogReserve)) {
                this.playCraftStep(serverLevel, "crafting logs into planks");
                return;
            }
            this.finished = true;
            return;
        }

        ItemStack tableStack = this.playerNpc.consumeInventoryItem(Items.CRAFTING_TABLE, 1).orElse(ItemStack.EMPTY);
        if (tableStack.isEmpty() && !PlayerNpcCraftingUtil.tryConsumePlanks(this.playerNpc.getInventory(), 4, rawLogReserve)) {
            this.finished = true;
            return;
        }

        serverLevel.setBlockAndUpdate(this.craftingTablePos, Blocks.CRAFTING_TABLE.defaultBlockState());
        this.playerNpc.getPersistentData().putInt(TEMP_TABLE_X, this.craftingTablePos.getX());
        this.playerNpc.getPersistentData().putInt(TEMP_TABLE_Y, this.craftingTablePos.getY());
        this.playerNpc.getPersistentData().putInt(TEMP_TABLE_Z, this.craftingTablePos.getZ());
        this.craftingStandPos = this.findCraftingStand(serverLevel, this.craftingTablePos);
        this.playCraftStep(serverLevel, "placed crafting table");
    }

    private void tickCraftAtTable(ServerLevel serverLevel) {
        ToolRecipe recipe = this.nextToolRecipe();
        if (recipe == null) {
            this.finished = true;
            return;
        }

        int rawLogReserve = this.rawLogReserveForRecipe(recipe);
        if (!PlayerNpcCraftingUtil.canCraft(serverLevel, this.playerNpc.getInventory(), recipe.result().getItem(), true)
                && PlayerNpcCraftingUtil.countSticks(this.playerNpc.getInventory()) < recipe.sticksNeeded()) {
            if (PlayerNpcCraftingUtil.tryCraftSticks(serverLevel, this.playerNpc.getInventory(), rawLogReserve)) {
                this.playCraftStep(serverLevel, "crafting planks into sticks");
                return;
            }
            this.finished = true;
            return;
        }

        if (!PlayerNpcCraftingUtil.canCraft(serverLevel, this.playerNpc.getInventory(), recipe.result().getItem(), true)
                && PlayerNpcCraftingUtil.countLogs(this.playerNpc.getInventory()) > rawLogReserve) {
            if (PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), rawLogReserve)) {
                this.playCraftStep(serverLevel, "crafting logs into planks");
                return;
            }
            this.finished = true;
            return;
        }

        if (!PlayerNpcCraftingUtil.canCraft(serverLevel, this.playerNpc.getInventory(), recipe.result().getItem(), true)) {
            this.finished = true;
            return;
        }

        if (this.tryCraftRecipe(serverLevel, recipe)) {
            this.craftedTool = true;
            this.playCraftStep(serverLevel, "crafted " + recipe.result().getHoverName().getString());
        }
        this.finished = true;
    }

    private void playCraftStep(ServerLevel serverLevel, String detail) {
        this.playerNpc.setCurrentAiDetail(detail);
        this.playerNpc.triggerMainHandUseAnimation();
        serverLevel.playSound(null, this.craftingTablePos == null ? this.playerNpc.blockPosition() : this.craftingTablePos, SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 0.6F, 1.2F);
    }

    private boolean canCraftUsefulGear(ServerLevel serverLevel) {
        if (!this.canCraftTool()) {
            return false;
        }
        return this.hasNearbyCraftingTable(serverLevel)
                || this.shouldPlaceCraftingTable(serverLevel);
    }

    private boolean canCraftTool() {
        int rawLogReserve = this.rawLogReserveForCurrentNeed();
        if (!this.hasTool(AxeItem.class)) {
            return this.canCraftStoneTool(3, 2, rawLogReserve) || PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 3, 2, rawLogReserve);
        }
        if (!this.hasTool(PickaxeItem.class)) {
            return this.canCraftStoneTool(3, 2, rawLogReserve) || PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 3, 2, rawLogReserve);
        }
        if (!this.hasTool(ShovelItem.class)) {
            return this.canCraftStoneTool(1, 2, rawLogReserve) || PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 1, 2, rawLogReserve);
        }
        if (!this.hasTool(SwordItem.class)) {
            return this.canCraftStoneTool(2, 1, rawLogReserve) || PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 2, 1, rawLogReserve);
        }
        return !this.hasTool(FishingRodItem.class)
                && PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.STRING)) >= 2
                && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, 3, rawLogReserve);
    }

    private boolean canCraftToolAfterPlacedTable() {
        int reservedPlanks = this.hasCarriedCraftingTable() ? 0 : 4;
        int rawLogReserve = this.rawLogReserveForCurrentNeed();
        if (!this.hasTool(AxeItem.class)) {
            return this.canCraftStoneTool(3, 2, reservedPlanks, rawLogReserve) || this.canProvidePlanksAndSticks(3, 2, reservedPlanks, rawLogReserve);
        }
        if (!this.hasTool(PickaxeItem.class)) {
            return this.canCraftStoneTool(3, 2, reservedPlanks, rawLogReserve) || this.canProvidePlanksAndSticks(3, 2, reservedPlanks, rawLogReserve);
        }
        if (!this.hasTool(ShovelItem.class)) {
            return this.canCraftStoneTool(1, 2, reservedPlanks, rawLogReserve) || this.canProvidePlanksAndSticks(1, 2, reservedPlanks, rawLogReserve);
        }
        if (!this.hasTool(SwordItem.class)) {
            return this.canCraftStoneTool(2, 1, reservedPlanks, rawLogReserve) || this.canProvidePlanksAndSticks(2, 1, reservedPlanks, rawLogReserve);
        }
        return !this.hasTool(FishingRodItem.class)
                && PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.STRING)) >= 2
                && this.canProvidePlanksAndSticks(0, 3, reservedPlanks, rawLogReserve);
    }

    private boolean shouldPlaceCraftingTable(ServerLevel serverLevel) {
        if (!this.needsBasicGear()
                || this.hasNearbyCraftingTable(serverLevel)
                || (this.isNearSavedHome(serverLevel) && !this.needsCriticalStarterTool())
                || this.hasValidTemporaryCraftingTable(serverLevel)
                || !this.canCraftToolAfterPlacedTable()) {
            return false;
        }

        return (this.hasCarriedCraftingTable()
                || this.countCarriedCraftingTables() == 0 && PlayerNpcCraftingUtil.canCraftCraftingTable(this.playerNpc.getInventory()))
                && this.findCraftingTablePlacement(serverLevel) != null;
    }

    private boolean needsBasicGear() {
        return !this.hasTool(PickaxeItem.class)
                || !this.hasTool(AxeItem.class)
                || !this.hasTool(SwordItem.class)
                || !this.hasTool(ShovelItem.class);
    }

    private boolean needsCriticalStarterTool() {
        return !this.hasTool(AxeItem.class) || !this.hasTool(PickaxeItem.class);
    }

    private ToolRecipe nextToolRecipe() {
        if (!this.hasTool(AxeItem.class)) {
            return this.countStone() >= 3
                    ? new ToolRecipe(Items.STONE_AXE.getDefaultInstance(), true, 3, 2)
                    : new ToolRecipe(Items.WOODEN_AXE.getDefaultInstance(), false, 3, 2);
        }
        if (!this.hasTool(PickaxeItem.class)) {
            return this.countStone() >= 3
                    ? new ToolRecipe(Items.STONE_PICKAXE.getDefaultInstance(), true, 3, 2)
                    : new ToolRecipe(Items.WOODEN_PICKAXE.getDefaultInstance(), false, 3, 2);
        }
        if (!this.hasTool(ShovelItem.class)) {
            return this.countStone() >= 1
                    ? new ToolRecipe(Items.STONE_SHOVEL.getDefaultInstance(), true, 1, 2)
                    : new ToolRecipe(Items.WOODEN_SHOVEL.getDefaultInstance(), false, 1, 2);
        }
        if (!this.hasTool(SwordItem.class)) {
            return this.countStone() >= 2
                    ? new ToolRecipe(Items.STONE_SWORD.getDefaultInstance(), true, 2, 1)
                    : new ToolRecipe(Items.WOODEN_SWORD.getDefaultInstance(), false, 2, 1);
        }
        if (!this.hasTool(FishingRodItem.class)
                && PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.STRING)) >= 2) {
            return new ToolRecipe(Items.FISHING_ROD.getDefaultInstance(), false, 0, 3);
        }
        return null;
    }

    private boolean tryCraftRecipe(ServerLevel serverLevel, ToolRecipe recipe) {
        return PlayerNpcCraftingUtil.craftItem(serverLevel, this.playerNpc.getInventory(), recipe.result().getItem(), true)
                .map(stack -> InventoryUtils.addItem(this.playerNpc, stack))
                .orElse(false);
    }

    private boolean hasTool(Class<?> toolClass) {
        if (toolClass.isInstance(this.playerNpc.getMainHandItem().getItem())) {
            return true;
        }
        return InventoryUtils.hasItem(this.playerNpc, stack -> toolClass.isInstance(stack.getItem()));
    }

    private int countStone() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    private boolean consumeStone(int count) {
        return PlayerNpcCraftingUtil.consumeItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE), count);
    }

    private boolean canCraftStoneTool(int stoneNeeded, int sticksNeeded) {
        return this.countStone() >= stoneNeeded
                && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, sticksNeeded, this.rawLogReserveForCurrentNeed());
    }

    private boolean canCraftStoneTool(int stoneNeeded, int sticksNeeded, int rawLogReserve) {
        return this.countStone() >= stoneNeeded
                && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, sticksNeeded, rawLogReserve);
    }

    private boolean canCraftStoneTool(int stoneNeeded, int sticksNeeded, int reservedPlanks, int rawLogReserve) {
        return this.countStone() >= stoneNeeded
                && this.canProvidePlanksAndSticks(0, sticksNeeded, reservedPlanks, rawLogReserve);
    }

    private boolean canProvidePlanksAndSticks(int planksNeeded, int sticksNeeded, int reservedPlanks, int rawLogReserve) {
        int availablePlanks = PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), rawLogReserve) - reservedPlanks;
        if (availablePlanks < 0) {
            return false;
        }

        int availableSticks = PlayerNpcCraftingUtil.countSticks(this.playerNpc.getInventory());
        int missingSticks = Math.max(0, sticksNeeded - availableSticks);
        int planksForSticks = ((missingSticks + 3) / 4) * 2;
        return availablePlanks >= planksNeeded + planksForSticks;
    }

    private int rawLogReserveForCurrentNeed() {
        return this.needsCriticalStarterTool() ? 0 : this.playerNpc.getRawLogReserveTarget();
    }

    private int rawLogReserveForRecipe(ToolRecipe recipe) {
        return this.isCriticalStarterRecipe(recipe) ? 0 : this.playerNpc.getRawLogReserveTarget();
    }

    private boolean isCriticalStarterRecipe(ToolRecipe recipe) {
        return recipe.result().is(Items.WOODEN_AXE)
                || recipe.result().is(Items.STONE_AXE)
                || recipe.result().is(Items.WOODEN_PICKAXE)
                || recipe.result().is(Items.STONE_PICKAXE);
    }

    private boolean hasNearbyCraftingTable(ServerLevel serverLevel) {
        return this.findNearbyCraftingTable(serverLevel) != null;
    }

    private BlockPos findNearbyCraftingTable(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-CRAFTING_TABLE_SCAN_RADIUS, -2, -CRAFTING_TABLE_SCAN_RADIUS),
                origin.offset(CRAFTING_TABLE_SCAN_RADIUS, 2, CRAFTING_TABLE_SCAN_RADIUS))) {
            if (serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findCraftingStand(ServerLevel serverLevel, BlockPos tablePos) {
        List<BlockPos> candidates = new ArrayList<>();
        candidates.add(this.playerNpc.blockPosition());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(tablePos.relative(direction));
        }

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!this.canStandAt(serverLevel, immutable)
                    || this.distanceToTableSqr(immutable, tablePos) > CRAFTING_TABLE_USE_DISTANCE_SQR) {
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

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).isAir()
                && serverLevel.getBlockState(pos.above()).isAir()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below());
    }

    private boolean isAtCraftingStand() {
        return this.craftingStandPos != null
                && this.playerNpc.distanceToSqr(this.craftingStandPos.getX() + 0.5D, this.craftingStandPos.getY(), this.craftingStandPos.getZ() + 0.5D) <= 1.25D * 1.25D
                && this.distanceToTableSqr(this.playerNpc.blockPosition(), this.craftingTablePos) <= CRAFTING_TABLE_USE_DISTANCE_SQR + 1.0D;
    }

    private boolean moveToCraftingStand() {
        if (this.craftingStandPos == null) {
            return false;
        }
        Path path = this.playerNpc.getNavigation().createPath(this.craftingStandPos, 0);
        if (path == null || !path.canReach()) {
            return false;
        }
        return this.playerNpc.getNavigation().moveTo(path, 1.0D);
    }

    private double distanceToTableSqr(BlockPos standPos, BlockPos tablePos) {
        if (tablePos == null) {
            return Double.MAX_VALUE;
        }
        double dx = standPos.getX() + 0.5D - (tablePos.getX() + 0.5D);
        double dy = standPos.getY() + 0.5D - (tablePos.getY() + 0.5D);
        double dz = standPos.getZ() + 0.5D - (tablePos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean hasCarriedCraftingTable() {
        return InventoryUtils.hasItem(this.playerNpc, Items.CRAFTING_TABLE);
    }

    private int countCarriedCraftingTables() {
        int count = PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.CRAFTING_TABLE));
        if (this.playerNpc.getMainHandItem().is(Items.CRAFTING_TABLE)) {
            count += this.playerNpc.getMainHandItem().getCount();
        }
        if (this.playerNpc.getOffhandItem().is(Items.CRAFTING_TABLE)) {
            count += this.playerNpc.getOffhandItem().getCount();
        }
        return count;
    }

    private boolean isNearSavedHome(ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return false;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        BlockPos homeCenter = homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
        return this.playerNpc.distanceToSqr(homeCenter.getX() + 0.5D, homeCenter.getY(), homeCenter.getZ() + 0.5D) <= HOME_CRAFTING_DISTANCE_SQR;
    }

    private boolean hasValidTemporaryCraftingTable(ServerLevel serverLevel) {
        BlockPos pos = this.getTemporaryCraftingTablePos();
        if (pos == null) {
            return false;
        }
        if (serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
            return true;
        }

        this.clearTemporaryCraftingTable();
        return false;
    }

    private BlockPos getTemporaryCraftingTablePos() {
        if (!this.playerNpc.getPersistentData().contains(TEMP_TABLE_X)) {
            return null;
        }

        return new BlockPos(
                this.playerNpc.getPersistentData().getInt(TEMP_TABLE_X),
                this.playerNpc.getPersistentData().getInt(TEMP_TABLE_Y),
                this.playerNpc.getPersistentData().getInt(TEMP_TABLE_Z)
        );
    }

    private void clearTemporaryCraftingTable() {
        this.playerNpc.getPersistentData().remove(TEMP_TABLE_X);
        this.playerNpc.getPersistentData().remove(TEMP_TABLE_Y);
        this.playerNpc.getPersistentData().remove(TEMP_TABLE_Z);
    }

    private BlockPos findCraftingTablePlacement(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        BlockPos[] candidates = {
                origin.relative(this.playerNpc.getDirection()),
                origin.relative(this.playerNpc.getDirection().getClockWise()),
                origin.relative(this.playerNpc.getDirection().getCounterClockWise()),
                origin.relative(this.playerNpc.getDirection().getOpposite())
        };

        for (BlockPos candidate : candidates) {
            if (PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, candidate)
                    && serverLevel.getBlockState(candidate.below()).isSolidRender(serverLevel, candidate.below())) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private void resetPlan() {
        this.craftingTablePos = null;
        this.craftingStandPos = null;
        this.actionDelayTicks = 0;
        this.finished = false;
        this.craftedTool = false;
    }

    private record ToolRecipe(ItemStack result, boolean usesStone, int materialNeeded, int sticksNeeded) {}
}
