package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

public class ExploreCaveOreGoal extends Goal {
    private static final int SEARCH_RADIUS = 16;
    private static final int SEARCH_DOWN = 18;
    private static final int SEARCH_UP = 6;
    private static final double BREAK_DISTANCE_SQR = 3.0D * 3.0D;
    private static final int COOLDOWN_TICKS = 20 * 12;
    private static final int REPATH_INTERVAL_TICKS = 20;
    private static final int MAX_MINE_TICKS = 20 * 12;
    private static final int MAX_FAILED_PATH_TICKS = 20 * 3;

    private final PlayerNpcEntity playerNpc;
    private final double speed;
    private BlockPos targetPos;
    private BlockPos standPos;
    private ItemStack previousMainHand = ItemStack.EMPTY;
    private int mineTicks;
    private int repathTicks;
    private int failedPathTicks;
    private boolean usingTemporaryPickaxe;

    public ExploreCaveOreGoal(PlayerNpcEntity playerNpc, double speed) {
        this.playerNpc = playerNpc;
        this.speed = Math.min(speed, 1.0D);
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
                || this.playerNpc.getOreMiningCooldown() > 0
                || !this.hasPickaxe()
                || this.inventoryIsMostlyFull()) {
            return false;
        }

        OreTarget target = this.findOreTarget(serverLevel);
        if (target == null) {
            return false;
        }

