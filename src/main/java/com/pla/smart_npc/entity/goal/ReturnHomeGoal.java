package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.ReturnPositionAi;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBuildMaterialUtil;
import com.pla.smart_npc.util.PlayerNpcCraftingUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

import java.util.EnumSet;
import java.util.Optional;

public class ReturnHomeGoal extends Goal {
    private static final double MIN_DISTANCE_SQR = 28.0D * 28.0D;
    private static final double STOP_DISTANCE_SQR = 4.0D * 4.0D;
    private static final double UTILITY_RETURN_DISTANCE_SQR = 48.0D * 48.0D;
    private static final double POST_STONE_RETURN_DISTANCE_SQR = 96.0D * 96.0D;
    private static final int MAX_RETURN_TICKS = 20 * 20;
    private static final int HOME_WORK_AREA_MARGIN = 4;
    private static final int HOME_SURFACE_ESCAPE_TICKS = 20 * 10;
    private static final int HOME_SURFACE_ESCAPE_EXTRA_BLOCKS = 6;

    private final PlayerNpcEntity playerNpc;
    private final ReturnPositionAi returnPositionAi;
    private final CanUseThrottle canUseThrottle = new CanUseThrottle();
    private PlayerNpcHomeUtil.HomeArea homeArea;
    private BlockPos homeCenter;
    private int returnTicks;
    private boolean utilityReturn;
    private boolean buildReturn;
    private boolean shelterReturn;
    private boolean completedStoneTripReturn;
    private boolean explorationRecoveryReturn;
    private boolean homeSurfaceRecoveryReturn;

