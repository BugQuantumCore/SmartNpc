package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import com.pla.player_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.player_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class PickupNearbyItemGoal extends Goal {
    private static final String AI_STATE = "ai.player_npc.collecting_item";
    private static final double SEARCH_RADIUS = 8.0D;
    private static final double SEARCH_VERTICAL_RADIUS = 5.0D;
    private static final double ANIMAL_LOOT_SEARCH_RADIUS = 10.0D;
    private static final double ANIMAL_LOOT_SEARCH_VERTICAL_RADIUS = 4.0D;
    private static final double PICKUP_DISTANCE_SQR = 1.45D * 1.45D;
    private static final int MAX_PICKUP_TICKS = 20 * 12;
    private static final int FAILED_PICKUP_COOLDOWN_TICKS = 20 * 8;
    private static final int REPATH_INTERVAL_TICKS = 10;
    private static final int MAX_FAILED_PATH_TICKS = 20 * 5;
    private static final int MAX_CLOSE_PICKUP_WAIT_TICKS = 24;
    private static final int MAX_OBSTRUCTION_BREAK_TICKS = 20 * 4;
    private static final double OBSTRUCTION_BREAK_DISTANCE_SQR = 3.2D * 3.2D;
    private static final double ACTIVE_APPROACH_HORIZONTAL_RANGE_SQR = 4.5D * 4.5D;
    private static final double ACTIVE_APPROACH_VERTICAL_RANGE = 3.0D;
    private static final double ACTIVE_APPROACH_PUSH_SPEED = 0.22D;
    private static final double ACTIVE_APPROACH_JUMP_Y = 0.42D;
    private static final int ACTIVE_APPROACH_JUMP_COOLDOWN_TICKS = 10;
    private static final double HIGH_ITEM_VERTICAL_GAP = 1.2D;
    private static final double PICKUP_PILLAR_BASE_REACHED_SQR = 1.2D * 1.2D;
    private static final int PICKUP_PILLAR_SEARCH_RADIUS = 2;
    private static final int PICKUP_PILLAR_PLACE_DELAY_TICKS = 6;
    private static final int PICKUP_PILLAR_MAX_PLACE_WAIT_TICKS = 32;
    private static final int PICKUP_PILLAR_FORCE_PLACE_TICKS = 10;
    private static final double PICKUP_PILLAR_PLACE_CLEARANCE_Y = 0.95D;
    private static final double PICKUP_PILLAR_FALLBACK_PLACE_CLEARANCE_Y = 0.78D;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private final Set<BlockPos> skippedObstructions = new HashSet<>();
    private ItemEntity targetItem;
    private BlockPos prioritySearchCenter;
    private BlockPos pathObstructionPos;
    private BlockPos pickupPillarBasePos;
    private BlockPos pickupPillarPlacePos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int pickupTicks;
    private int repathTicks;
    private int failedPathTicks;
    private int closePickupWaitTicks;
    private int obstructionMineTicks;
    private int pickupPillarPlaceDelayTicks;
    private int pickupPillarPlaceWaitTicks;
    private int activeApproachTicks;
    private int activeApproachJumpCooldown;
    private int giveUpCooldownTicks;
    private boolean usingTemporaryTool;

    public PickupNearbyItemGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = speed;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (giveUpCooldownTicks > 0) {
            giveUpCooldownTicks--;
            return false;
        }

        if (!canCollectRightNow()) {
            return false;
        }

        boolean hasAnimalLootPriority = playerNpc.hasAnimalLootPriority();
        prioritySearchCenter = hasAnimalLootPriority ? playerNpc.getAnimalLootPriorityPos() : null;

        targetItem = findTargetItem();
        if (targetItem == null && prioritySearchCenter != null) {
            playerNpc.clearAnimalLootPriority();
            prioritySearchCenter = null;
        }
        return targetItem != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canCollectRightNow()
                && targetItem != null
                && isCollectable(targetItem)
                && canAccept(targetItem.getItem())
                && pickupTicks < MAX_PICKUP_TICKS
                && failedPathTicks < MAX_FAILED_PATH_TICKS;
    }

    @Override
    public void start() {
        pickupTicks = 0;
        repathTicks = 0;
        failedPathTicks = 0;
        closePickupWaitTicks = 0;
        obstructionMineTicks = 0;
        activeApproachTicks = 0;
        activeApproachJumpCooldown = 0;
        pathObstructionPos = null;
        clearPickupPillar();
        previousMainHand = ItemStack.EMPTY;
        skippedObstructions.clear();
        usingTemporaryTool = false;
        prioritySearchCenter = playerNpc.getAnimalLootPriorityPos();
        playerNpc.setCurrentAiState(AI_STATE);
        updateDetail();
        moveToTarget();
    }

    @Override
    public void tick() {
        if (targetItem == null || !targetItem.isAlive() || targetItem.getItem().isEmpty()) {
            targetItem = findTargetItem();
            if (targetItem == null) {
                playerNpc.clearAnimalLootPriority();
                prioritySearchCenter = null;
                failedPathTicks = MAX_FAILED_PATH_TICKS;
                playerNpc.getNavigation().stop();
                return;
            }
            clearPickupPillar();
            activeApproachTicks = 0;
        }

        pickupTicks++;
        if (activeApproachJumpCooldown > 0) {
            activeApproachJumpCooldown--;
        }
        updateDetail();
        playerNpc.getLookControl().setLookAt(
                targetItem.getX(),
                targetItem.getY() + targetItem.getBbHeight() * 0.5D,
                targetItem.getZ(),
                30.0F,
                30.0F
        );

        double distanceSqr = playerNpc.distanceToSqr(targetItem);
        if (distanceSqr <= PICKUP_DISTANCE_SQR) {
            playerNpc.getNavigation().stop();
            if (playerNpc.tryPickupItemEntity(targetItem)) {
                closePickupWaitTicks = 0;
                activeApproachTicks = 0;
                if (targetItem == null || !targetItem.isAlive() || targetItem.getItem().isEmpty()) {
                    targetItem = findTargetItem();
                    if (targetItem == null) {
                        playerNpc.clearAnimalLootPriority();
                    }
                }
            } else {
                closePickupWaitTicks++;
                if (playerNpc.level() instanceof ServerLevel serverLevel && tryActivePickupApproach(serverLevel)) {
                    return;
                }
                if (!targetItem.hasPickUpDelay() || closePickupWaitTicks >= MAX_CLOSE_PICKUP_WAIT_TICKS) {
                    targetItem = null;
                    if (prioritySearchCenter == null) {
                        failedPathTicks = MAX_FAILED_PATH_TICKS;
                    }
                }
            }
            return;
        }

        closePickupWaitTicks = 0;
        if (playerNpc.level() instanceof ServerLevel serverLevel && tickPickupPillar(serverLevel)) {
            return;
        }
        if (tickPathObstruction()) {
            return;
        }
        if (playerNpc.level() instanceof ServerLevel serverLevel && tryActivePickupApproach(serverLevel)) {
            return;
        }
        if (repathTicks-- <= 0 || playerNpc.getNavigation().isDone() || playerNpc.getNavigation().isStuck()) {
            repathTicks = REPATH_INTERVAL_TICKS;
            moveToTarget();
        }
    }

    @Override
    public void stop() {
        boolean gaveUp = pickupTicks >= MAX_PICKUP_TICKS || failedPathTicks >= MAX_FAILED_PATH_TICKS;
        if (gaveUp) {
            giveUpCooldownTicks = FAILED_PICKUP_COOLDOWN_TICKS + playerNpc.getRandom().nextInt(20 * 4);
            playerNpc.clearAnimalLootPriority();
        }

        targetItem = null;
        prioritySearchCenter = null;
        pickupTicks = 0;
        repathTicks = 0;
        failedPathTicks = 0;
        closePickupWaitTicks = 0;
        obstructionMineTicks = 0;
        activeApproachTicks = 0;
        activeApproachJumpCooldown = 0;
        playerNpc.clearBlockBreakProgress(pathObstructionPos);
        pathObstructionPos = null;
        clearPickupPillar();
        skippedObstructions.clear();
        restorePreviousMainHand();
        playerNpc.getNavigation().stop();
        if (AI_STATE.equals(playerNpc.getCurrentAiState())) {
            playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        }
    }

    private boolean canCollectRightNow() {
        if (playerNpc.level().isClientSide) {
            return false;
        }
        if (!playerNpc.isAlive() || playerNpc.isRemoved() || playerNpc.isDeadOrDying()) {
            return false;
        }
        if (playerNpc.isPassenger() || playerNpc.isNoAi() || playerNpc.isHealing()) {
            return false;
        }

        LivingEntity target = playerNpc.getTarget();
        return target == null || !target.isAlive() || playerNpc.hasAnimalLootPriority() && target instanceof Animal;
    }

    private ItemEntity findTargetItem() {
        BlockPos searchCenter = playerNpc.getAnimalLootPriorityPos();
        if (searchCenter != null) {
            prioritySearchCenter = searchCenter;
        }
        AABB searchBox = prioritySearchCenter == null
                ? playerNpc.getBoundingBox().inflate(SEARCH_RADIUS, SEARCH_VERTICAL_RADIUS, SEARCH_RADIUS)
                : new AABB(prioritySearchCenter).inflate(ANIMAL_LOOT_SEARCH_RADIUS, ANIMAL_LOOT_SEARCH_VERTICAL_RADIUS, ANIMAL_LOOT_SEARCH_RADIUS);
        List<ItemEntity> items = playerNpc.level().getEntitiesOfClass(
                ItemEntity.class,
                searchBox,
                item -> isCollectable(item) && canAccept(item.getItem())
        );

        return items.stream()
                .filter(this::canReachItem)
                .min(Comparator.comparingDouble(this::targetSortDistance))
                .orElse(null);
    }

    private boolean canReachItem(ItemEntity item) {
        if (playerNpc.distanceToSqr(item) <= PICKUP_DISTANCE_SQR || canReach(item)) {
            return true;
        }

        BlockPos stand = findStandNearItem(item);
        return stand != null && (canReach(stand) || findPathObstructionToward(stand) != null)
                || canPillarToItem(item);
    }

    private double targetSortDistance(ItemEntity item) {
        if (prioritySearchCenter == null) {
            return playerNpc.distanceToSqr(item);
        }

        double dx = item.getX() - (prioritySearchCenter.getX() + 0.5D);
        double dy = item.getY() - prioritySearchCenter.getY();
        double dz = item.getZ() - (prioritySearchCenter.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz + playerNpc.distanceToSqr(item) * 0.1D;
    }

    private boolean isCollectable(ItemEntity item) {
        return item != null
                && item.isAlive()
                && !item.isRemoved()
                && !item.hasPickUpDelay()
                && !item.getItem().isEmpty()
                && InventoryUtils.isInventoryBackedSupplyDrop(item.getItem());
    }

    private boolean canAccept(ItemStack incoming) {
        if (incoming.isEmpty()) {
            return false;
        }

        SimpleContainer inventory = playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slotStack = inventory.getItem(i);
            if (slotStack.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameTags(slotStack, incoming)
                    && slotStack.getCount() < slotStack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private boolean canReach(ItemEntity item) {
        Path path = playerNpc.getNavigation().createPath(item, 0);
        return path != null && path.canReach();
    }

    private boolean canReach(BlockPos pos) {
        Path path = playerNpc.getNavigation().createPath(pos, 0);
        return path != null && path.canReach();
    }

    private void moveToTarget() {
        if (targetItem == null) {
            failedPathTicks = MAX_FAILED_PATH_TICKS;
            playerNpc.getNavigation().stop();
            return;
        }
        if (playerNpc.distanceToSqr(targetItem) <= PICKUP_DISTANCE_SQR) {
            failedPathTicks = 0;
            return;
        }

        Path itemPath = playerNpc.getNavigation().createPath(targetItem, 0);
        if (itemPath != null && itemPath.canReach() && playerNpc.getNavigation().moveTo(itemPath, speed)) {
            failedPathTicks = 0;
        } else {
            BlockPos stand = findStandNearItem(targetItem);
            Path standPath = stand == null ? null : playerNpc.getNavigation().createPath(stand, 0);
            if (standPath != null && standPath.canReach() && playerNpc.getNavigation().moveTo(standPath, speed)) {
                failedPathTicks = 0;
            } else if (isHighPickupTarget(targetItem) && tryMoveToPickupPillarBase()) {
                failedPathTicks = 0;
            } else if (tryStartPathObstructionMining(stand) || tryStartPathObstructionMining(targetItem.blockPosition())) {
                failedPathTicks = 0;
            } else {
                failedPathTicks += REPATH_INTERVAL_TICKS;
            }
        }
    }

    private BlockPos findStandNearItem(ItemEntity item) {
        if (item == null || !(playerNpc.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return null;
        }

        BlockPos itemPos = item.blockPosition();
        BlockPos bestStand = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(itemPos.offset(-2, -2, -2), itemPos.offset(2, 2, 2))) {
            BlockPos stand = pos.immutable();
            if (!canStandAt(serverLevel, stand)) {
                continue;
            }
            double distance = playerNpc.distanceToSqr(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestStand = stand;
            }
        }
        return bestStand;
    }

    private boolean tryActivePickupApproach(ServerLevel serverLevel) {
        if (targetItem == null || !targetItem.isAlive()) {
            return false;
        }

        double dx = targetItem.getX() - playerNpc.getX();
        double dy = targetItem.getY() - playerNpc.getY();
        double dz = targetItem.getZ() - playerNpc.getZ();
        double horizontalSqr = dx * dx + dz * dz;
        if (horizontalSqr > ACTIVE_APPROACH_HORIZONTAL_RANGE_SQR
                || Math.abs(dy) > ACTIVE_APPROACH_VERTICAL_RANGE) {
            activeApproachTicks = 0;
            return false;
        }

        activeApproachTicks++;
        playerNpc.getNavigation().moveTo(targetItem, Math.max(speed, 1.15D));
        playerNpc.getMoveControl().setWantedPosition(
                targetItem.getX(),
                targetItem.getY(),
                targetItem.getZ(),
                Math.max(speed, 1.15D)
        );

        if (activeApproachTicks >= 12
                && (playerNpc.getNavigation().isDone() || playerNpc.getNavigation().isStuck())
                && tryStartPathObstructionMining(targetItem.blockPosition())) {
            activeApproachTicks = 0;
            return true;
        }

        if (horizontalSqr > 1.0E-4D) {
            double horizontal = Math.sqrt(horizontalSqr);
            double pushSpeed = Math.min(ACTIVE_APPROACH_PUSH_SPEED, 0.09D + horizontal * 0.05D);
            Vec3 motion = playerNpc.getDeltaMovement();
            double pushX = dx / horizontal * pushSpeed;
            double pushZ = dz / horizontal * pushSpeed;
            playerNpc.setDeltaMovement(
                    motion.x * 0.35D + pushX,
                    motion.y,
                    motion.z * 0.35D + pushZ
            );
            playerNpc.hasImpulse = true;

            float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            playerNpc.setYRot(yaw);
            playerNpc.setYHeadRot(yaw);
        }

        if (shouldJumpTowardPickup(dy, horizontalSqr)) {
            Vec3 motion = playerNpc.getDeltaMovement();
            playerNpc.setDeltaMovement(motion.x, Math.max(motion.y, ACTIVE_APPROACH_JUMP_Y), motion.z);
            playerNpc.hasImpulse = true;
            activeApproachJumpCooldown = ACTIVE_APPROACH_JUMP_COOLDOWN_TICKS;
        }

        playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "moving into %s\ngiving up in %ds",
                targetItem.getItem().getHoverName().getString(),
                getRemainingPickupSeconds()
        ));
        if (playerNpc.tryPickupItemEntity(targetItem)) {
            activeApproachTicks = 0;
            failedPathTicks = 0;
            repathTicks = 0;
        }
        return true;
    }

    private boolean shouldJumpTowardPickup(double dy, double horizontalSqr) {
        if (!playerNpc.onGround() || activeApproachJumpCooldown > 0) {
            return false;
        }

        return dy > 0.15D
                || horizontalSqr > 0.85D * 0.85D && (playerNpc.getNavigation().isDone() || activeApproachTicks >= 4)
                || activeApproachTicks >= 8;
    }

    private boolean tickPickupPillar(ServerLevel serverLevel) {
        if (!isHighPickupTarget(targetItem) || countPickupPillarBlocks() <= 0) {
            clearPickupPillar();
            return false;
        }

        if (pickupPillarPlacePos != null) {
            tickPickupPillarPlacement(serverLevel);
            return true;
        }

        if (pickupPillarBasePos == null || !canUsePickupPillarBase(serverLevel, pickupPillarBasePos, targetItem)) {
            pickupPillarBasePos = findPickupPillarBase(serverLevel, targetItem);
            if (pickupPillarBasePos == null) {
                return false;
            }
        }

        if (!isAtPickupPillarBase()) {
            moveToPickupPillarBase(pickupPillarBasePos);
            return true;
        }

        if (!playerNpc.onGround()) {
            return true;
        }

        BlockPos feet = playerNpc.blockPosition();
        if (tryClearPillarHeadroom(serverLevel, feet)) {
            return true;
        }

        if (!canPillarFrom(serverLevel, feet)) {
            clearPickupPillar();
            return false;
        }

        return beginPickupPillarStep(serverLevel, feet);
    }

    private boolean tryMoveToPickupPillarBase() {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel) || !isHighPickupTarget(targetItem)) {
            return false;
        }

        BlockPos base = pickupPillarBasePos;
        if (base == null || !canUsePickupPillarBase(serverLevel, base, targetItem)) {
            base = findPickupPillarBase(serverLevel, targetItem);
            pickupPillarBasePos = base;
        }
        if (base == null) {
            return false;
        }

        moveToPickupPillarBase(base);
        return true;
    }

    private boolean isHighPickupTarget(ItemEntity item) {
        return item != null
                && item.isAlive()
                && item.getY() - playerNpc.getY() >= HIGH_ITEM_VERTICAL_GAP
                && horizontalDistanceSqrToItem(item, playerNpc.blockPosition()) <= 4.0D * 4.0D;
    }

    private boolean canPillarToItem(ItemEntity item) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)
                || !isHighPickupTarget(item)
                || countPickupPillarBlocks() <= 0) {
            return false;
        }

        return findPickupPillarBase(serverLevel, item) != null;
    }

    private BlockPos findPickupPillarBase(ServerLevel serverLevel, ItemEntity item) {
        if (item == null) {
            return null;
        }

        BlockPos currentFeet = playerNpc.blockPosition();
        if (canUsePickupPillarBase(serverLevel, currentFeet, item)) {
            return currentFeet.immutable();
        }

        BlockPos itemPos = item.blockPosition();
        BlockPos bestBase = null;
        double bestDistance = Double.MAX_VALUE;
        int minY = Math.max(serverLevel.getMinBuildHeight() + 1, currentFeet.getY() - 2);
        int maxY = Math.min(itemPos.getY(), currentFeet.getY() + 2);
        for (int y = maxY; y >= minY; y--) {
            for (int x = itemPos.getX() - PICKUP_PILLAR_SEARCH_RADIUS; x <= itemPos.getX() + PICKUP_PILLAR_SEARCH_RADIUS; x++) {
                for (int z = itemPos.getZ() - PICKUP_PILLAR_SEARCH_RADIUS; z <= itemPos.getZ() + PICKUP_PILLAR_SEARCH_RADIUS; z++) {
                    BlockPos base = new BlockPos(x, y, z);
                    if (!canUsePickupPillarBase(serverLevel, base, item)) {
                        continue;
                    }
                    double distance = playerNpc.distanceToSqr(base.getX() + 0.5D, base.getY(), base.getZ() + 0.5D);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        bestBase = base.immutable();
                    }
                }
            }
        }
        return bestBase;
    }

    private boolean canUsePickupPillarBase(ServerLevel serverLevel, BlockPos base, ItemEntity item) {
        if (base == null || item == null || !canStandAt(serverLevel, base)) {
            return false;
        }
        BlockPos feet = playerNpc.blockPosition();
        if (!(feet.getX() == base.getX() && feet.getZ() == base.getZ())
                && !canReach(base)
                && findPathObstructionToward(base) == null) {
            return false;
        }
        if (horizontalDistanceSqrToItem(item, base) > 1.85D * 1.85D) {
            return false;
        }

        int blocksNeeded = Math.max(1, item.blockPosition().getY() - base.getY());
        if (blocksNeeded > Math.max(1, countPickupPillarBlocks())) {
            return false;
        }

        int maxClimb = Math.min(Math.max(1, blocksNeeded), countPickupPillarBlocks());
        for (int placed = 1; placed <= maxClimb; placed++) {
            if (!hasOpenOrClearableBodySpace(serverLevel, base.above(placed))) {
                return false;
            }
        }
        return true;
    }

    private double horizontalDistanceSqrToItem(ItemEntity item, BlockPos pos) {
        double dx = item.getX() - (pos.getX() + 0.5D);
        double dz = item.getZ() - (pos.getZ() + 0.5D);
        return dx * dx + dz * dz;
    }

    private boolean isAtPickupPillarBase() {
        if (pickupPillarBasePos == null) {
            return false;
        }

        BlockPos feet = playerNpc.blockPosition();
        return feet.getY() >= pickupPillarBasePos.getY()
                && feet.getX() == pickupPillarBasePos.getX()
                && feet.getZ() == pickupPillarBasePos.getZ()
                || playerNpc.distanceToSqr(
                pickupPillarBasePos.getX() + 0.5D,
                pickupPillarBasePos.getY(),
                pickupPillarBasePos.getZ() + 0.5D
        ) <= PICKUP_PILLAR_BASE_REACHED_SQR;
    }

    private void moveToPickupPillarBase(BlockPos base) {
        if (base == null) {
            return;
        }

        Path path = playerNpc.getNavigation().createPath(base, 0);
        if (path != null && path.canReach() && playerNpc.getNavigation().moveTo(path, speed)) {
            failedPathTicks = 0;
            return;
        }

        if (!tryStartPathObstructionMining(base)) {
            failedPathTicks += REPATH_INTERVAL_TICKS;
        }
    }

    private boolean tryClearPillarHeadroom(ServerLevel serverLevel, BlockPos feet) {
        BlockPos[] headroom = new BlockPos[]{feet.above(), feet.above(2)};
        for (BlockPos pos : headroom) {
            BlockState state = serverLevel.getBlockState(pos);
            if (!isPathObstructionBlock(serverLevel, pos, state)) {
                continue;
            }
            pathObstructionPos = pos.immutable();
            obstructionMineTicks = 0;
            return tickPathObstruction();
        }
        return false;
    }

    private boolean canPillarFrom(ServerLevel serverLevel, BlockPos feet) {
        return serverLevel.getBlockState(feet).canBeReplaced()
                && hasOpenBodySpace(serverLevel, feet)
                && !hasOtherEntityInBlock(serverLevel, feet)
                && equipPickupPillarBlock();
    }

    private boolean hasOpenBodySpace(ServerLevel serverLevel, BlockPos feet) {
        BlockState feetState = serverLevel.getBlockState(feet);
        BlockState headState = serverLevel.getBlockState(feet.above());
        return feetState.getCollisionShape(serverLevel, feet).isEmpty()
                && headState.getCollisionShape(serverLevel, feet.above()).isEmpty()
                && feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty();
    }

    private boolean hasOpenOrClearableBodySpace(ServerLevel serverLevel, BlockPos feet) {
        return isOpenOrClearableBodyBlock(serverLevel, feet)
                && isOpenOrClearableBodyBlock(serverLevel, feet.above());
    }

    private boolean isOpenOrClearableBodyBlock(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        if (state.getCollisionShape(serverLevel, pos).isEmpty() && state.getFluidState().isEmpty()) {
            return true;
        }
        return isPathObstructionBlock(serverLevel, pos, state);
    }

    private boolean beginPickupPillarStep(ServerLevel serverLevel, BlockPos feet) {
        if (!equipPickupPillarBlock()) {
            return false;
        }

        pickupPillarPlacePos = feet.immutable();
        pickupPillarPlaceDelayTicks = PICKUP_PILLAR_PLACE_DELAY_TICKS;
        pickupPillarPlaceWaitTicks = 0;
        playerNpc.getNavigation().stop();
        lookDownAt(pickupPillarPlacePos);
        playerNpc.shortPillarJump();
        updatePickupPillarDetail();
        return true;
    }

    private void tickPickupPillarPlacement(ServerLevel serverLevel) {
        if (pickupPillarPlacePos == null) {
            return;
        }
        if (pickupPillarPlaceDelayTicks > 0) {
            pickupPillarPlaceDelayTicks--;
            return;
        }

        pickupPillarPlaceWaitTicks++;
        if (pickupPillarPlaceWaitTicks > PICKUP_PILLAR_MAX_PLACE_WAIT_TICKS) {
            clearPickupPillarPlacement();
            return;
        }

        if (!hasPickupPillarPlacementClearance()
                && pickupPillarPlaceWaitTicks < PICKUP_PILLAR_FORCE_PLACE_TICKS) {
            lookDownAt(pickupPillarPlacePos);
            return;
        }

        if (!serverLevel.getBlockState(pickupPillarPlacePos).canBeReplaced()
                || !equipPickupPillarBlock()) {
            clearPickupPillarPlacement();
            return;
        }

        ItemStack blockStack = playerNpc.getMainHandItem();
        if (blockStack.isEmpty() || !(blockStack.getItem() instanceof BlockItem blockItem)) {
            clearPickupPillarPlacement();
            return;
        }

        BlockState placeState = blockItem.getBlock().defaultBlockState();
        if (!canPlacePickupPillarWithoutClipping(serverLevel, pickupPillarPlacePos, placeState)) {
            lookDownAt(pickupPillarPlacePos);
            return;
        }

        lookDownAt(pickupPillarPlacePos);
        if (!serverLevel.setBlockAndUpdate(pickupPillarPlacePos, placeState)) {
            clearPickupPillarPlacement();
            return;
        }
        snapAbovePickupPillarIfNeeded(pickupPillarPlacePos);
        playerNpc.swing(InteractionHand.MAIN_HAND, true);
        SoundType soundType = placeState.getSoundType(serverLevel, pickupPillarPlacePos, playerNpc);
        serverLevel.playSound(
                null,
                pickupPillarPlacePos,
                soundType.getPlaceSound(),
                SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) * 0.5F,
                soundType.getPitch() * 0.8F
        );
        blockStack.shrink(1);
        if (blockStack.isEmpty()) {
            playerNpc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }

        failedPathTicks = 0;
        repathTicks = 0;
        clearPickupPillarPlacement();
        moveToTarget();
    }

    private boolean hasPickupPillarPlacementClearance() {
        if (pickupPillarPlacePos == null) {
            return false;
        }

        double clearedY = playerNpc.getBoundingBox().minY - pickupPillarPlacePos.getY();
        if (clearedY >= PICKUP_PILLAR_PLACE_CLEARANCE_Y) {
            return true;
        }

        return pickupPillarPlaceWaitTicks >= 6
                && clearedY >= PICKUP_PILLAR_FALLBACK_PLACE_CLEARANCE_Y
                && playerNpc.getDeltaMovement().y <= 0.05D;
    }

    private boolean canPlacePickupPillarWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (hasOtherEntityInBlock(serverLevel, pos)) {
            return false;
        }

        List<AABB> boxes = state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .map(box -> box.move(pos))
                .toList();
        if (boxes.stream().noneMatch(box -> box.intersects(playerNpc.getBoundingBox().inflate(0.02D)))) {
            return true;
        }

        double snapUp = pos.getY() + 1.0D - playerNpc.getBoundingBox().minY;
        if (snapUp < -0.05D || snapUp > 0.35D) {
            return false;
        }

        AABB snappedBox = playerNpc.getBoundingBox().move(0.0D, snapUp + 0.01D, 0.0D);
        return boxes.stream().noneMatch(box -> box.intersects(snappedBox.inflate(0.001D)))
                && serverLevel.noCollision(playerNpc, snappedBox);
    }

    private boolean hasOtherEntityInBlock(ServerLevel serverLevel, BlockPos pos) {
        return !serverLevel.getEntities(
                playerNpc,
                new AABB(pos).inflate(0.05D),
                entity -> entity.isAlive() && !(entity instanceof ItemEntity)
        ).isEmpty();
    }

    private void snapAbovePickupPillarIfNeeded(BlockPos pos) {
        double topY = pos.getY() + 1.0D;
        if (playerNpc.getBoundingBox().minY >= topY) {
            return;
        }

        Vec3 motion = playerNpc.getDeltaMovement();
        playerNpc.setPos(playerNpc.getX(), topY, playerNpc.getZ());
        playerNpc.setDeltaMovement(motion.x, Math.max(0.0D, motion.y), motion.z);
        playerNpc.fallDistance = 0.0F;
    }

    private boolean equipPickupPillarBlock() {
        if (isPickupPillarBlock(playerNpc.getMainHandItem())) {
            return true;
        }

        ItemStack block = playerNpc.consumeInventoryItem(this::isPickupPillarBlock, 1)
                .orElse(ItemStack.EMPTY);
        if (block.isEmpty()) {
            return false;
        }

        setTemporaryMainHand(block);
        return true;
    }

    private boolean isPickupPillarBlock(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return false;
        }
        if (stack.is(Items.DIRT) || stack.is(Items.COBBLESTONE)) {
            return true;
        }
        if (stack.is(ItemTags.LOGS)
                || stack.is(ItemTags.SAPLINGS)
                || stack.is(Items.CRAFTING_TABLE)
                || stack.is(Items.CHEST)
                || stack.is(Items.FURNACE)
                || stack.getItem() instanceof BedItem) {
            return false;
        }

        BlockState state = blockItem.getBlock().defaultBlockState();
        return !state.is(Blocks.TORCH)
                && !state.is(BlockTags.LEAVES)
                && !state.getCollisionShape(playerNpc.level(), BlockPos.ZERO).isEmpty();
    }

    private int countPickupPillarBlocks() {
        int count = isPickupPillarBlock(playerNpc.getMainHandItem()) ? playerNpc.getMainHandItem().getCount() : 0;
        SimpleContainer inventory = playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (isPickupPillarBlock(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void lookDownAt(BlockPos pos) {
        playerNpc.getLookControl().setLookAt(
                pos.getX() + 0.5D,
                pos.getY() - 0.5D,
                pos.getZ() + 0.5D,
                60.0F,
                60.0F
        );
    }

    private void clearPickupPillar() {
        pickupPillarBasePos = null;
        clearPickupPillarPlacement();
    }

    private void clearPickupPillarPlacement() {
        pickupPillarPlacePos = null;
        pickupPillarPlaceDelayTicks = 0;
        pickupPillarPlaceWaitTicks = 0;
    }

    private void updatePickupPillarDetail() {
        if (targetItem == null || pickupPillarBasePos == null) {
            return;
        }
        playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "pillaring to %s\ngiving up in %ds",
                targetItem.getItem().getHoverName().getString(),
                getRemainingPickupSeconds()
        ));
    }

    private boolean tryStartPathObstructionMining(BlockPos destination) {
        if (!(playerNpc.level() instanceof ServerLevel) || destination == null) {
            return false;
        }

        BlockPos obstruction = findPathObstructionToward(destination);
        if (obstruction == null) {
            return false;
        }

        pathObstructionPos = obstruction;
        obstructionMineTicks = 0;
        playerNpc.getNavigation().stop();
        return tickPathObstruction();
    }

    private boolean tickPathObstruction() {
        if (pathObstructionPos == null || !(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(pathObstructionPos);
        if (!isPathObstructionBlock(serverLevel, pathObstructionPos, state)) {
            clearPathObstruction();
            return false;
        }
        if (playerNpc.distanceToSqr(
                pathObstructionPos.getX() + 0.5D,
                pathObstructionPos.getY() + 0.5D,
                pathObstructionPos.getZ() + 0.5D
        ) > OBSTRUCTION_BREAK_DISTANCE_SQR) {
            skippedObstructions.add(pathObstructionPos.immutable());
            clearPathObstruction();
            return false;
        }
        if (!equipToolFor(state)) {
            skippedObstructions.add(pathObstructionPos.immutable());
            clearPathObstruction();
            return false;
        }

        playerNpc.getNavigation().stop();
        playerNpc.getLookControl().setLookAt(
                pathObstructionPos.getX() + 0.5D,
                pathObstructionPos.getY() + 0.5D,
                pathObstructionPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );
        if (obstructionMineTicks % 8 == 0) {
            playerNpc.triggerMainHandAttackAnimation();
            PlayerNpcBlockSoundUtil.playMiningHitSound(serverLevel, pathObstructionPos, state, playerNpc);
        }

        obstructionMineTicks++;
        int requiredMineTicks = getRequiredMineTicks(serverLevel, pathObstructionPos, state);
        playerNpc.showBlockBreakProgress(pathObstructionPos, obstructionMineTicks, requiredMineTicks);
        updateObstructionDetail(state, requiredMineTicks);
        if (obstructionMineTicks < requiredMineTicks && obstructionMineTicks < MAX_OBSTRUCTION_BREAK_TICKS) {
            return true;
        }

        BlockPos clearedPos = pathObstructionPos;
        if (obstructionMineTicks >= requiredMineTicks && serverLevel.destroyBlock(clearedPos, true, playerNpc)) {
            playerNpc.hurtMainHandItem(1);
            failedPathTicks = 0;
            repathTicks = 0;
            moveToTarget();
        } else {
            skippedObstructions.add(clearedPos.immutable());
            failedPathTicks += REPATH_INTERVAL_TICKS;
        }
        clearPathObstruction();
        return true;
    }

    private void clearPathObstruction() {
        playerNpc.clearBlockBreakProgress(pathObstructionPos);
        pathObstructionPos = null;
        obstructionMineTicks = 0;
    }

    private BlockPos findPathObstructionToward(BlockPos destination) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel) || destination == null) {
            return null;
        }

        BlockPos feet = playerNpc.blockPosition();
        List<BlockPos> candidates = new java.util.ArrayList<>();
        candidates.add(feet.above());
        candidates.add(feet.above(2));
        candidates.add(destination);
        candidates.add(destination.above());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = feet.relative(direction);
            candidates.add(side);
            candidates.add(side.above());
            if (destination.getY() > feet.getY()) {
                candidates.add(side.above(2));
            }
        }
        addLineObstructionCandidates(candidates, feet, destination);

        Set<BlockPos> seen = new HashSet<>();
        candidates.sort(Comparator
                .comparingDouble((BlockPos pos) -> playerNpc.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D))
                .thenComparingDouble(pos -> pos.distSqr(destination)));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!seen.add(immutable)
                    || skippedObstructions.contains(immutable)
                    || !serverLevel.isInWorldBounds(immutable)
                    || !serverLevel.getWorldBorder().isWithinBounds(immutable)
                    || playerNpc.distanceToSqr(
                    immutable.getX() + 0.5D,
                    immutable.getY() + 0.5D,
                    immutable.getZ() + 0.5D
            ) > OBSTRUCTION_BREAK_DISTANCE_SQR) {
                continue;
            }

            BlockState state = serverLevel.getBlockState(immutable);
            if (isPathObstructionBlock(serverLevel, immutable, state)) {
                return immutable;
            }
        }
        return null;
    }

    private void addLineObstructionCandidates(List<BlockPos> candidates, BlockPos feet, BlockPos destination) {
        double dx = destination.getX() - feet.getX();
        double dy = destination.getY() - feet.getY();
        double dz = destination.getZ() - feet.getZ();
        double steps = Math.max(1.0D, Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))));
        int maxSteps = Math.min(8, (int) Math.ceil(steps));
        for (int i = 1; i <= maxSteps; i++) {
            double progress = i / (double) maxSteps;
            int x = feet.getX() + (int) Math.round(dx * progress);
            int y = feet.getY() + (int) Math.round(dy * progress);
            int z = feet.getZ() + (int) Math.round(dz * progress);
            BlockPos routeFeet = new BlockPos(x, y, z);
            candidates.add(routeFeet);
            candidates.add(routeFeet.above());
            candidates.add(routeFeet.above(2));
        }
    }

    private boolean isPathObstructionBlock(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        boolean blocksMovement = !state.getCollisionShape(serverLevel, pos).isEmpty();
        boolean replaceableClutter = state.canBeReplaced() && !state.isAir();
        return !state.isAir()
                && state.getDestroySpeed(serverLevel, pos) >= 0.0F
                && (blocksMovement || replaceableClutter)
                && state.getFluidState().isEmpty()
                && !isProtectedHomeBlock(pos)
                && serverLevel.getBlockEntity(pos) == null
                && hasRequiredToolFor(state);
    }

    private boolean canStandAt(net.minecraft.server.level.ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }

        var feet = serverLevel.getBlockState(pos);
        var head = serverLevel.getBlockState(pos.above());
        BlockPos floorPos = pos.below();
        return feet.getCollisionShape(serverLevel, pos).isEmpty()
                && head.getCollisionShape(serverLevel, pos.above()).isEmpty()
                && feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && serverLevel.getBlockState(floorPos).isSolidRender(serverLevel, floorPos);
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(playerNpc);
        return homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), pos);
    }

    private boolean hasRequiredToolFor(BlockState state) {
        return !isPickaxeBlock(state) || hasTool(PickaxeItem.class);
    }

    private boolean isPickaxeBlock(BlockState state) {
        return state.is(BlockTags.MINEABLE_WITH_PICKAXE);
    }

    private boolean equipToolFor(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            if (!equipTool(AxeItem.class)) {
                equipEmptyHandForMining();
            }
            return true;
        }
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return equipTool(PickaxeItem.class);
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            if (!equipTool(ShovelItem.class)) {
                equipEmptyHandForMining();
            }
            return true;
        }
        return true;
    }

    private boolean equipTool(Class<?> toolClass) {
        if (toolClass.isInstance(playerNpc.getMainHandItem().getItem())) {
            return true;
        }
        if (restorePreviousMainHandForTool(toolClass)) {
            return true;
        }

        ItemStack tool = playerNpc.consumeInventoryItem(stack -> toolClass.isInstance(stack.getItem()), 1)
                .orElse(ItemStack.EMPTY);
        if (tool.isEmpty()) {
            return false;
        }

        setTemporaryMainHand(tool);
        return true;
    }

    private void equipEmptyHandForMining() {
        if (playerNpc.getMainHandItem().isEmpty()) {
            return;
        }

        setTemporaryMainHand(ItemStack.EMPTY);
    }

    private void setTemporaryMainHand(ItemStack stack) {
        ItemStack currentMainHand = playerNpc.getMainHandItem().copy();
        if (!usingTemporaryTool) {
            previousMainHand = currentMainHand;
            usingTemporaryTool = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, previousMainHand)
                && !InventoryUtils.addItem(playerNpc, currentMainHand)) {
            playerNpc.spawnAtLocation(currentMainHand);
        }

        playerNpc.setItemSlot(EquipmentSlot.MAINHAND, stack);
    }

    private boolean restorePreviousMainHandForTool(Class<?> toolClass) {
        if (!usingTemporaryTool || !toolClass.isInstance(previousMainHand.getItem())) {
            return false;
        }

        ItemStack currentMainHand = playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, previousMainHand)
                && !InventoryUtils.addItem(playerNpc, currentMainHand)) {
            playerNpc.spawnAtLocation(currentMainHand);
        }

        playerNpc.setItemSlot(EquipmentSlot.MAINHAND, previousMainHand.copy());
        previousMainHand = ItemStack.EMPTY;
        usingTemporaryTool = false;
        return true;
    }

    private void restorePreviousMainHand() {
        if (!usingTemporaryTool) {
            return;
        }

        ItemStack currentMainHand = playerNpc.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, previousMainHand)
                && !InventoryUtils.addItem(playerNpc, currentMainHand)) {
            playerNpc.spawnAtLocation(currentMainHand);
        }

        playerNpc.setItemSlot(EquipmentSlot.MAINHAND, previousMainHand.copy());
        previousMainHand = ItemStack.EMPTY;
        usingTemporaryTool = false;
    }

    private boolean hasTool(Class<?> toolClass) {
        if (toolClass.isInstance(playerNpc.getMainHandItem().getItem())) {
            return true;
        }
        if (usingTemporaryTool && toolClass.isInstance(previousMainHand.getItem())) {
            return true;
        }
        return InventoryUtils.hasItem(playerNpc, stack -> toolClass.isInstance(stack.getItem()));
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(serverLevel, pos);
        if (hardness < 0.0F) {
            return MAX_OBSTRUCTION_BREAK_TICKS;
        }

        ItemStack heldStack = playerNpc.getMainHandItem();
        float toolSpeed = heldStack.isEmpty() ? 1.0F : heldStack.getDestroySpeed(state);
        if (toolSpeed <= 0.0F) {
            toolSpeed = 1.0F;
        }

        boolean correctTool = !state.requiresCorrectToolForDrops() || heldStack.isCorrectToolForDrops(state);
        float progressPerTick = toolSpeed / hardness / (correctTool ? 30.0F : 100.0F);
        if (progressPerTick <= 0.0F) {
            return MAX_OBSTRUCTION_BREAK_TICKS;
        }

        return Math.max(1, (int) Math.ceil(1.0F / progressPerTick));
    }

    private void updateDetail() {
        if (targetItem == null || targetItem.getItem().isEmpty()) {
            if (prioritySearchCenter != null) {
                playerNpc.setCurrentAiDetail(String.format(
                        java.util.Locale.ROOT,
                        "animal drops\n@ %d %d %d\ngiving up in %ds",
                        prioritySearchCenter.getX(),
                        prioritySearchCenter.getY(),
                        prioritySearchCenter.getZ(),
                        getRemainingPickupSeconds()
                ));
                return;
            }
            playerNpc.setCurrentAiDetail(String.format(
                    java.util.Locale.ROOT,
                    "nearby drops\ngiving up in %ds",
                    getRemainingPickupSeconds()
            ));
            return;
        }

        playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "%s\n@ %d %d %d\ngiving up in %ds",
                targetItem.getItem().getHoverName().getString(),
                targetItem.blockPosition().getX(),
                targetItem.blockPosition().getY(),
                targetItem.blockPosition().getZ(),
                getRemainingPickupSeconds()
        ));
    }

    private void updateObstructionDetail(BlockState state, int requiredMineTicks) {
        if (pathObstructionPos == null) {
            return;
        }

        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String blockName = blockId == null ? state.getBlock().getDescriptionId() : blockId.toString();
        playerNpc.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "clearing pickup path %s\n@ %d %d %d %d/%dt\ngiving up in %ds",
                blockName,
                pathObstructionPos.getX(),
                pathObstructionPos.getY(),
                pathObstructionPos.getZ(),
                Math.min(obstructionMineTicks, requiredMineTicks),
                requiredMineTicks,
                getRemainingPickupSeconds()
        ));
    }

    private int getRemainingPickupSeconds() {
        int remainingTicks = Math.max(0, MAX_PICKUP_TICKS - pickupTicks);
        return Math.max(0, (remainingTicks + 19) / 20);
    }

}
