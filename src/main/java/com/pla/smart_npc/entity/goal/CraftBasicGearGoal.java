package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.FarmAi;
import com.pla.smart_npc.entity.ai.PathNavigationAi;
import com.pla.smart_npc.entity.ai.PlacingBlockAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcGearUtil;
import com.pla.smart_npc.util.PlayerNpcGearUtil.ToolKind;
import com.pla.smart_npc.util.PlayerNpcGearUtil.ToolTier;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
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
    private static final int FAILED_CRAFT_RETRY_TICKS = 20 * 15;
    private static final int CRAFTING_TABLE_SCAN_RADIUS = 5;
    private static final int CRAFTING_TABLE_PLACEMENT_RADIUS = 3;
    private static final double HOME_CRAFTING_DISTANCE_SQR = 48.0D * 48.0D;
    private static final double TEMP_CRAFTING_TABLE_REUSE_DISTANCE_SQR = 32.0D * 32.0D;
    private static final double CRAFTING_TABLE_USE_DISTANCE_SQR = 2.25D * 2.25D;
    private static final int CRAFT_ACTION_DELAY_TICKS = 12;
    private static final int CRAFTING_REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_ACTIVATION_STAND_PATH_CHECKS = 6;
    private static final double DIRECT_CRAFTING_STEP_DISTANCE_SQR = 8.0D * 8.0D;

    public static boolean hasTemporaryCraftingTable(PlayerNpcEntity playerNpc) {
        return getTemporaryCraftingTablePos(playerNpc) != null;
    }

    public static boolean hasValidTemporaryCraftingTable(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        BlockPos pos = getTemporaryCraftingTablePos(playerNpc);
        return pos != null
                && serverLevel.hasChunkAt(pos)
                && serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    public static boolean isTemporaryCraftingTable(PlayerNpcEntity playerNpc, ServerLevel serverLevel, BlockPos pos) {
        BlockPos tablePos = getTemporaryCraftingTablePos(playerNpc);
        return tablePos != null
                && tablePos.equals(pos)
                && serverLevel.hasChunkAt(pos)
                && serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE);
    }

    public static boolean shouldKeepTemporaryCraftingTableForGear(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!hasValidTemporaryCraftingTable(playerNpc, serverLevel)) {
            return false;
        }

        CraftBasicGearGoal probe = new CraftBasicGearGoal(playerNpc);
        return probe.needsCriticalStarterTool() || probe.canCraftTool();
    }

    public static boolean shouldPrioritizeGearCrafting(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (playerNpc == null
                || serverLevel == null
                || !playerNpc.isAlive()
                || playerNpc.isNoAi()
                || playerNpc.isPassenger()
                || playerNpc.isHealing()
                || playerNpc.getTarget() != null
                || "ai.player_npc.gathering_materials".equals(playerNpc.getCurrentAiState())) {
            return false;
        }

        CraftBasicGearGoal probe = new CraftBasicGearGoal(playerNpc);
        boolean logGatheringEpisodeActive = GatherLogsGoal.isLogGatheringEpisodeActive(playerNpc);
        boolean missingPickaxe = !probe.hasTool(PickaxeItem.class);
        boolean blockedByVerticalEscape = isBlockedByVerticalEscape(playerNpc, logGatheringEpisodeActive);
        boolean emergencyPickaxeCraft = missingPickaxe && blockedByVerticalEscape;
        if (logGatheringEpisodeActive && !emergencyPickaxeCraft) {
            return false;
        }
        if (shouldYieldToResourceSupply(playerNpc, serverLevel) && !emergencyPickaxeCraft) {
            return false;
        }
        if (blockedByVerticalEscape && !missingPickaxe) {
            return false;
        }
        if (playerNpc.getCraftGearCooldown() > 0 && !probe.needsCriticalStarterToolForCooldown()) {
            return false;
        }
        // This predicate is consulted by several lower-priority goals. Keep it inventory/exact-
        // ownership based; the actual higher-priority CraftBasicGearGoal performs the admitted
        // nearby-table and placement/path plan once during canUse().
        return probe.canCraftTool()
                && (hasValidTemporaryCraftingTable(playerNpc, serverLevel)
                || probe.hasCarriedCraftingTable()
                || PlayerNpcCraftingUtil.canCraftCraftingTable(playerNpc.getInventory()));
    }

    public static boolean needsFishingRodCraftingLogs(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        if (!isFishingRodBootstrapActive(playerNpc) || serverLevel == null) {
            return false;
        }

        CraftBasicGearGoal probe = new CraftBasicGearGoal(playerNpc);
        ToolRecipe recipe = probe.nextFishingRodRecipe();
        if (recipe == null) {
            return false;
        }
        // This method participates in log-supply arbitration and must remain a pure inventory /
        // exact-owned-table predicate. Nearby station, placement-volume, stand and path discovery
        // belong to the admitted CraftBasicGearGoal activation.
        int reservedTablePlanks = hasValidTemporaryCraftingTable(playerNpc, serverLevel)
                || probe.hasCarriedCraftingTable()
                ? 0
                : 4;
        return !probe.canProvideRecipeMaterials(
                recipe,
                reservedTablePlanks,
                probe.rawLogReserveForRecipe(recipe)
        );
    }

    public static BlockPos getTemporaryCraftingTablePos(PlayerNpcEntity playerNpc) {
        if (playerNpc == null || !playerNpc.getPersistentData().contains(TEMP_TABLE_X)) {
            return null;
        }

        return new BlockPos(
                playerNpc.getPersistentData().getInt(TEMP_TABLE_X),
                playerNpc.getPersistentData().getInt(TEMP_TABLE_Y),
                playerNpc.getPersistentData().getInt(TEMP_TABLE_Z)
        );
    }

    public static void clearTemporaryCraftingTable(PlayerNpcEntity playerNpc) {
        if (playerNpc == null) {
            return;
        }
        playerNpc.getPersistentData().remove(TEMP_TABLE_X);
        playerNpc.getPersistentData().remove(TEMP_TABLE_Y);
        playerNpc.getPersistentData().remove(TEMP_TABLE_Z);
    }

    private final PlayerNpcEntity playerNpc;
    private final PlacingBlockAi placingBlockAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private BlockPos craftingTablePos;
    private BlockPos craftingStandPos;
    private int actionDelayTicks;
    private boolean finished;
    private boolean craftedTool;
    private boolean emergencyPickaxeCraft;
    private boolean craftingTableInteracted;
    private int failedCraftRetryAfterTick;
    private int nextCraftingPathAttemptTick;
    private int activationStandPathChecksRemaining;
    private boolean activationPlanning;
    private BlockPos plannedPlacementStand;

    public CraftBasicGearGoal(PlayerNpcEntity playerNpc) {
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
                || this.playerNpc.tickCount < this.failedCraftRetryAfterTick
                || "ai.player_npc.gathering_materials".equals(this.playerNpc.getCurrentAiState())) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        boolean logGatheringEpisodeActive = GatherLogsGoal.isLogGatheringEpisodeActive(this.playerNpc);
        boolean missingPickaxe = !this.hasTool(PickaxeItem.class);
        boolean blockedByVerticalEscape = isBlockedByVerticalEscape(this.playerNpc, logGatheringEpisodeActive);
        boolean emergencyPickaxeCraft = missingPickaxe && blockedByVerticalEscape;
        if (logGatheringEpisodeActive && !emergencyPickaxeCraft) {
            return false;
        }
        if (shouldYieldToResourceSupply(this.playerNpc, serverLevel) && !emergencyPickaxeCraft) {
            return false;
        }
        if (blockedByVerticalEscape && !missingPickaxe) {
            return false;
        }
        if (!PlayerNpcAiWorkBudget.tryAcquire(serverLevel, this.playerNpc)) {
            return false;
        }
        boolean needsTerraformShovel = this.needsTerraformShovel(serverLevel);
        boolean needsFarmHoe = FarmAi.needsHoe(this.playerNpc, serverLevel);
        boolean needsCriticalStarterTool = this.needsCriticalStarterToolForCooldown();
        boolean priorityCrafting = this.playerNpc.isStoneAccessClearing() && this.canCraftTool();
        if (this.playerNpc.getCraftGearCooldown() > 0
                && !priorityCrafting
                && !needsCriticalStarterTool
                && !needsTerraformShovel
                && !needsFarmHoe) {
            return false;
        }

        this.resetPlan();
        this.activationPlanning = true;
        this.activationStandPathChecksRemaining = MAX_ACTIVATION_STAND_PATH_CHECKS;
        this.emergencyPickaxeCraft = emergencyPickaxeCraft;
        if (!this.canCraftTool()) {
            this.resetPlan();
            return false;
        }

        CraftingTableUse nearbyTable = this.findNearbyCraftingTableUse(serverLevel);
        if (nearbyTable != null) {
            this.craftingTablePos = nearbyTable.tablePos();
            this.craftingStandPos = nearbyTable.standPos();
            return true;
        }

        CraftingTableUse temporaryTable = this.findReusableTemporaryCraftingTable(serverLevel);
        if (temporaryTable != null) {
            this.craftingTablePos = temporaryTable.tablePos();
            this.craftingStandPos = temporaryTable.standPos();
            return true;
        }
        this.clearUnusableTemporaryCraftingTable(serverLevel);

        if (this.canPlanCraftingTablePlacement(serverLevel)) {
            BlockPos placement = this.findCraftingTablePlacement(serverLevel);
            if (placement != null) {
                BlockPos stand = this.plannedPlacementStand;
                if (stand == null) {
                    return false;
                }
                this.craftingTablePos = placement;
                this.craftingStandPos = stand;
                return true;
            }
            this.playerNpc.setCraftGearCooldown(20 * 2);
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
                && (this.emergencyPickaxeCraft || this.playerNpc.getUpwardEscapeTarget() == null)
                && (this.emergencyPickaxeCraft || this.playerNpc.getHoleEscapeCooldown() <= 0)
                && this.playerNpc.getTarget() == null;
    }

    private static boolean shouldYieldToResourceSupply(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        return GatherLogsGoal.hasLogSupplyDemand(playerNpc, serverLevel)
                || GatherStoneGoal.isStoneSupplyPhaseActive(playerNpc, serverLevel);
    }

    private static boolean isBlockedByVerticalEscape(PlayerNpcEntity playerNpc, boolean logGatheringEpisodeActive) {
        return playerNpc.getUpwardEscapeTarget() != null
                || playerNpc.getHoleEscapeCooldown() > 0
                || "ai.player_npc.digging_down_for_stone".equals(playerNpc.getCurrentAiState())
                || (!logGatheringEpisodeActive
                    && "ai.player_npc.pillaring_up".equals(playerNpc.getCurrentAiState()));
    }

    @Override
    public void start() {
        this.activationPlanning = false;
        this.actionDelayTicks = 0;
        this.finished = false;
        this.craftedTool = false;
        this.craftingTableInteracted = false;
        this.nextCraftingPathAttemptTick = this.playerNpc.tickCount;
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
            this.moveToCraftingStandOnCadence();
            return;
        }

        this.playerNpc.getNavigation().stop();
        this.interactWithCraftingTableOnce();
        if (this.actionDelayTicks++ < CRAFT_ACTION_DELAY_TICKS) {
            return;
        }
        this.actionDelayTicks = 0;
        this.tickCraftAtTable(serverLevel);
    }

    @Override
    public void stop() {
        if (!this.playerNpc.level().isClientSide) {
            boolean canStillCraftUsefulGear = this.playerNpc.level() instanceof ServerLevel serverLevel
                    && this.canCraftUsefulGear(serverLevel);
            boolean failedCraftAttempt = this.finished && !this.craftedTool && canStillCraftUsefulGear;
            int cooldown = failedCraftAttempt
                    ? FAILED_CRAFT_RETRY_TICKS + this.playerNpc.getRandom().nextInt(20 * 10)
                    : this.craftedTool && this.needsCriticalStarterTool() && canStillCraftUsefulGear
                    ? CRITICAL_TOOL_COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(10)
                    : this.craftedTool
                    ? COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 15)
                    : canStillCraftUsefulGear
                    ? CRITICAL_TOOL_COOLDOWN_TICKS
                    : 20 + this.playerNpc.getRandom().nextInt(20);
            this.failedCraftRetryAfterTick = failedCraftAttempt
                    ? this.playerNpc.tickCount + cooldown
                    : 0;
            this.playerNpc.setCraftGearCooldown(cooldown);
        }
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
        this.nextCraftingPathAttemptTick = 0;
        this.resetPlan();
    }

    private void tickPlaceCraftingTable(ServerLevel serverLevel) {
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
            this.actionDelayTicks = 0;
            this.playerNpc.setCurrentAiDetail("walking to crafting table placement");
            if (this.playerNpc.tickCount >= this.nextCraftingPathAttemptTick
                    && !this.moveToCraftingStandOnCadence()) {
                this.finished = true;
            }
            return;
        }

        if (!this.canPlaceCraftingTableAt(serverLevel, this.craftingTablePos)) {
            this.finished = true;
            return;
        }

        this.playerNpc.getNavigation().stop();
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

        if (!this.placingBlockAi.placeBlock(serverLevel, this.craftingTablePos, Blocks.CRAFTING_TABLE.defaultBlockState())) {
            InventoryUtils.addItem(this.playerNpc, tableStack.isEmpty() ? new ItemStack(Items.CRAFTING_TABLE) : tableStack);
            this.finished = true;
            return;
        }
        this.playerNpc.getPersistentData().putInt(TEMP_TABLE_X, this.craftingTablePos.getX());
        this.playerNpc.getPersistentData().putInt(TEMP_TABLE_Y, this.craftingTablePos.getY());
        this.playerNpc.getPersistentData().putInt(TEMP_TABLE_Z, this.craftingTablePos.getZ());
        this.craftingStandPos = this.findCraftingStand(serverLevel, this.craftingTablePos);
        this.playerNpc.setCurrentAiDetail("placed crafting table");
    }

    private void tickCraftAtTable(ServerLevel serverLevel) {
        ToolRecipe recipe = this.nextToolRecipe();
        if (recipe == null) {
            this.finished = true;
            return;
        }

        int rawLogReserve = this.rawLogReserveForRecipe(recipe);
        boolean canCraftRecipe = PlayerNpcCraftingUtil.canCraft(
                serverLevel,
                this.playerNpc.getInventory(),
                recipe.result().getItem(),
                true
        );
        if (!canCraftRecipe
                && PlayerNpcCraftingUtil.countSticks(this.playerNpc.getInventory()) < recipe.sticksNeeded()) {
            if (PlayerNpcCraftingUtil.tryCraftSticks(serverLevel, this.playerNpc.getInventory(), rawLogReserve)) {
                this.playCraftStep(serverLevel, "crafting planks into sticks");
                return;
            }
            this.finished = true;
            return;
        }

        if (!canCraftRecipe
                && PlayerNpcCraftingUtil.countLogs(this.playerNpc.getInventory()) > rawLogReserve) {
            if (PlayerNpcCraftingUtil.tryConvertOneLogToPlanks(this.playerNpc.getInventory(), rawLogReserve)) {
                this.playCraftStep(serverLevel, "crafting logs into planks");
                return;
            }
            this.finished = true;
            return;
        }

        if (!canCraftRecipe) {
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
        serverLevel.playSound(null, this.craftingTablePos == null ? this.playerNpc.blockPosition() : this.craftingTablePos, SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 0.6F, 1.2F);
    }

    private void interactWithCraftingTableOnce() {
        if (this.craftingTableInteracted) {
            return;
        }
        this.playerNpc.triggerMainHandUseAnimation();
        this.craftingTableInteracted = true;
    }

    private boolean canCraftUsefulGear(ServerLevel serverLevel) {
        if (!this.canCraftTool()) {
            return false;
        }
        return this.hasNearbyCraftingTable(serverLevel)
                || this.hasReusableTemporaryCraftingTable(serverLevel)
                || this.shouldPlaceCraftingTable(serverLevel);
    }

    private boolean canCraftTool() {
        ToolRecipe recipe = this.nextToolRecipe();
        return recipe != null && this.canProvideRecipeMaterials(recipe, 0, this.rawLogReserveForRecipe(recipe));
    }

    private boolean canCraftToolAfterPlacedTable() {
        int reservedPlanks = this.hasCarriedCraftingTable() ? 0 : 4;
        ToolRecipe recipe = this.nextToolRecipe();
        return recipe != null && this.canProvideRecipeMaterials(recipe, reservedPlanks, this.rawLogReserveForRecipe(recipe));
    }

    private boolean shouldPlaceCraftingTable(ServerLevel serverLevel) {
        boolean needsTerraformShovel = this.needsTerraformShovel(serverLevel);
        if (!this.needsBasicGear()
                || this.hasNearbyCraftingTable(serverLevel)
                || (this.isNearSavedHome(serverLevel) && !this.needsCriticalStarterTool() && !needsTerraformShovel)
                || this.hasReusableTemporaryCraftingTable(serverLevel)
                || !this.canCraftToolAfterPlacedTable()) {
            return false;
        }

        return (this.hasCarriedCraftingTable()
                || this.countCarriedCraftingTables() == 0 && PlayerNpcCraftingUtil.canCraftCraftingTable(this.playerNpc.getInventory()))
                && this.findCraftingTablePlacement(serverLevel) != null;
    }

    private boolean canPlanCraftingTablePlacement(ServerLevel serverLevel) {
        boolean needsTerraformShovel = this.needsTerraformShovel(serverLevel);
        return this.needsBasicGear()
                && (!this.isNearSavedHome(serverLevel) || this.needsCriticalStarterTool() || needsTerraformShovel)
                && this.canCraftToolAfterPlacedTable()
                && (this.hasCarriedCraftingTable()
                || this.countCarriedCraftingTables() == 0
                && PlayerNpcCraftingUtil.canCraftCraftingTable(this.playerNpc.getInventory()));
    }

    private boolean needsBasicGear() {
        return this.nextToolRecipe() != null;
    }

    private boolean needsCriticalStarterTool() {
        return !this.hasTool(AxeItem.class)
                || !this.hasTool(PickaxeItem.class)
                || !GatherStoneGoal.isFarmingSupportJob(this.playerNpc)
                && !this.hasTool(ShovelItem.class);
    }

    private boolean needsCriticalStarterToolForCooldown() {
        if (this.shouldLimitMiningProspectingGear()) {
            return !this.hasTool(PickaxeItem.class);
        }
        return this.needsCriticalStarterTool();
    }

    private ToolRecipe nextToolRecipe() {
        if (this.shouldLimitMiningProspectingGear()) {
            return this.nextMiningProspectingGearRecipe();
        }

        ToolRecipe stoneGatheringRecipe = this.nextStoneGatheringRecipe();
        if (stoneGatheringRecipe != null) {
            return stoneGatheringRecipe;
        }
        if (this.isActiveStoneGatheringPhase()) {
            return null;
        }

        ToolRecipe farmHoeRecipe = this.nextFarmHoeRecipe();
        if (farmHoeRecipe != null) {
            return farmHoeRecipe;
        }

        if (this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.isReadyFarmingCycle(serverLevel)) {
            return this.nextReadyFarmSupportRecipe(serverLevel);
        }

        ToolRecipe criticalRecipe = this.nextCriticalStarterRecipe();
        if (criticalRecipe != null) {
            return criticalRecipe;
        }
        if (this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.playerNpc.isDailyJobActive(PlayerNpcInterest.FARMING)
                && FarmAi.getPlan(this.playerNpc, serverLevel)
                .map(plan -> !plan.phase().isReady())
                .orElse(false)) {
            return null;
        }

        ToolRecipe fishingRodRecipe = this.nextFishingRodRecipe();
        if (fishingRodRecipe != null && this.shouldPrioritizeFishingRod()) {
            return fishingRodRecipe;
        }

        ToolRecipe terraformRecipe = this.nextTerraformShovelRecipe();
        if (terraformRecipe != null) {
            return terraformRecipe;
        }

        ToolRecipe materialUpgradeRecipe = this.nextMaterialUpgradeRecipe();
        if (materialUpgradeRecipe != null) {
            return materialUpgradeRecipe;
        }

        if (!this.hasTool(AxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.AXE);
        }
        if (!this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (!this.hasTool(SwordItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.SWORD);
        }
        if (!GatherStoneGoal.isFarmingSupportJob(this.playerNpc)
                && !this.hasTool(ShovelItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.SHOVEL);
        }
        return fishingRodRecipe;
    }

    private ToolRecipe nextMiningProspectingGearRecipe() {
        if (!this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (this.isMiningProspectingState()) {
            return null;
        }

        ToolRecipe pickaxeUpgradeRecipe = this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        if (pickaxeUpgradeRecipe != null && this.bestToolTier(ToolKind.PICKAXE).isBelow(pickaxeUpgradeRecipe.tier())) {
            return pickaxeUpgradeRecipe;
        }
        return null;
    }

    private ToolRecipe nextStoneGatheringRecipe() {
        if (!this.isActiveStoneGatheringPhase()) {
            return null;
        }
        if (!this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (!GatherStoneGoal.isFarmingSupportJob(this.playerNpc)
                && !this.hasTool(ShovelItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.SHOVEL);
        }
        return null;
    }

    private ToolRecipe nextTerraformShovelRecipe() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.needsTerraformShovel(serverLevel)) {
            return null;
        }

        return this.bestCraftableToolRecipe(ToolKind.SHOVEL);
    }

    private ToolRecipe nextFarmHoeRecipe() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !FarmAi.needsHoe(this.playerNpc, serverLevel)
                || this.hasTool(HoeItem.class)) {
            return null;
        }
        return this.bestCraftableToolRecipe(ToolKind.HOE);
    }

    private boolean isReadyFarmingCycle(ServerLevel serverLevel) {
        return FarmAi.isFarmingJobActive(this.playerNpc)
                && FarmAi.getPlan(this.playerNpc, serverLevel)
                .map(plan -> plan.phase().isReady())
                .orElse(false);
    }

    private ToolRecipe nextReadyFarmSupportRecipe(ServerLevel serverLevel) {
        if (this.emergencyPickaxeCraft && !this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (FarmAi.needsFarmLogs(this.playerNpc, serverLevel) && !this.hasTool(AxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.AXE);
        }
        if (FarmAi.needsFarmStone(this.playerNpc, serverLevel) && !this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        return null;
    }

    private ToolRecipe nextCriticalStarterRecipe() {
        if (this.emergencyPickaxeCraft && !this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (this.shouldPrioritizeMiningStarterPickaxe() && !this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (!this.hasTool(AxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.AXE);
        }
        if (!this.hasTool(PickaxeItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.PICKAXE);
        }
        if (!GatherStoneGoal.isFarmingSupportJob(this.playerNpc)
                && !this.hasTool(ShovelItem.class)) {
            return this.bestCraftableToolRecipe(ToolKind.SHOVEL);
        }
        return null;
    }

    private ToolRecipe nextMaterialUpgradeRecipe() {
        for (ToolKind kind : List.of(ToolKind.PICKAXE, ToolKind.AXE, ToolKind.SWORD, ToolKind.SHOVEL)) {
            ToolRecipe recipe = this.bestCraftableToolRecipe(kind);
            if (recipe != null && this.bestToolTier(kind).isBelow(recipe.tier())) {
                return recipe;
            }
        }
        return null;
    }

    private ToolRecipe bestCraftableToolRecipe(ToolKind kind) {
        ToolTier currentTier = this.bestToolTier(kind);
        boolean hasBetterPrimaryMaterial = false;
        for (ToolTier tier : List.of(ToolTier.DIAMOND, ToolTier.IRON, ToolTier.STONE, ToolTier.WOOD)) {
            ToolRecipe recipe = this.toolRecipe(kind, tier);
            if (!currentTier.isBelow(tier)) {
                continue;
            }

            if (tier != ToolTier.WOOD && this.hasPrimaryToolMaterial(recipe)) {
                hasBetterPrimaryMaterial = true;
            }

            if (this.canProvideRecipeMaterials(recipe, 0, this.rawLogReserveForRecipe(recipe))) {
                return recipe;
            }

            if (hasBetterPrimaryMaterial) {
                return null;
            }
        }
        return null;
    }

    private ToolRecipe toolRecipe(ToolKind kind, ToolTier tier) {
        return new ToolRecipe(PlayerNpcGearUtil.itemFor(kind, tier).getDefaultInstance(), kind, tier, kind.materialCost(), kind.stickCost());
    }

    private boolean tryCraftRecipe(ServerLevel serverLevel, ToolRecipe recipe) {
        boolean crafted = PlayerNpcCraftingUtil.craftItem(serverLevel, this.playerNpc.getInventory(), recipe.result().getItem(), true)
                .map(stack -> InventoryUtils.addItem(this.playerNpc, stack))
                .orElse(false);
        if (crafted) {
            this.playerNpc.equipBetterGearFromInventory();
        }
        return crafted;
    }

    private boolean hasTool(Class<?> toolClass) {
        return this.playerNpc.hasCarriedTool(toolClass);
    }

    private boolean shouldPrioritizeFishingRod() {
        return isFishingRodBootstrapActive(this.playerNpc);
    }

    private ToolRecipe nextFishingRodRecipe() {
        if (!this.hasTool(FishingRodItem.class)
                && PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.STRING)) >= 2) {
            return new ToolRecipe(Items.FISHING_ROD.getDefaultInstance(), null, ToolTier.NONE, 0, 3);
        }
        return null;
    }

    private static boolean isFishingRodBootstrapActive(PlayerNpcEntity playerNpc) {
        return playerNpc != null
                && playerNpc.isDailyJobActive(PlayerNpcInterest.FISHING)
                && !playerNpc.shouldPrioritizeLogGathering()
                && !playerNpc.shouldPrioritizeCobblestoneGathering()
                && !PlayerNpcFishingGoal.hasFishingRod(playerNpc)
                && PlayerNpcCraftingUtil.countItem(playerNpc.getInventory(), stack -> stack.is(Items.STRING)) >= 2;
    }

    private int countStone() {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE));
    }

    private int countToolMaterial(ToolTier tier) {
        return switch (tier) {
            case STONE -> this.countStone();
            case IRON -> PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.IRON_INGOT));
            case DIAMOND -> PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.DIAMOND));
            case WOOD, NONE, NETHERITE -> 0;
        };
    }

    private boolean hasPrimaryToolMaterial(ToolRecipe recipe) {
        return recipe.kind() != null
                && recipe.tier() != ToolTier.WOOD
                && recipe.tier() != ToolTier.NONE
                && this.countToolMaterial(recipe.tier()) >= recipe.materialNeeded();
    }

    private boolean canProvideRecipeMaterials(ToolRecipe recipe, int reservedPlanks, int rawLogReserve) {
        if (recipe.kind() == null || recipe.tier() == ToolTier.NONE || recipe.tier() == ToolTier.WOOD) {
            return this.canProvidePlanksAndSticks(recipe.materialNeeded(), recipe.sticksNeeded(), reservedPlanks, rawLogReserve);
        }

        if (recipe.tier() == ToolTier.STONE || recipe.tier() == ToolTier.IRON || recipe.tier() == ToolTier.DIAMOND) {
            return this.countToolMaterial(recipe.tier()) >= recipe.materialNeeded()
                    && this.canProvidePlanksAndSticks(0, recipe.sticksNeeded(), reservedPlanks, rawLogReserve);
        }

        return false;
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
        return this.needsCriticalStarterTool() || this.needsTerraformShovel() || this.needsFarmHoe()
                ? 0
                : this.playerNpc.getRawLogReserveTarget();
    }

    private int rawLogReserveForRecipe(ToolRecipe recipe) {
        return this.isCriticalStarterRecipe(recipe)
                || recipe.kind() == ToolKind.HOE && this.needsFarmHoe()
                || (this.isTerraformShovelRecipe(recipe) && this.needsTerraformShovel())
                ? 0
                : this.playerNpc.getRawLogReserveTarget();
    }

    private boolean isCriticalStarterRecipe(ToolRecipe recipe) {
        return recipe.kind() == ToolKind.AXE && !this.hasTool(AxeItem.class)
                || recipe.kind() == ToolKind.PICKAXE && !this.hasTool(PickaxeItem.class)
                || recipe.kind() == ToolKind.SHOVEL && !this.hasTool(ShovelItem.class)
                || recipe.kind() == ToolKind.HOE && this.needsFarmHoe();
    }

    private boolean isTerraformShovelRecipe(ToolRecipe recipe) {
        return recipe.kind() == ToolKind.SHOVEL;
    }

    private boolean needsFarmHoe() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && FarmAi.needsHoe(this.playerNpc, serverLevel);
    }

    private boolean shouldLimitMiningProspectingGear() {
        return GatherStoneGoal.isMiningJobActive(this.playerNpc)
                && !this.playerNpc.shouldPrioritizeLogGathering()
                && !this.playerNpc.shouldPrioritizeCobblestoneGathering();
    }

    private boolean isMiningProspectingState() {
        return "ai.player_npc.prospecting_ore".equals(this.playerNpc.getCurrentAiState())
                || "ai.player_npc.exploring_cave".equals(this.playerNpc.getCurrentAiState());
    }

    private boolean needsTerraformShovel() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && this.needsTerraformShovel(serverLevel);
    }

    private boolean needsTerraformShovel(ServerLevel serverLevel) {
        return TerraformBuildSiteGoal.needsShovelForPrep(this.playerNpc, serverLevel);
    }

    private boolean isActiveStoneGatheringPhase() {
        return this.playerNpc.level() instanceof ServerLevel serverLevel
                && GatherStoneGoal.isStoneSupplyPhaseActive(this.playerNpc, serverLevel);
    }

    private ToolTier bestToolTier(ToolKind kind) {
        ToolTier best = PlayerNpcGearUtil.bestToolTier(
                this.playerNpc.getMainHandItem(),
                this.playerNpc.getOffhandItem(),
                this.playerNpc.getInventory(),
                kind
        );
        ToolTier mainWeaponTier = PlayerNpcGearUtil.tierFor(this.playerNpc.getMainWeaponItem(), kind);
        if (best.isBelow(mainWeaponTier)) {
            best = mainWeaponTier;
        }
        ToolTier offWeaponTier = PlayerNpcGearUtil.tierFor(this.playerNpc.getOffWeaponItem(), kind);
        if (best.isBelow(offWeaponTier)) {
            best = offWeaponTier;
        }
        return best;
    }

    private boolean hasNearbyCraftingTable(ServerLevel serverLevel) {
        return this.findNearbyCraftingTableUse(serverLevel) != null;
    }

    private CraftingTableUse findNearbyCraftingTableUse(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-CRAFTING_TABLE_SCAN_RADIUS, -2, -CRAFTING_TABLE_SCAN_RADIUS),
                origin.offset(CRAFTING_TABLE_SCAN_RADIUS, 2, CRAFTING_TABLE_SCAN_RADIUS))) {
            if (serverLevel.hasChunkAt(pos)
                    && serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
                candidates.add(pos.immutable());
            }
        }

        candidates.sort(Comparator.comparingDouble(origin::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos stand = this.findCraftingStand(serverLevel, candidate);
            if (stand != null) {
                return new CraftingTableUse(candidate, stand);
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
            if (this.activationPlanning && this.activationStandPathChecksRemaining <= 0) {
                continue;
            }
            if (this.activationPlanning) {
                this.activationStandPathChecksRemaining--;
            }
            Path path = this.playerNpc.getNavigation().createPath(immutable, 0);
            if (path != null && path.canReach()) {
                return immutable;
            }
        }
        return null;
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        return PathNavigationAi.canStandAt(serverLevel, pos);
    }

    private boolean isAtCraftingStand() {
        if (this.craftingTablePos != null
                && this.distanceToTableSqr(this.playerNpc.blockPosition(), this.craftingTablePos) <= CRAFTING_TABLE_USE_DISTANCE_SQR + 1.0D) {
            return true;
        }

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
            if (this.playerNpc.distanceToSqr(
                    this.craftingStandPos.getX() + 0.5D,
                    this.playerNpc.getY(),
                    this.craftingStandPos.getZ() + 0.5D) <= DIRECT_CRAFTING_STEP_DISTANCE_SQR) {
                this.playerNpc.getNavigation().stop();
                this.playerNpc.getMoveControl().setWantedPosition(
                        this.craftingStandPos.getX() + 0.5D,
                        this.craftingStandPos.getY(),
                        this.craftingStandPos.getZ() + 0.5D,
                        1.0D);
                this.playerNpc.setCurrentAiDetail("stepping to crafting table");
                return true;
            }
            return false;
        }
        return this.playerNpc.getNavigation().moveTo(path, 1.0D);
    }

    private boolean moveToCraftingStandOnCadence() {
        if (this.playerNpc.tickCount < this.nextCraftingPathAttemptTick) {
            return true;
        }
        this.nextCraftingPathAttemptTick = this.playerNpc.tickCount + CRAFTING_REPATH_INTERVAL_TICKS;
        return this.moveToCraftingStand();
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

    private boolean hasReusableTemporaryCraftingTable(ServerLevel serverLevel) {
        return this.findReusableTemporaryCraftingTable(serverLevel) != null;
    }

    private CraftingTableUse findReusableTemporaryCraftingTable(ServerLevel serverLevel) {
        BlockPos pos = this.getTemporaryCraftingTablePos();
        if (pos == null
                || this.playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > TEMP_CRAFTING_TABLE_REUSE_DISTANCE_SQR
                || !serverLevel.hasChunkAt(pos)
                || !serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
            return null;
        }

        BlockPos stand = this.findCraftingStand(serverLevel, pos);
        return stand == null ? null : new CraftingTableUse(pos, stand);
    }

    private void clearUnusableTemporaryCraftingTable(ServerLevel serverLevel) {
        BlockPos pos = this.getTemporaryCraftingTablePos();
        if (pos == null) {
            return;
        }
        // An obstructed stand can make a nearby table temporarily unusable. Keep
        // ownership in that case so mining/path-clearing goals do not destroy it,
        // but release tables that are gone or genuinely outside the reuse area.
        if (this.playerNpc.distanceToSqr(
                pos.getX() + 0.5D,
                pos.getY() + 0.5D,
                pos.getZ() + 0.5D
        ) > TEMP_CRAFTING_TABLE_REUSE_DISTANCE_SQR
                || serverLevel.hasChunkAt(pos)
                && !serverLevel.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) {
            this.clearTemporaryCraftingTable();
        }
    }

    private BlockPos getTemporaryCraftingTablePos() {
        return getTemporaryCraftingTablePos(this.playerNpc);
    }

    private void clearTemporaryCraftingTable() {
        clearTemporaryCraftingTable(this.playerNpc);
    }

    private BlockPos findCraftingTablePlacement(ServerLevel serverLevel) {
        BlockPos origin = this.playerNpc.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        this.addCraftingTablePlacementCandidate(candidates, origin.relative(this.playerNpc.getDirection()));
        this.addCraftingTablePlacementCandidate(candidates, origin.relative(this.playerNpc.getDirection().getClockWise()));
        this.addCraftingTablePlacementCandidate(candidates, origin.relative(this.playerNpc.getDirection().getCounterClockWise()));
        this.addCraftingTablePlacementCandidate(candidates, origin.relative(this.playerNpc.getDirection().getOpposite()));

        for (int dy = -1; dy <= 1; dy++) {
            for (int radius = 1; radius <= CRAFTING_TABLE_PLACEMENT_RADIUS; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if ((dx == 0 && dz == 0) || Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        this.addCraftingTablePlacementCandidate(candidates, origin.offset(dx, dy, dz));
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(origin::distSqr));
        this.plannedPlacementStand = null;
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!this.canPlaceCraftingTableAt(serverLevel, immutable)) {
                continue;
            }
            BlockPos stand = this.findCraftingStand(serverLevel, immutable);
            if (stand != null) {
                this.plannedPlacementStand = stand.immutable();
                return immutable;
            }
        }
        return null;
    }

    private boolean shouldPrioritizeMiningStarterPickaxe() {
        return this.playerNpc.level() instanceof ServerLevel
                && this.playerNpc.isDailyJobActive(PlayerNpcInterest.MINING)
                && !this.playerNpc.hasInterest(PlayerNpcInterest.BUILDING)
                && !this.playerNpc.shouldPrioritizeLogGathering();
    }

    private void addCraftingTablePlacementCandidate(List<BlockPos> candidates, BlockPos candidate) {
        BlockPos immutable = candidate.immutable();
        if (!candidates.contains(immutable)) {
            candidates.add(immutable);
        }
    }

    private boolean canPlaceCraftingTableAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && PlayerNpcHomeUtil.isReplaceableForNpcBuild(serverLevel, pos)
                && !PlayerNpcHomeUtil.isInsideBuildFootprint(this.playerNpc, pos)
                && !FarmAi.isProtectedFarmBlock(this.playerNpc, pos)
                && serverLevel.getFluidState(pos).isEmpty()
                && serverLevel.getBlockState(pos.below()).isSolidRender(serverLevel, pos.below())
                && this.placingBlockAi.canPlaceWithoutClipping(serverLevel, pos, Blocks.CRAFTING_TABLE.defaultBlockState());
    }

    private void resetPlan() {
        this.craftingTablePos = null;
        this.craftingStandPos = null;
        this.actionDelayTicks = 0;
        this.finished = false;
        this.craftedTool = false;
        this.emergencyPickaxeCraft = false;
        this.craftingTableInteracted = false;
        this.activationStandPathChecksRemaining = 0;
        this.activationPlanning = false;
        this.plannedPlacementStand = null;
    }

    private record ToolRecipe(ItemStack result, ToolKind kind, ToolTier tier, int materialNeeded, int sticksNeeded) {}

    private record CraftingTableUse(BlockPos tablePos, BlockPos standPos) {}
}