    public ReturnHomeGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.returnPositionAi = new ReturnPositionAi(playerNpc, speed);
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    public static boolean shouldSuppressExplorationForHome(PlayerNpcEntity playerNpc, ServerLevel serverLevel) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return false;
        }
        if (playerNpc.hasExplorationReturnHomeRequest()) {
            return true;
        }
        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        if (needsHomeSurfaceRecovery(playerNpc, homeArea)) {
            return true;
        }
        if (isInsideHomeWorkArea(playerNpc, homeArea)) {
            return false;
        }
        if (shouldReturnAfterCompletedStoneTrip(playerNpc, serverLevel, homeArea)) {
            return true;
        }
        if (playerNpc.shouldPrioritizeLogGathering() || playerNpc.shouldPrioritizeCobblestoneGathering()) {
            return serverLevel.isNight() || serverLevel.isThundering();
        }
        return serverLevel.isNight()
                || serverLevel.isThundering()
                || BuildHouseGoal.hasReadyHomeBuildWork(playerNpc, serverLevel)
                || PlayerNpcBuildMaterialUtil.needsStoneSmelting(serverLevel, playerNpc)
                || PlayerNpcBuildMaterialUtil.needsTorchCharcoalSmelting(serverLevel, playerNpc);
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || this.playerNpc.isHealing()
                || this.playerNpc.getTarget() != null) {
            return false;
        }
        if (!this.canUseThrottle.canCheck(this.playerNpc)) {
            return false;
        }

        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this.playerNpc);
        if (home.isEmpty()) {
            return false;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        this.homeArea = homeArea;
        this.homeCenter = homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
        double distanceSqr = this.playerNpc.distanceToSqr(this.homeCenter.getX() + 0.5D, this.homeCenter.getY(), this.homeCenter.getZ() + 0.5D);
        boolean inventoryHalfFull = this.inventoryMoreThanHalfFull();
        boolean inventoryMostlyFull = this.inventoryMostlyFull();
        this.shelterReturn = this.shouldShelterAtHome(serverLevel);
        this.buildReturn = BuildHouseGoal.hasReadyHomeBuildWork(this.playerNpc, serverLevel);
        this.completedStoneTripReturn = shouldReturnAfterCompletedStoneTrip(this.playerNpc, serverLevel, homeArea);
        this.explorationRecoveryReturn = this.playerNpc.hasExplorationReturnHomeRequest();
        this.homeSurfaceRecoveryReturn = needsHomeSurfaceRecovery(this.playerNpc, homeArea);
        if (this.homeSurfaceRecoveryReturn) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            return true;
        }
        if (this.explorationRecoveryReturn) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.completedStoneTripReturn = false;
            if (this.hasReachedExplorationRecoveryHome(homeArea)) {
                this.playerNpc.clearExplorationReturnHomeRequest();
                return false;
            }
            return true;
        }
        if (this.shelterReturn && distanceSqr > STOP_DISTANCE_SQR && !this.isInsideHomeWorkArea(homeArea)) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return true;
        }

        if (this.completedStoneTripReturn && !this.isInsideHomeWorkArea(homeArea)) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return true;
        }

        if (!this.shelterReturn
                && !this.completedStoneTripReturn
                && this.needsBuildMaterialReserves()
                && !inventoryMostlyFull) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return false;
        }

        if (this.shouldDeferUtilityReturnForBuildMaterialGathering(serverLevel, inventoryMostlyFull)) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return false;
        }

        this.utilityReturn = this.hasHomeUtilityWork(serverLevel, homeArea);
        if (this.buildReturn
                && !this.isInsideHomeWorkArea(homeArea)) {
            return true;
        }
        if (!this.buildReturn && this.utilityReturn && distanceSqr > STOP_DISTANCE_SQR) {
            return true;
        }

        if (this.playerNpc.getReturnHomeCooldown() > 0) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return false;
        }

        if (distanceSqr < MIN_DISTANCE_SQR) {
            this.utilityReturn = false;
            this.buildReturn = false;
            this.shelterReturn = false;
            this.completedStoneTripReturn = false;
            this.explorationRecoveryReturn = false;
            this.homeSurfaceRecoveryReturn = false;
            return false;
        }

        this.utilityReturn = false;
        this.buildReturn = false;
        this.shelterReturn = false;
        this.completedStoneTripReturn = false;
        this.explorationRecoveryReturn = false;
        this.homeSurfaceRecoveryReturn = false;
        boolean randomReturn = this.playerNpc.getRandom().nextFloat() < 0.35F;
        return inventoryHalfFull || randomReturn;
    }

    @Override
    public boolean canContinueToUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        if (this.shelterReturn && !this.shouldShelterAtHome(serverLevel)) {
            return false;
        }
        if (!this.shelterReturn
                && !this.completedStoneTripReturn
                && !this.explorationRecoveryReturn
                && !this.homeSurfaceRecoveryReturn
                && this.needsBuildMaterialReserves()
                && !this.inventoryMostlyFull()) {
            return false;
        }
        if ((this.buildReturn || this.completedStoneTripReturn) && this.hasReachedHomeWorkAreaSurface(this.homeArea)) {
            return false;
        }
        if (this.explorationRecoveryReturn && this.hasReachedExplorationRecoveryHome(this.homeArea)) {
            return false;
        }
        if (this.homeSurfaceRecoveryReturn && this.hasReachedHomeWorkAreaSurface(this.homeArea)) {
            return false;
        }

        return this.homeCenter != null
                && this.returnTicks > 0
                && this.playerNpc.isAlive()
                && this.playerNpc.getTarget() == null
                && !this.playerNpc.isNoAi()
                && !this.playerNpc.isPassenger()
                && !this.playerNpc.isHealing()
                && this.playerNpc.distanceToSqr(this.homeCenter.getX() + 0.5D, this.homeCenter.getY(), this.homeCenter.getZ() + 0.5D) > STOP_DISTANCE_SQR;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.returnTicks = MAX_RETURN_TICKS;
        this.playerNpc.setCurrentAiState("ai.player_npc.returning_home");
        if (this.homeSurfaceRecoveryReturn && this.homeCenter != null) {
            this.requestHomeSurfaceEscape();
        }
        if (this.homeCenter != null) {
            this.returnPositionAi.start(this.homeCenter);
        }
        this.updateDetail();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        this.returnTicks--;
        if (this.homeCenter != null) {
            this.playerNpc.getLookControl().setLookAt(this.homeCenter.getX() + 0.5D, this.homeCenter.getY(), this.homeCenter.getZ() + 0.5D, 40.0F, 40.0F);
            this.returnPositionAi.tick(
                    serverLevel,
                    this.homeCenter,
                    this::isProtectedHomeBlock,
                    this.moveDetail(),
                    "clearing return path");
            this.updateDetail();
        }
    }

    @Override
    public void stop() {
        boolean arrivedAtHome = this.homeCenter != null
                && (this.buildReturn
                || this.completedStoneTripReturn
                || this.homeSurfaceRecoveryReturn
                ? this.hasReachedHomeWorkAreaSurface(this.homeArea)
                : this.hasReachedHomeCenter());
        if (!this.playerNpc.level().isClientSide) {
            int cooldown = !arrivedAtHome
                    ? 0
                    : this.utilityReturn
                    || this.buildReturn
                    || this.shelterReturn
                    || this.completedStoneTripReturn
                    || this.explorationRecoveryReturn
                    || this.homeSurfaceRecoveryReturn
                    ? 20 * 12 + this.playerNpc.getRandom().nextInt(20 * 12)
                    : 20 * 60 + this.playerNpc.getRandom().nextInt(20 * 60);
            this.playerNpc.setReturnHomeCooldown(cooldown);
            if (!arrivedAtHome && this.homeCenter != null && this.playerNpc.blockPosition().getY() < this.homeCenter.getY() - 1) {
                if (this.homeSurfaceRecoveryReturn) {
                    this.requestHomeSurfaceEscape();
                } else {
                    this.playerNpc.requestUpwardEscapeTo(this.homeCenter, 20 * 8, 10);
                }
            }
            if (this.explorationRecoveryReturn && arrivedAtHome) {
                this.playerNpc.clearExplorationReturnHomeRequest();
                this.playerNpc.setGatherCooldown(20 * 2);
            }
            if ((this.buildReturn || this.completedStoneTripReturn) && arrivedAtHome) {
                this.playerNpc.setBuildHouseCooldown(0);
                this.playerNpc.setManageHomeCooldown(0);
                if (this.buildReturn) {
                    this.playerNpc.setGatherCooldown(20 * 8);
                }
            }
        }
        this.returnPositionAi.stop();
        this.homeArea = null;
        this.homeCenter = null;
        this.returnTicks = 0;
        this.utilityReturn = false;
        this.buildReturn = false;
        this.shelterReturn = false;
        this.completedStoneTripReturn = false;
        this.explorationRecoveryReturn = false;
        this.homeSurfaceRecoveryReturn = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        this.playerNpc.setCurrentAiDetail("");
    }

    private boolean isInsideHomeWorkArea(PlayerNpcHomeUtil.HomeArea homeArea) {
        if (homeArea == null) {
            return false;
        }

        return isInsideHomeWorkArea(this.playerNpc, homeArea);
    }

    private static boolean isInsideHomeWorkArea(PlayerNpcEntity playerNpc, PlayerNpcHomeUtil.HomeArea homeArea) {
        BlockPos pos = playerNpc.blockPosition();
        return pos.getX() >= homeArea.origin().getX() - HOME_WORK_AREA_MARGIN
                && pos.getX() < homeArea.origin().getX() + homeArea.width() + HOME_WORK_AREA_MARGIN
                && pos.getZ() >= homeArea.origin().getZ() - HOME_WORK_AREA_MARGIN
                && pos.getZ() < homeArea.origin().getZ() + homeArea.depth() + HOME_WORK_AREA_MARGIN
                && pos.getY() >= homeArea.origin().getY()
                && pos.getY() <= homeArea.origin().getY() + 8;
    }

    private static boolean needsHomeSurfaceRecovery(PlayerNpcEntity playerNpc, PlayerNpcHomeUtil.HomeArea homeArea) {
        if (homeArea == null) {
            return false;
        }

        BlockPos pos = playerNpc.blockPosition();
        return isInsideHomeWorkFootprint(pos, homeArea)
                && pos.getY() < homeArea.origin().getY();
    }

    private static boolean isInsideHomeWorkFootprint(BlockPos pos, PlayerNpcHomeUtil.HomeArea homeArea) {
        return pos.getX() >= homeArea.origin().getX() - HOME_WORK_AREA_MARGIN
                && pos.getX() < homeArea.origin().getX() + homeArea.width() + HOME_WORK_AREA_MARGIN
                && pos.getZ() >= homeArea.origin().getZ() - HOME_WORK_AREA_MARGIN
                && pos.getZ() < homeArea.origin().getZ() + homeArea.depth() + HOME_WORK_AREA_MARGIN;
    }

    private boolean hasReachedExplorationRecoveryHome(PlayerNpcHomeUtil.HomeArea homeArea) {
        return this.hasReachedHomeWorkAreaSurface(homeArea) && this.hasReachedHomeCenter();
    }

    private boolean hasReachedHomeWorkAreaSurface(PlayerNpcHomeUtil.HomeArea homeArea) {
        return homeArea != null
                && this.isInsideHomeWorkArea(homeArea)
                && !needsHomeSurfaceRecovery(this.playerNpc, homeArea);
    }

    private boolean hasReachedHomeCenter() {
        return this.homeCenter != null
                && this.playerNpc.distanceToSqr(
                this.homeCenter.getX() + 0.5D,
                this.homeCenter.getY(),
                this.homeCenter.getZ() + 0.5D
        ) <= STOP_DISTANCE_SQR;
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        return this.homeArea != null
                && !this.homeSurfaceRecoveryReturn
                && PlayerNpcHomeUtil.isInside(this.homeArea, pos);
    }

    private void requestHomeSurfaceEscape() {
        BlockPos feet = this.playerNpc.blockPosition();
        int maxPillarBlocks = Math.max(1, this.homeCenter.getY() - feet.getY() + HOME_SURFACE_ESCAPE_EXTRA_BLOCKS);
        this.playerNpc.requestForcedUpwardEscapeTo(this.homeCenter, HOME_SURFACE_ESCAPE_TICKS, maxPillarBlocks);
    }

    private void updateDetail() {
        this.playerNpc.setCurrentAiDetail(this.returnPositionAi.detail(this.moveDetail()));
    }

    private String moveDetail() {
        if (this.shelterReturn) {
            return "returning to home shelter";
        }
        if (this.buildReturn) {
            return "returning to build site";
        }
        if (this.completedStoneTripReturn) {
            return "returning from dig site";
        }
        if (this.explorationRecoveryReturn) {
            return "returning after failed exploration";
        }
        if (this.homeSurfaceRecoveryReturn) {
            return "returning to surface";
        }
        if (this.utilityReturn) {
            return "returning to home utility";
        }
        return "returning home";
    }

    private boolean shouldShelterAtHome(ServerLevel serverLevel) {
        return serverLevel.isNight() || serverLevel.isThundering();
    }

    private boolean inventoryMoreThanHalfFull() {
        int used = 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            if (!this.playerNpc.getInventory().getItem(i).isEmpty()) {
                used++;
            }
        }
        return used > this.playerNpc.getInventory().getContainerSize() / 2;
    }

    private boolean inventoryMostlyFull() {
        int freeSlots = 0;
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            if (this.playerNpc.getInventory().getItem(i).isEmpty()) {
                freeSlots++;
            }
        }
        return freeSlots <= 2;
    }

    private boolean needsBuildMaterialReserves() {
        return this.playerNpc.shouldPrioritizeLogGathering()
                || this.playerNpc.shouldPrioritizeCobblestoneGathering();
    }

    private boolean shouldDeferUtilityReturnForBuildMaterialGathering(ServerLevel serverLevel, boolean inventoryMostlyFull) {
        return !inventoryMostlyFull
                && !this.shouldShelterAtHome(serverLevel)
                && (PlayerNpcBuildMaterialUtil.needsLogsForCurrentBuild(serverLevel, this.playerNpc)
                || PlayerNpcBuildMaterialUtil.needsStoneForCurrentBuild(serverLevel, this.playerNpc)
                || GatherMissingBuildMaterialGoal.needsMissingBuildMaterial(this.playerNpc, serverLevel));
    }

    private static boolean shouldReturnAfterCompletedStoneTrip(
            PlayerNpcEntity playerNpc,
            ServerLevel serverLevel,
            PlayerNpcHomeUtil.HomeArea homeArea
    ) {
        if (playerNpc.shouldPrioritizeCobblestoneGathering()
                || isInsideHomeWorkArea(playerNpc, homeArea)) {
            return false;
        }

        BlockPos feet = playerNpc.blockPosition();
        int belowHomeBlocks = homeArea.origin().getY() - feet.getY();
        if (belowHomeBlocks < 3) {
            return false;
        }

        BlockPos homeCenter = homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
        if (playerNpc.distanceToSqr(homeCenter.getX() + 0.5D, homeCenter.getY(), homeCenter.getZ() + 0.5D) > POST_STONE_RETURN_DISTANCE_SQR) {
            return false;
        }

        return !serverLevel.canSeeSky(feet.above()) || belowHomeBlocks >= 5;
    }



    private boolean hasHomeUtilityWork(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        return this.hasStorageWork(serverLevel, homeArea)
                || this.hasImportantHomeCraftingWork(serverLevel, homeArea)
                || this.hasCookingWork(serverLevel, homeArea)
                || this.hasSleepWork(serverLevel, homeArea);
    }

    private boolean hasStorageWork(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        return this.inventoryMostlyFull()
                && (this.findBlock(serverLevel, homeArea, Blocks.CHEST) != null
                || InventoryUtils.hasItem(this.playerNpc, Items.CHEST)
                || PlayerNpcCraftingUtil.canCraftChest(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()));
    }

    private boolean hasImportantHomeCraftingWork(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        if (!this.canUseOrCreateHomeCraftingTable(serverLevel, homeArea)) {
            return false;
        }

        return this.canCraftMetalGear()
                || this.canCraftShield();
    }

    private boolean canUseOrCreateHomeCraftingTable(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        return this.findBlock(serverLevel, homeArea, Blocks.CRAFTING_TABLE) != null
                || InventoryUtils.hasItem(this.playerNpc, Items.CRAFTING_TABLE)
                || PlayerNpcCraftingUtil.canCraftCraftingTable(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget());
    }

    private boolean hasCookingWork(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        BlockPos furnacePos = this.findBlock(serverLevel, homeArea, Blocks.FURNACE);
        if (furnacePos == null) {
            return (InventoryUtils.hasItem(this.playerNpc, Items.FURNACE)
                    || PlayerNpcCraftingUtil.canCraftFurnace(this.playerNpc.getInventory()))
                    && (this.hasCookableFood()
                    || this.hasSmeltableMaterial(serverLevel)
                    || PlayerNpcBuildMaterialUtil.needsStoneSmelting(serverLevel, this.playerNpc)
                    || PlayerNpcBuildMaterialUtil.needsTorchCharcoalSmelting(serverLevel, this.playerNpc));
        }

        return (this.hasCookableFood()
                || this.hasSmeltableMaterial(serverLevel)
                || PlayerNpcBuildMaterialUtil.needsStoneSmelting(serverLevel, this.playerNpc)
                || PlayerNpcBuildMaterialUtil.needsTorchCharcoalSmelting(serverLevel, this.playerNpc)) && this.hasFuel()
                || serverLevel.getBlockEntity(furnacePos) instanceof FurnaceBlockEntity furnace
                && !furnace.getItem(2).isEmpty();
    }

    private boolean hasSleepWork(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        return serverLevel.isNight()
                && this.playerNpc.getSleepCooldown() <= 0
                && this.findBed(serverLevel, homeArea) != null;
    }

    private boolean canCraftMetalGear() {
        return this.canCraftTool(Items.DIAMOND_PICKAXE, Items.DIAMOND, 3, 2)
                || this.canCraftTool(Items.DIAMOND_SWORD, Items.DIAMOND, 2, 1)
                || this.canCraftTool(Items.DIAMOND_AXE, Items.DIAMOND, 3, 2)
                || this.canCraftTool(Items.DIAMOND_SHOVEL, Items.DIAMOND, 1, 2)
                || this.canCraftArmor(EquipmentSlot.HEAD, Items.DIAMOND_HELMET, Items.DIAMOND, 5)
                || this.canCraftArmor(EquipmentSlot.CHEST, Items.DIAMOND_CHESTPLATE, Items.DIAMOND, 8)
                || this.canCraftArmor(EquipmentSlot.LEGS, Items.DIAMOND_LEGGINGS, Items.DIAMOND, 7)
                || this.canCraftArmor(EquipmentSlot.FEET, Items.DIAMOND_BOOTS, Items.DIAMOND, 4)
                || this.canCraftTool(Items.IRON_PICKAXE, Items.IRON_INGOT, 3, 2)
                || this.canCraftTool(Items.IRON_AXE, Items.IRON_INGOT, 3, 2)
                || this.canCraftTool(Items.IRON_SWORD, Items.IRON_INGOT, 2, 1)
                || this.canCraftTool(Items.IRON_SHOVEL, Items.IRON_INGOT, 1, 2)
                || this.canCraftArmor(EquipmentSlot.HEAD, Items.IRON_HELMET, Items.IRON_INGOT, 5)
                || this.canCraftArmor(EquipmentSlot.CHEST, Items.IRON_CHESTPLATE, Items.IRON_INGOT, 8)
                || this.canCraftArmor(EquipmentSlot.LEGS, Items.IRON_LEGGINGS, Items.IRON_INGOT, 7)
                || this.canCraftArmor(EquipmentSlot.FEET, Items.IRON_BOOTS, Items.IRON_INGOT, 4);
    }

    private boolean canCraftTool(ItemLike result, ItemLike material, int materialNeeded, int sticksNeeded) {
        return !this.hasItem(result)
                && this.countMaterial(material) >= materialNeeded
                && PlayerNpcCraftingUtil.canProvidePlanksAndSticks(this.playerNpc.getInventory(), 0, sticksNeeded, this.playerNpc.getRawLogReserveTarget());
    }

    private boolean canCraftArmor(EquipmentSlot slot, ItemLike result, ItemLike material, int materialNeeded) {
        return !this.hasEquippedOrStoredItem(slot, result)
                && this.countMaterial(material) >= materialNeeded;
    }

    private boolean hasEquippedOrStoredItem(EquipmentSlot slot, ItemLike itemLike) {
        return this.playerNpc.getItemBySlot(slot).is(itemLike.asItem())
                || this.hasItem(itemLike);
    }

    private int countMaterial(ItemLike material) {
        return PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(material.asItem()));
    }

    private boolean canCraftShield() {
        return !this.hasShield()
                && PlayerNpcCraftingUtil.countPlankEquivalent(this.playerNpc.getInventory(), this.playerNpc.getRawLogReserveTarget()) >= 6
                && PlayerNpcCraftingUtil.countItem(this.playerNpc.getInventory(), stack -> stack.is(Items.IRON_INGOT)) >= 1;
    }

    private boolean hasItem(net.minecraft.world.level.ItemLike itemLike) {
        return this.playerNpc.getMainHandItem().is(itemLike.asItem())
                || this.playerNpc.getOffhandItem().is(itemLike.asItem())
                || InventoryUtils.hasItem(this.playerNpc, itemLike);
    }

    private boolean hasShield() {
        return this.playerNpc.getOffhandItem().getItem() instanceof ShieldItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof ShieldItem);
    }

    private boolean hasCookableFood() {
        return InventoryUtils.hasItem(this.playerNpc, this::isCookableFood);
    }

    private boolean hasSmeltableMaterial(ServerLevel serverLevel) {
        return InventoryUtils.hasItem(this.playerNpc, stack -> this.isSmeltableMaterial(stack, serverLevel));
    }

    private boolean hasFuel() {
        return InventoryUtils.hasItem(this.playerNpc, this::isFuel);
    }

    private boolean isCookableFood(ItemStack stack) {
        return stack.is(Items.BEEF)
                || stack.is(Items.PORKCHOP)
                || stack.is(Items.CHICKEN)
                || stack.is(Items.MUTTON)
                || stack.is(Items.COD)
                || stack.is(Items.SALMON)
                || stack.is(Items.POTATO);
    }

    private boolean isSmeltableMaterial(ItemStack stack, ServerLevel serverLevel) {
        return PlayerNpcBuildMaterialUtil.isGlassSmeltingInput(stack)
                && PlayerNpcBuildMaterialUtil.needsGlassSmelting(serverLevel, this.playerNpc)
                || stack.is(Items.RAW_IRON)
                || stack.is(Items.RAW_COPPER)
                || stack.is(Items.RAW_GOLD)
                || stack.is(Items.IRON_ORE)
                || stack.is(Items.DEEPSLATE_IRON_ORE)
                || stack.is(Items.COPPER_ORE)
                || stack.is(Items.DEEPSLATE_COPPER_ORE)
                || stack.is(Items.GOLD_ORE)
                || stack.is(Items.DEEPSLATE_GOLD_ORE);
    }

    private boolean isFuel(ItemStack stack) {
        return !stack.isEmpty() && AbstractFurnaceBlockEntity.isFuel(stack);
    }

    private BlockPos findBlock(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea, Block block) {
        for (BlockPos pos : BlockPos.betweenClosed(
                homeArea.origin(),
                homeArea.origin().offset(homeArea.width() - 1, 3, homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).is(block)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private BlockPos findBed(ServerLevel serverLevel, PlayerNpcHomeUtil.HomeArea homeArea) {
        for (BlockPos pos : BlockPos.betweenClosed(
                homeArea.origin(),
                homeArea.origin().offset(homeArea.width() - 1, 3, homeArea.depth() - 1))) {
            if (serverLevel.getBlockState(pos).getBlock() instanceof BedBlock) {
                return pos.immutable();
            }
        }
        return null;
    }
}
