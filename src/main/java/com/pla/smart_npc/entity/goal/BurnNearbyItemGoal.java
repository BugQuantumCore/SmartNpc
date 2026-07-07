package com.pla.smart_npc.entity.goal;

import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcBlockBreakUtil;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcBlockSoundUtil;
import com.pla.smart_npc.util.PlayerNpcHomeUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class BurnNearbyItemGoal extends Goal {
    private final Mob mob;
    private final double speed;
    private final double searchRadius;
    private final Set<BlockPos> skippedObstructions = new HashSet<>();
    private ItemEntity targetItem;
    private BlockPos pathObstructionPos;
    private ItemStack burnToolRestoreItem = ItemStack.EMPTY;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private BlockPos firePos;
    private ItemStack burningStack = ItemStack.EMPTY;
    private ItemStack activeBurnToolStack = ItemStack.EMPTY;
    private int burnTicks;
    private int repathTicks;
    private int failedPathTicks;
    private int obstructionMineTicks;
    private int giveUpCooldownTicks;
    private boolean equippedBurnTool;
    private boolean usingTemporaryTool;
    private BurnTool burnTool;
    private static final int REPATH_INTERVAL_TICKS = 10;
    private static final int MAX_FAILED_PATH_TICKS = 20 * 5;
    private static final int FAILED_BURN_COOLDOWN_TICKS = 20 * 15;
    private static final int MAX_OBSTRUCTION_BREAK_TICKS = 20 * 4;
    private static final double OBSTRUCTION_BREAK_DISTANCE_SQR = 3.2D * 3.2D;

    private static List<String> keys(String prefix, int count) {
        List<String> list = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            list.add(prefix + "." + i);
        }
        return List.copyOf(list);
    }

    private static final List<String> burnMessageKeys = keys("burn_item.player_npc", 56);
    private enum BurnTool {
        FLINT_AND_STEEL,
        LAVA_BUCKET
    }

    public BurnNearbyItemGoal(Mob mob, double speed, double searchRadius) {
        this.mob = mob;
        this.speed = speed;
        this.searchRadius = searchRadius;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (giveUpCooldownTicks > 0) {
            giveUpCooldownTicks--;
            return false;
        }
        if (mob.level().isClientSide) return false;
        if (!mob.isAlive() || mob.isRemoved() || mob.isDeadOrDying()) return false;
        if (mob.isPassenger()) return false;
        if (mob.getTarget() != null) return false;
        if (mob.isNoAi()) return false;
        if (mob instanceof PlayerNpcEntity playerNpcEntity && playerNpcEntity.isHealing()) {
            return false;
        }
        targetItem = findTargetItem();
        return targetItem != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (mob.level().isClientSide) return false;
        if (!mob.isAlive() || mob.isRemoved() || mob.isDeadOrDying()) return false;
        if (mob.isPassenger()) return false;
        if (mob.getTarget() != null) return false;
        if (mob.isNoAi()) return false;

        return targetItem != null
                && (burnTicks > 0 || targetItem.isAlive())
                && (burnTicks > 0 || !targetItem.getItem().isEmpty());
    }

    @Override
    public void start() {
        burnToolRestoreItem = ItemStack.EMPTY;
        firePos = null;
        burningStack = ItemStack.EMPTY;
        activeBurnToolStack = ItemStack.EMPTY;
        burnTicks = 0;
        repathTicks = 0;
        failedPathTicks = 0;
        obstructionMineTicks = 0;
        pathObstructionPos = null;
        previousMainHand = ItemStack.EMPTY;
        skippedObstructions.clear();
        equippedBurnTool = false;
        usingTemporaryTool = false;
        burnTool = null;

        if (targetItem == null) {
            return;
        }

        if (shouldPickupOrEquipInsteadOfBurn(targetItem.getItem())) {
            restoreMainWeapon(false);
        }

        updateMovingDetail();
        moveToTargetItem();
    }

    @Override
    public void tick() {
        if (!mob.isAlive() || mob.isRemoved() || mob.isDeadOrDying()) return;
        if (!(mob.level() instanceof ServerLevel serverLevel)) return;
        if (burnTicks > 0) {
            tickBurningGround(serverLevel);
            return;
        }

        if (targetItem == null || !targetItem.isAlive() || targetItem.getItem().isEmpty()) {
            return;
        }

        if (tickPathObstruction()) {
            return;
        }

        if (shouldPickupOrEquipInsteadOfBurn(targetItem.getItem())) {
            restoreMainWeapon(false);
        }

        double dist = mob.distanceTo(targetItem);

        if (dist > 1.5D && (repathTicks-- <= 0 || mob.getNavigation().isDone() || mob.getNavigation().isStuck())) {
            repathTicks = REPATH_INTERVAL_TICKS;
            if (moveToTargetItem()) {
                failedPathTicks = 0;
            } else {
                failedPathTicks += REPATH_INTERVAL_TICKS;
                if (failedPathTicks >= MAX_FAILED_PATH_TICKS) {
                    abandonTarget("failed to path to item");
                }
                return;
            }
        }

        mob.getLookControl().setLookAt(
                targetItem.getX(),
                targetItem.getY() + targetItem.getBbHeight() / 2.0,
                targetItem.getZ(),
                30.0F, 30.0F
        );

        if (dist <= 1.5D) {
            restorePreviousMainHand();
            if (shouldPickupOrEquipInsteadOfBurn(targetItem.getItem())) {
                if (tryHandleItemWithoutBurning(targetItem)) {
                    targetItem = null;
                    mob.getNavigation().stop();
                    return;
                }
            }

            if (shouldReserveInsteadOfBurn(targetItem.getItem())) {
                targetItem = null;
                mob.getNavigation().stop();
                return;
            }

            igniteGroundAtItem(serverLevel);
        }

        updateMovingDetail();
    }

    @Override
    public void stop() {
        boolean shouldRestoreBurnTool = equippedBurnTool || isBurnTool(mob.getMainHandItem());

        clearTemporaryFire();
        targetItem = null;
        clearPathObstruction();
        mob.getNavigation().stop();

        if (shouldRestoreBurnTool) {
            restoreMainWeapon(true);
        }
        returnActiveBurnToolIfNeeded();
        restorePreviousMainHand();

        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            playerNpcEntity.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        }

        burnToolRestoreItem = ItemStack.EMPTY;
        firePos = null;
        burningStack = ItemStack.EMPTY;
        activeBurnToolStack = ItemStack.EMPTY;
        burnTicks = 0;
        repathTicks = 0;
        failedPathTicks = 0;
        obstructionMineTicks = 0;
        pathObstructionPos = null;
        previousMainHand = ItemStack.EMPTY;
        skippedObstructions.clear();
        equippedBurnTool = false;
        usingTemporaryTool = false;
        burnTool = null;
    }

    private boolean moveToTargetItem() {
        if (targetItem == null || !targetItem.isAlive()) {
            return false;
        }

        restorePreviousMainHand();
        Path itemPath = mob.getNavigation().createPath(targetItem, 0);
        if (itemPath != null && itemPath.canReach() && mob.getNavigation().moveTo(itemPath, speed)) {
            return true;
        }

        BlockPos stand = findStandNearItem(targetItem);
        Path standPath = stand == null ? null : mob.getNavigation().createPath(stand, 0);
        if (standPath != null && standPath.canReach() && mob.getNavigation().moveTo(standPath, speed)) {
            return true;
        }

        return tryStartPathObstructionMining(stand) || tryStartPathObstructionMining(targetItem.blockPosition());
    }

    private void abandonTarget(String reason) {
        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            playerNpcEntity.setCurrentAiDetail(reason);
        }
        giveUpCooldownTicks = FAILED_BURN_COOLDOWN_TICKS;
        targetItem = null;
        clearPathObstruction();
        mob.getNavigation().stop();
    }

    private BlockPos findStandNearItem(ItemEntity item) {
        if (item == null || !(mob.level() instanceof ServerLevel serverLevel)) {
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
            double distance = mob.distanceToSqr(stand.getX() + 0.5D, stand.getY(), stand.getZ() + 0.5D);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestStand = stand;
            }
        }
        return bestStand;
    }

    private boolean tryStartPathObstructionMining(BlockPos destination) {
        if (!(mob.level() instanceof ServerLevel) || destination == null || !(mob instanceof PlayerNpcEntity)) {
            return false;
        }

        BlockPos obstruction = findPathObstructionToward(destination);
        if (obstruction == null) {
            return false;
        }

        pathObstructionPos = obstruction;
        obstructionMineTicks = 0;
        mob.getNavigation().stop();
        return tickPathObstruction();
    }

    private boolean tickPathObstruction() {
        if (pathObstructionPos == null
                || !(mob instanceof PlayerNpcEntity playerNpc)
                || !(mob.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        BlockState state = serverLevel.getBlockState(pathObstructionPos);
        if (!isPathObstructionBlock(serverLevel, pathObstructionPos, state)) {
            clearPathObstruction();
            return false;
        }
        if (mob.distanceToSqr(
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

        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(
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
        if (obstructionMineTicks >= requiredMineTicks && PlayerNpcBlockBreakUtil.destroyBlock(serverLevel, clearedPos, state, playerNpc)) {
            playerNpc.hurtMainHandItem(1);
            failedPathTicks = 0;
            repathTicks = 0;
            clearPathObstruction();
            moveToTargetItem();
        } else {
            skippedObstructions.add(clearedPos.immutable());
            failedPathTicks += REPATH_INTERVAL_TICKS;
            clearPathObstruction();
            if (failedPathTicks >= MAX_FAILED_PATH_TICKS) {
                abandonTarget("failed to clear burn path");
            }
        }
        return true;
    }

    private void clearPathObstruction() {
        if (mob instanceof PlayerNpcEntity playerNpc) {
            playerNpc.clearBlockBreakProgress(pathObstructionPos);
        }
        pathObstructionPos = null;
        obstructionMineTicks = 0;
    }

    private BlockPos findPathObstructionToward(BlockPos destination) {
        if (!(mob.level() instanceof ServerLevel serverLevel) || destination == null) {
            return null;
        }

        BlockPos feet = mob.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
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
                .comparingDouble((BlockPos pos) -> mob.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D))
                .thenComparingDouble(pos -> pos.distSqr(destination)));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (!seen.add(immutable)
                    || skippedObstructions.contains(immutable)
                    || !serverLevel.isInWorldBounds(immutable)
                    || !serverLevel.getWorldBorder().isWithinBounds(immutable)
                    || mob.distanceToSqr(
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
                && !CraftBasicGearGoal.isTemporaryCraftingTable(playerNpcForTempTableCheck(), serverLevel, pos)
                && !isProtectedHomeBlock(pos)
                && serverLevel.getBlockEntity(pos) == null
                && hasRequiredToolFor(state);
    }

    private PlayerNpcEntity playerNpcForTempTableCheck() {
        return mob instanceof PlayerNpcEntity playerNpc ? playerNpc : null;
    }

    private boolean canStandAt(ServerLevel serverLevel, BlockPos pos) {
        if (!serverLevel.isInWorldBounds(pos) || !serverLevel.getWorldBorder().isWithinBounds(pos)) {
            return false;
        }

        BlockState feet = serverLevel.getBlockState(pos);
        BlockState head = serverLevel.getBlockState(pos.above());
        BlockPos floorPos = pos.below();
        return feet.getCollisionShape(serverLevel, pos).isEmpty()
                && head.getCollisionShape(serverLevel, pos.above()).isEmpty()
                && feet.getFluidState().isEmpty()
                && head.getFluidState().isEmpty()
                && serverLevel.getBlockState(floorPos).isSolidRender(serverLevel, floorPos);
    }

    private boolean isProtectedHomeBlock(BlockPos pos) {
        if (!(mob instanceof PlayerNpcEntity playerNpc)) {
            return false;
        }
        Optional<PlayerNpcHomeUtil.HomeArea> homeArea = PlayerNpcHomeUtil.getHome(playerNpc);
        return homeArea.isPresent() && PlayerNpcHomeUtil.isInside(homeArea.get(), pos);
    }

    private boolean hasRequiredToolFor(BlockState state) {
        return !state.is(BlockTags.MINEABLE_WITH_PICKAXE) || hasTool(PickaxeItem.class);
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
        if (toolClass.isInstance(mob.getMainHandItem().getItem())) {
            return true;
        }
        if (restorePreviousMainHandForTool(toolClass)) {
            return true;
        }

        ItemStack tool = InventoryUtils.consumeItem(mob, stack -> toolClass.isInstance(stack.getItem()), 1)
                .orElse(ItemStack.EMPTY);
        if (tool.isEmpty()) {
            return false;
        }

        setTemporaryMainHand(tool);
        return true;
    }

    private void equipEmptyHandForMining() {
        if (mob.getMainHandItem().isEmpty()) {
            return;
        }

        setTemporaryMainHand(ItemStack.EMPTY);
    }

    private void setTemporaryMainHand(ItemStack stack) {
        ItemStack currentMainHand = mob.getMainHandItem().copy();
        if (!usingTemporaryTool) {
            previousMainHand = currentMainHand;
            usingTemporaryTool = true;
        } else if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, previousMainHand)
                && !InventoryUtils.addItem(mob, currentMainHand)) {
            mob.spawnAtLocation(currentMainHand);
        }

        mob.setItemSlot(EquipmentSlot.MAINHAND, stack);
    }

    private boolean restorePreviousMainHandForTool(Class<?> toolClass) {
        if (!usingTemporaryTool || !toolClass.isInstance(previousMainHand.getItem())) {
            return false;
        }

        ItemStack currentMainHand = mob.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, previousMainHand)
                && !InventoryUtils.addItem(mob, currentMainHand)) {
            mob.spawnAtLocation(currentMainHand);
        }

        mob.setItemSlot(EquipmentSlot.MAINHAND, previousMainHand.copy());
        previousMainHand = ItemStack.EMPTY;
        usingTemporaryTool = false;
        return true;
    }

    private void restorePreviousMainHand() {
        if (!usingTemporaryTool) {
            return;
        }

        ItemStack currentMainHand = mob.getMainHandItem().copy();
        if (!currentMainHand.isEmpty()
                && !ItemStack.isSameItemSameTags(currentMainHand, previousMainHand)
                && !InventoryUtils.addItem(mob, currentMainHand)) {
            mob.spawnAtLocation(currentMainHand);
        }

        mob.setItemSlot(EquipmentSlot.MAINHAND, previousMainHand.copy());
        previousMainHand = ItemStack.EMPTY;
        usingTemporaryTool = false;
    }

    private boolean hasTool(Class<?> toolClass) {
        if (toolClass.isInstance(mob.getMainHandItem().getItem())) {
            return true;
        }
        if (usingTemporaryTool && toolClass.isInstance(previousMainHand.getItem())) {
            return true;
        }
        return InventoryUtils.hasItem(mob, stack -> toolClass.isInstance(stack.getItem()));
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        float hardness = state.getDestroySpeed(serverLevel, pos);
        if (hardness < 0.0F) {
            return MAX_OBSTRUCTION_BREAK_TICKS;
        }

        ItemStack heldStack = mob.getMainHandItem();
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

    private void updateObstructionDetail(BlockState state, int requiredMineTicks) {
        if (pathObstructionPos == null || !(mob instanceof PlayerNpcEntity playerNpcEntity)) {
            return;
        }

        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String blockName = blockId == null ? state.getBlock().getDescriptionId() : blockId.toString();
        playerNpcEntity.setCurrentAiDetail(String.format(
                java.util.Locale.ROOT,
                "clearing burn path %s\n@ %d %d %d %d/%dt\ncooldown if failed: 15s",
                blockName,
                pathObstructionPos.getX(),
                pathObstructionPos.getY(),
                pathObstructionPos.getZ(),
                Math.min(obstructionMineTicks, requiredMineTicks),
                requiredMineTicks
        ));
    }

    private void updateMovingDetail() {
        if (!(mob instanceof PlayerNpcEntity playerNpcEntity) || targetItem == null || targetItem.getItem().isEmpty()) {
            return;
        }

        ItemStack stack = targetItem.getItem();
        BlockPos pos = targetItem.blockPosition();
        boolean collecting = shouldPickupOrEquipInsteadOfBurn(stack);
        playerNpcEntity.setCurrentAiState(collecting ? "ai.player_npc.collecting_item" : "ai.player_npc.burning_item");
        playerNpcEntity.setCurrentAiDetail(
                (collecting ? "moving to " : "moving to burn ")
                        + stack.getHoverName().getString()
                        + " @ "
                        + pos.getX()
                        + " "
                        + pos.getY()
                        + " "
                        + pos.getZ()
        );
    }

    private void tryBroadcastBurnMessage(ServerLevel serverLevel, ItemStack burnedStack) {
        if (!SmartNpcConfig.TURN_ON_NPC_CHAT.get()) return;
        if (!(mob instanceof PlayerNpcEntity)) return;
        if (mob.getRandom().nextFloat() >= 0.05F) return;

        String key = burnMessageKeys.get(mob.getRandom().nextInt(burnMessageKeys.size()));

        serverLevel.getServer().getPlayerList().broadcastSystemMessage(
                Component.empty()
                        .append(Component.literal("<"))
                        .append(mob.getDisplayName())
                        .append(Component.literal("> "))
                        .append(Component.translatable(key, burnedStack.getHoverName())),
                false
        );
    }

    private void restoreMainWeapon(boolean addIdleCooldown) {
        ItemStack weapon = getCachedMainWeapon();

        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            if (addIdleCooldown) {
                playerNpcEntity.setPlayingIdleCooldown(playerNpcEntity.getPlayingIdleCooldown() + 40);
            }
        }

        if ((weapon == null || weapon.isEmpty()) && !burnToolRestoreItem.isEmpty()) {
            weapon = burnToolRestoreItem;
        }

        if (weapon != null && !weapon.isEmpty()) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, weapon.copy());
            cacheMainWeapon(weapon);
        } else if (isBurnTool(mob.getMainHandItem())) {
            mob.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    private ItemEntity findTargetItem() {
        List<ItemEntity> items = mob.level().getEntitiesOfClass(
                ItemEntity.class,
                mob.getBoundingBox().inflate(searchRadius),
                e -> e.isAlive()
                        && !e.hasPickUpDelay()
                        && e.onGround()
                        && !e.getItem().isEmpty()
                        && (shouldPickupOrEquipInsteadOfBurn(e.getItem())
                        || (hasAnyBurnTool() && !shouldReserveInsteadOfBurn(e.getItem())))
        );

        if (items.isEmpty()) return null;
        return items.get(mob.getRandom().nextInt(items.size()));
    }

    private void igniteGroundAtItem(ServerLevel serverLevel) {
        if (targetItem == null || !targetItem.isAlive() || targetItem.getItem().isEmpty()) {
            return;
        }

        BurnTarget burnTarget = selectBurnTarget(serverLevel, targetItem.blockPosition());
        if (burnTarget == null || !equipBurnTool(burnTarget.tool())) {
            targetItem = null;
            return;
        }

        burningStack = targetItem.getItem().copy();
        burningStack.setCount(Math.min(burningStack.getCount(), 1));
        firePos = burnTarget.pos();
        burnTicks = 24;
        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            playerNpcEntity.setCurrentAiState("ai.player_npc.burning_item");
        }

        mob.getNavigation().stop();
        mob.getLookControl().setLookAt(firePos.getX() + 0.5D, firePos.getY() + 0.5D, firePos.getZ() + 0.5D, 40.0F, 40.0F);
        mob.swing(InteractionHand.MAIN_HAND, true);
        if (burnTarget.tool() == BurnTool.LAVA_BUCKET) {
            serverLevel.setBlockAndUpdate(firePos, Blocks.LAVA.defaultBlockState());
            convertActiveLavaBucketToEmptyBucket();
            serverLevel.playSound(null, firePos, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.HOSTILE, 1.0F, 1.0F);
        } else {
            serverLevel.setBlockAndUpdate(firePos, Blocks.FIRE.defaultBlockState());
            serverLevel.sendParticles(ParticleTypes.FLAME, firePos.getX() + 0.5D, firePos.getY() + 0.2D, firePos.getZ() + 0.5D, 8, 0.25D, 0.1D, 0.25D, 0.01D);
            serverLevel.playSound(null, firePos, SoundEvents.FLINTANDSTEEL_USE, SoundSource.HOSTILE, 1.0F, 1.0F);
        }
    }

    private void tickBurningGround(ServerLevel serverLevel) {
        burnTicks--;
        if (firePos != null && burnTicks == 10) {
            burnItemsOnFire(serverLevel);
        }
        if (burnTicks > 0) {
            return;
        }

        clearTemporaryFire();
        if (!burningStack.isEmpty()) {
            tryBroadcastBurnMessage(serverLevel, burningStack);
        }
        burningStack = ItemStack.EMPTY;
        targetItem = null;
        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            playerNpcEntity.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
        }
    }

    private void burnItemsOnFire(ServerLevel serverLevel) {
        if (firePos == null) {
            return;
        }

        List<ItemEntity> burningItems = serverLevel.getEntitiesOfClass(
                ItemEntity.class,
                new net.minecraft.world.phys.AABB(firePos).inflate(0.75D),
                item -> item.isAlive() && !item.getItem().isEmpty()
        );

        if (burningItems.isEmpty()) {
            return;
        }

        ItemEntity item = burningItems.get(0);
        burningStack = item.getItem().copy();
        burningStack.setCount(Math.min(burningStack.getCount(), 1));
        item.discard();
        mob.swing(InteractionHand.MAIN_HAND, true);
    }

    private void clearTemporaryFire() {
        if (firePos != null
                && mob.level() instanceof ServerLevel serverLevel
                && (serverLevel.getBlockState(firePos).is(Blocks.FIRE) || serverLevel.getBlockState(firePos).is(Blocks.LAVA))) {
            mob.swing(InteractionHand.MAIN_HAND, true);
            serverLevel.removeBlock(firePos, false);
        }
    }

    private BlockPos findFirePos(ServerLevel serverLevel, BlockPos itemPos) {
        BlockPos[] candidates = {
                itemPos,
                itemPos.above(),
                itemPos.below().above()
        };

        for (BlockPos candidate : candidates) {
            if (serverLevel.getBlockState(candidate).isAir()
                    && Blocks.FIRE.defaultBlockState().canSurvive(serverLevel, candidate)) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private BurnTarget selectBurnTarget(ServerLevel serverLevel, BlockPos itemPos) {
        if (InventoryUtils.hasItem(mob, Items.FLINT_AND_STEEL)) {
            BlockPos fire = findFirePos(serverLevel, itemPos);
            if (fire != null) {
                return new BurnTarget(BurnTool.FLINT_AND_STEEL, fire);
            }
        }

        if (InventoryUtils.hasItem(mob, Items.LAVA_BUCKET)) {
            BlockPos lava = findLavaPos(serverLevel, itemPos);
            if (lava != null) {
                return new BurnTarget(BurnTool.LAVA_BUCKET, lava);
            }
        }

        return null;
    }

    private BlockPos findLavaPos(ServerLevel serverLevel, BlockPos itemPos) {
        BlockPos[] candidates = {
                itemPos,
                itemPos.above()
        };

        for (BlockPos candidate : candidates) {
            if (serverLevel.isInWorldBounds(candidate)
                    && serverLevel.getWorldBorder().isWithinBounds(candidate)
                    && serverLevel.getBlockState(candidate).isAir()) {
                return candidate.immutable();
            }
        }
        return null;
    }

    private boolean equipBurnTool(BurnTool tool) {
        if (equippedBurnTool && burnTool == tool) {
            return true;
        }

        Item item = tool == BurnTool.LAVA_BUCKET ? Items.LAVA_BUCKET : Items.FLINT_AND_STEEL;
        ItemStack consumed = InventoryUtils.consumeItem(mob, item, 1).orElse(ItemStack.EMPTY);
        if (consumed.isEmpty()) {
            return false;
        }

        rememberRestoreItemBeforeBurnTool();
        equippedBurnTool = true;
        burnTool = tool;
        activeBurnToolStack = consumed.copy();
        activeBurnToolStack.setCount(1);

        mob.setItemSlot(EquipmentSlot.MAINHAND, activeBurnToolStack.copy());
        return true;
    }

    private boolean hasAnyBurnTool() {
        return InventoryUtils.hasItem(mob, Items.FLINT_AND_STEEL)
                || InventoryUtils.hasItem(mob, Items.LAVA_BUCKET);
    }

    private void convertActiveLavaBucketToEmptyBucket() {
        if (burnTool == BurnTool.LAVA_BUCKET && !activeBurnToolStack.isEmpty()) {
            activeBurnToolStack = ItemStack.EMPTY;
            giveOrDrop(new ItemStack(Items.BUCKET));
        }
    }

    private void returnActiveBurnToolIfNeeded() {
        if (activeBurnToolStack.isEmpty()) {
            return;
        }

        giveOrDrop(activeBurnToolStack);
        activeBurnToolStack = ItemStack.EMPTY;
    }

    private void giveOrDrop(ItemStack stack) {
        if (!InventoryUtils.addItem(mob, stack)) {
            mob.spawnAtLocation(stack);
        }
    }

    private boolean shouldPickupOrEquipInsteadOfBurn(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        if (npcInventoryCanAccept(stack)) {
            return true;
        }

        if (mainWeaponIsEmpty() && isUsefulWeapon(stack)) {
            return true;
        }

        return emptyArmorSlotCanUse(stack);
    }

    private boolean shouldReserveInsteadOfBurn(ItemStack stack) {
        return InventoryUtils.isInventoryBackedSupplyDrop(stack);
    }

    private boolean tryHandleItemWithoutBurning(ItemEntity itemEntity) {
        if (itemEntity == null || !itemEntity.isAlive() || itemEntity.getItem().isEmpty()) {
            return false;
        }

        if (mainWeaponIsEmpty() && isUsefulWeapon(itemEntity.getItem())) {
            return tryEquipWeaponFromGround(itemEntity);
        }

        if (emptyArmorSlotCanUse(itemEntity.getItem())) {
            return tryEquipArmorFromGround(itemEntity);
        }

        if (npcInventoryCanAccept(itemEntity.getItem())) {
            return tryInsertIntoNpcInventory(itemEntity);
        }

        return false;
    }

    private boolean tryEquipWeaponFromGround(ItemEntity itemEntity) {
        ItemStack groundStack = itemEntity.getItem();

        if (groundStack.isEmpty() || !isUsefulWeapon(groundStack)) {
            return false;
        }

        ItemStack equipStack = groundStack.copy();
        equipStack.setCount(1);

        mob.setItemSlot(EquipmentSlot.MAINHAND, equipStack.copy());

        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            playerNpcEntity.setMainWeaponItem(equipStack.copy());
        }

        groundStack.shrink(1);

        if (groundStack.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setItem(groundStack);
        }

        mob.swing(InteractionHand.MAIN_HAND);

        mob.level().playSound(
                null,
                mob.blockPosition(),
                SoundEvents.ITEM_PICKUP,
                SoundSource.HOSTILE,
                0.2F,
                1.0F
        );

        return true;
    }

    private boolean tryEquipArmorFromGround(ItemEntity itemEntity) {
        ItemStack groundStack = itemEntity.getItem();

        if (groundStack.isEmpty()) {
            return false;
        }

        EquipmentSlot slot = LivingEntity.getEquipmentSlotForItem(groundStack);

        if (slot.getType() != EquipmentSlot.Type.ARMOR) {
            return false;
        }

        if (!mob.getItemBySlot(slot).isEmpty()) {
            return false;
        }

        ItemStack equipStack = groundStack.copy();
        equipStack.setCount(1);

        mob.setItemSlot(slot, equipStack.copy());

        groundStack.shrink(1);

        if (groundStack.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setItem(groundStack);
        }

        mob.swing(InteractionHand.MAIN_HAND);

        mob.level().playSound(
                null,
                mob.blockPosition(),
                SoundEvents.ITEM_PICKUP,
                SoundSource.HOSTILE,
                0.2F,
                1.0F
        );

        return true;
    }

    private boolean tryInsertIntoNpcInventory(ItemEntity itemEntity) {
        SimpleContainer inventory = getNpcInventory();

        if (inventory == null || itemEntity == null || itemEntity.getItem().isEmpty()) {
            return false;
        }

        ItemStack remaining = itemEntity.getItem().copy();
        int originalCount = remaining.getCount();

        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slotStack = inventory.getItem(i);

            if (!slotStack.isEmpty()
                    && ItemStack.isSameItemSameTags(slotStack, remaining)
                    && slotStack.getCount() < slotStack.getMaxStackSize()) {
                int transferable = Math.min(
                        remaining.getCount(),
                        slotStack.getMaxStackSize() - slotStack.getCount()
                );

                slotStack.grow(transferable);
                remaining.shrink(transferable);
            }
        }

        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slotStack = inventory.getItem(i);
            if (!slotStack.isEmpty()) {
                continue;
            }

            int transferable = Math.min(
                    remaining.getCount(),
                    Math.min(remaining.getMaxStackSize(), inventory.getMaxStackSize())
            );

            ItemStack inserted = remaining.copy();
            inserted.setCount(transferable);

            inventory.setItem(i, inserted);
            remaining.shrink(transferable);
        }

        if (remaining.getCount() == originalCount) {
            return false;
        }

        inventory.setChanged();

        if (remaining.isEmpty()) {
            itemEntity.discard();
        } else {
            itemEntity.setItem(remaining);
        }

        mob.swing(InteractionHand.MAIN_HAND);

        mob.level().playSound(
                null,
                mob.blockPosition(),
                SoundEvents.ITEM_PICKUP,
                SoundSource.HOSTILE,
                0.2F,
                1.0F
        );

        return true;
    }

    private boolean npcInventoryCanAccept(ItemStack incoming) {
        SimpleContainer inventory = getNpcInventory();

        if (inventory == null || incoming.isEmpty()) {
            return false;
        }

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

    private SimpleContainer getNpcInventory() {
        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            return playerNpcEntity.getInventory();
        }

        return null;
    }

    private ItemStack getCachedMainWeapon() {
        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            return playerNpcEntity.getMainWeaponItem();
        }

        return ItemStack.EMPTY;
    }

    private void cacheMainWeapon(ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) {
            return;
        }

        if (mob instanceof PlayerNpcEntity playerNpcEntity) {
            playerNpcEntity.setMainWeaponItem(weapon.copy());
        }

    }

    private void rememberRestoreItemBeforeBurnTool() {
        if (!burnToolRestoreItem.isEmpty()) {
            return;
        }

        ItemStack cachedWeapon = getCachedMainWeapon();
        if (!cachedWeapon.isEmpty()) {
            burnToolRestoreItem = cachedWeapon.copy();
            return;
        }

        ItemStack currentMainHand = mob.getMainHandItem();
        if (!currentMainHand.isEmpty() && !isBurnTool(currentMainHand) && isUsefulWeapon(currentMainHand)) {
            burnToolRestoreItem = currentMainHand.copy();
            cacheMainWeapon(currentMainHand);
        }
    }

    private boolean mainWeaponIsEmpty() {
        if (!getCachedMainWeapon().isEmpty()) {
            return false;
        }

        return mob.getMainHandItem().isEmpty()
                || isBurnTool(mob.getMainHandItem());
    }

    private boolean isFlintAndSteel(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == Items.FLINT_AND_STEEL;
    }

    private boolean isLavaBucket(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() == Items.LAVA_BUCKET;
    }

    private boolean isBurnTool(ItemStack stack) {
        return isFlintAndSteel(stack) || isLavaBucket(stack);
    }

    private boolean isUsefulWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        return stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof DiggerItem
                || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem;
    }

    private boolean emptyArmorSlotCanUse(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        EquipmentSlot slot = LivingEntity.getEquipmentSlotForItem(stack);

        if (slot.getType() != EquipmentSlot.Type.ARMOR) {
            return false;
        }

        return mob.getItemBySlot(slot).isEmpty();
    }

    private record BurnTarget(BurnTool tool, BlockPos pos) {}
}