        this.targetPos = target.targetPos();
        this.standPos = target.standPos();
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return this.targetPos != null
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && this.playerNpc.getTarget() == null
                && this.mineTicks < MAX_MINE_TICKS;
    }

    @Override
    public void start() {
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.previousMainHand = ItemStack.EMPTY;
        this.usingTemporaryPickaxe = false;
        this.playerNpc.setCurrentAiState("ai.player_npc.exploring_cave");
        if (this.targetPos != null && this.playerNpc.level() instanceof ServerLevel serverLevel) {
            this.equipPickaxe();
            this.updateTaskDetail(serverLevel);
        }
        this.moveToTarget();
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel) || this.targetPos == null) {
            return;
        }

        BlockState state = serverLevel.getBlockState(this.targetPos);
        if (!this.isOreBlock(state)) {
            this.targetPos = null;
            return;
        }

        this.playerNpc.getLookControl().setLookAt(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D,
                40.0F,
                40.0F
        );

        if (this.playerNpc.distanceToSqr(this.targetPos.getX() + 0.5D, this.targetPos.getY() + 0.5D, this.targetPos.getZ() + 0.5D) > BREAK_DISTANCE_SQR) {
            if (this.repathTicks-- <= 0 || this.playerNpc.getNavigation().isDone()) {
                if (this.moveToTarget()) {
                    this.failedPathTicks = 0;
                } else {
                    this.failedPathTicks += REPATH_INTERVAL_TICKS;
                    if (this.failedPathTicks >= MAX_FAILED_PATH_TICKS) {
                        this.targetPos = null;
                    }
                }
                this.repathTicks = REPATH_INTERVAL_TICKS;
            }
            this.updateTaskDetail(serverLevel);
            return;
        }

        this.playerNpc.getNavigation().stop();
        if (this.mineTicks % 8 == 0) {
            this.playerNpc.triggerMainHandAttackAnimation();
            serverLevel.levelEvent(2001, this.targetPos, Block.getId(state));
        }

        this.mineTicks++;
        this.updateTaskDetail(serverLevel);
        if (this.mineTicks < this.getRequiredMineTicks(serverLevel, state)) {
            return;
        }

        BlockPos minedPos = this.targetPos;
        if (serverLevel.destroyBlock(minedPos, true, this.playerNpc)) {
            this.playerNpc.hurtMainHandItem(1);
        }
        this.targetPos = null;
    }

    @Override
    public void stop() {
        this.restorePreviousMainHand();
        this.targetPos = null;
        this.standPos = null;
        this.mineTicks = 0;
        this.repathTicks = 0;
        this.failedPathTicks = 0;
        this.playerNpc.setOreMiningCooldown(COOLDOWN_TICKS + this.playerNpc.getRandom().nextInt(20 * 8));
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private OreTarget findOreTarget(ServerLevel serverLevel) {
        List<OreTarget> candidates = new ArrayList<>();
        BlockPos center = this.playerNpc.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-SEARCH_RADIUS, -SEARCH_DOWN, -SEARCH_RADIUS),
                center.offset(SEARCH_RADIUS, SEARCH_UP, SEARCH_RADIUS))) {
            BlockPos immutable = pos.immutable();
            if (!this.isCaveOre(serverLevel, immutable)) {
                continue;
            }

            BlockPos stand = this.findStandPos(serverLevel, immutable);
            if (stand != null) {
                candidates.add(new OreTarget(immutable, stand));
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator
                .comparingInt((OreTarget target) -> this.orePriority(serverLevel.getBlockState(target.targetPos())))
                .thenComparingDouble(target -> center.distSqr(target.standPos())));
        return candidates.get(this.playerNpc.getRandom().nextInt(Math.min(candidates.size(), 6)));
    }

    private boolean isCaveOre(ServerLevel serverLevel, BlockPos pos) {
        BlockState state = serverLevel.getBlockState(pos);
        if (!this.isOreBlock(state) || !this.hasAdjacentAir(serverLevel, pos)) {
            return false;
        }

        return pos.getY() <= serverLevel.getSeaLevel() + 12 || !serverLevel.canSeeSky(pos.above());
    }

    private boolean hasAdjacentAir(ServerLevel serverLevel, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (serverLevel.getBlockState(pos.relative(direction)).isAir()) {
                return true;
            }
        }
        return false;
    }

    private BlockPos findStandPos(ServerLevel serverLevel, BlockPos target) {
        List<BlockPos> candidates = new ArrayList<>();
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            candidates.add(target.relative(direction));
            candidates.add(target.relative(direction).above());
            candidates.add(target.relative(direction).below());
        }
        candidates.add(target.above());

        BlockPos center = this.playerNpc.blockPosition();
        candidates.sort(Comparator.comparingDouble(center::distSqr));
        for (BlockPos candidate : candidates) {
            BlockPos immutable = candidate.immutable();
            if (this.canStandAt(serverLevel, immutable)
                    && immutable.distSqr(target) <= BREAK_DISTANCE_SQR + 2.0D) {
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

    private boolean moveToTarget() {
        if (this.standPos == null) {
            return false;
        }

        return this.playerNpc.getNavigation().moveTo(this.standPos.getX() + 0.5D, this.standPos.getY(), this.standPos.getZ() + 0.5D, this.speed);
    }

    private boolean isOreBlock(BlockState state) {
        return state.is(Blocks.IRON_ORE)
                || state.is(Blocks.DEEPSLATE_IRON_ORE)
                || state.is(Blocks.COAL_ORE)
                || state.is(Blocks.DEEPSLATE_COAL_ORE)
                || state.is(Blocks.COPPER_ORE)
                || state.is(Blocks.DEEPSLATE_COPPER_ORE);
    }

    private int orePriority(BlockState state) {
        if (state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)) {
            return 0;
        }
        if (state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)) {
            return 1;
        }
        return 2;
    }

    private int getRequiredMineTicks(ServerLevel serverLevel, BlockState state) {
        float hardness = state.getDestroySpeed(serverLevel, this.targetPos);
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

        return Math.min(MAX_MINE_TICKS, Math.max(1, (int) Math.ceil(1.0F / progressPerTick)));
    }

    private boolean hasPickaxe() {
        return this.playerNpc.getMainHandItem().getItem() instanceof PickaxeItem
                || InventoryUtils.hasItem(this.playerNpc, stack -> stack.getItem() instanceof PickaxeItem);
    }

    private void equipPickaxe() {
        if (this.playerNpc.getMainHandItem().getItem() instanceof PickaxeItem) {
            return;
        }

        ItemStack pickaxe = this.playerNpc.consumeInventoryItem(stack -> stack.getItem() instanceof PickaxeItem, 1)
                .orElse(ItemStack.EMPTY);
        if (pickaxe.isEmpty()) {
            return;
        }

        this.previousMainHand = this.playerNpc.getMainHandItem().copy();
        this.usingTemporaryPickaxe = true;
        this.playerNpc.setItemSlot(EquipmentSlot.MAINHAND, pickaxe);
    }

    private void restorePreviousMainHand() {
        if (!this.usingTemporaryPickaxe) {
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
        this.usingTemporaryPickaxe = false;
    }

    private void updateTaskDetail(ServerLevel serverLevel) {
        if (this.targetPos == null) {
            this.playerNpc.setCurrentAiDetail("");
            return;
        }

        BlockState state = serverLevel.getBlockState(this.targetPos);
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        String blockName = blockId == null ? state.getBlock().getDescriptionId() : blockId.toString();
        int requiredMineTicks = this.getRequiredMineTicks(serverLevel, state);
        boolean inBreakRange = this.playerNpc.distanceToSqr(
                this.targetPos.getX() + 0.5D,
                this.targetPos.getY() + 0.5D,
                this.targetPos.getZ() + 0.5D
        ) <= BREAK_DISTANCE_SQR;
        this.playerNpc.setCurrentAiDetail(String.format(
                Locale.ROOT,
                "%s @ %d %d %d %s",
                blockName,
                this.targetPos.getX(),
                this.targetPos.getY(),
                this.targetPos.getZ(),
                inBreakRange ? String.format(Locale.ROOT, "%d/%dt", Math.min(this.mineTicks, requiredMineTicks), requiredMineTicks) : "walking"
        ));
    }

    private boolean inventoryIsMostlyFull() {
        SimpleContainer inventory = this.playerNpc.getInventory();
        int freeSlots = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) {
                freeSlots++;
            }
        }
        return freeSlots <= 2;
    }

    private record OreTarget(BlockPos targetPos, BlockPos standPos) {}
}
