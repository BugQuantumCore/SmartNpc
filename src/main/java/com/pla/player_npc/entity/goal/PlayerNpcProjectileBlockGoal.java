package com.pla.player_npc.entity.goal;

import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Queue;
import java.util.function.BiFunction;

public class PlayerNpcProjectileBlockGoal extends Goal {
    private static final double PROJECTILE_SCAN_RADIUS = 6.0D;
    private static final double PROJECTILE_SCAN_RADIUS_SQR = PROJECTILE_SCAN_RADIUS * PROJECTILE_SCAN_RADIUS;
    private static final double INCOMING_DOT_THRESHOLD = 0.35D;
    private static final int PLACE_INTERVAL_TICKS = 2;

    private final PlayerNpcEntity playerNpc;
    private final Queue<BlockPos> placementQueue = new ArrayDeque<>();
    private Projectile projectile;
    private int placeDelayTicks;
    private boolean finished;

    public PlayerNpcProjectileBlockGoal(PlayerNpcEntity playerNpc) {
        this.playerNpc = playerNpc;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)
                || !this.playerNpc.isAlive()
                || this.playerNpc.isNoAi()
                || this.playerNpc.isPassenger()
                || !this.playerNpc.onGround()
                || this.playerNpc.isHealing()
                || this.playerNpc.hasPlaceBlockParryCooldown()
                || !InventoryUtils.hasItem(this.playerNpc, this::isDefensiveBlock)
                || this.playerNpc.getRandom().nextDouble() > this.playerNpc.getPlaceBlockToParryChance()) {
            return false;
        }

        Projectile incomingProjectile = this.findIncomingProjectile(serverLevel);
        if (incomingProjectile == null) {
            return false;
        }

        List<BlockPos> placements = this.findPlacementPattern(serverLevel, incomingProjectile);
        if (placements.isEmpty()) {
            return false;
        }

        this.projectile = incomingProjectile;
        this.placementQueue.clear();
        this.placementQueue.addAll(placements);
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return !this.finished
                && this.playerNpc.isAlive()
                && !this.playerNpc.isNoAi()
                && this.playerNpc.level() instanceof ServerLevel
                && !this.placementQueue.isEmpty()
                && InventoryUtils.hasItem(this.playerNpc, this::isDefensiveBlock);
    }

    @Override
    public void start() {
        this.finished = false;
        this.placeDelayTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.playerNpc.setPlaceBlockParryCooldown();
        this.playerNpc.setCurrentAiState("ai.player_npc.blocking_projectile");
    }

    @Override
    public void tick() {
        if (!(this.playerNpc.level() instanceof ServerLevel serverLevel)) {
            this.finished = true;
            return;
        }

        if (this.projectile != null && this.projectile.isAlive()) {
            this.playerNpc.getLookControl().setLookAt(this.projectile, 60.0F, 60.0F);
        }

        if (this.placeDelayTicks > 0) {
            this.placeDelayTicks--;
            return;
        }

        while (!this.placementQueue.isEmpty()) {
            BlockPos placePos = this.placementQueue.poll();
            if (this.placeIfReplaceable(serverLevel, placePos)) {
                this.placeDelayTicks = PLACE_INTERVAL_TICKS;
                return;
            }
        }

        this.finished = true;
    }

    @Override
    public void stop() {
        this.placementQueue.clear();
        this.projectile = null;
        this.placeDelayTicks = 0;
        this.finished = false;
        this.playerNpc.setCurrentAiState(PlayerNpcEntity.AI_IDLE);
    }

    private Projectile findIncomingProjectile(ServerLevel serverLevel) {
        List<Projectile> projectiles = serverLevel.getEntitiesOfClass(
                Projectile.class,
                this.playerNpc.getBoundingBox().inflate(PROJECTILE_SCAN_RADIUS, 3.0D, PROJECTILE_SCAN_RADIUS),
                this::isThreateningProjectile
        );

        Projectile closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Projectile projectile : projectiles) {
            double distance = this.playerNpc.distanceToSqr(projectile);
            if (distance < closestDistance) {
                closest = projectile;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private boolean isThreateningProjectile(Projectile projectile) {
        if (!projectile.isAlive() || projectile.isRemoved() || this.playerNpc.distanceToSqr(projectile) > PROJECTILE_SCAN_RADIUS_SQR) {
            return false;
        }

        Entity owner = projectile.getOwner();
        if (owner == this.playerNpc || (owner != null && this.playerNpc.isAlliedTo(owner))) {
            return false;
        }

        Vec3 velocity = projectile.getDeltaMovement();
        Vec3 toNpc = this.playerNpc.position().add(0.0D, this.playerNpc.getBbHeight() * 0.5D, 0.0D).subtract(projectile.position());
        if (toNpc.lengthSqr() <= 3.0D * 3.0D) {
            return true;
        }
        if (velocity.lengthSqr() < 1.0E-4D || toNpc.lengthSqr() < 1.0E-4D) {
            return false;
        }

        return velocity.normalize().dot(toNpc.normalize()) > INCOMING_DOT_THRESHOLD;
    }

    private List<BlockPos> findPlacementPattern(ServerLevel serverLevel, Projectile projectile) {
        int pattern = this.playerNpc.getRandom().nextInt(11);
        int rotation = this.playerNpc.getRandom().nextInt(4);
        BiFunction<Integer, Integer, int[]> toWorld = this.getOffsetTransform(rotation);
        BlockPos projectileXZ = BlockPos.containing(projectile.getX(), 0.0D, projectile.getZ());
        int surfaceY = serverLevel.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, projectileXZ).getY();
        int topY = Math.max(surfaceY, Mth.floor(projectile.getY()));
        topY = Math.min(topY, surfaceY + 3);

        List<BlockPos> placements = new ArrayList<>();
        for (int y = surfaceY; y <= topY; y++) {
            int layer = y - surfaceY;
            BlockPos center = new BlockPos(projectileXZ.getX(), y, projectileXZ.getZ());
            if (!this.canPlaceAt(serverLevel, center)) {
                break;
            }

            placements.add(center.immutable());
            for (int[] offset : this.extraOffsetsForPattern(pattern, layer)) {
                int[] worldOffset = toWorld.apply(offset[0], offset[1]);
                BlockPos extra = center.offset(worldOffset[0], 0, worldOffset[1]);
                if (this.canPlaceAt(serverLevel, extra)) {
                    placements.add(extra.immutable());
                }
            }
        }
        return placements;
    }

    private int[][] extraOffsetsForPattern(int pattern, int layer) {
        return switch (pattern) {
            case 0 -> new int[][]{};
            case 1 -> layer == 3 ? new int[][]{{1, 0}} : new int[][]{};
            case 2 -> {
                if (layer == 0) yield new int[][]{{-1, 0}, {1, 0}, {2, 0}};
                if (layer == 1) yield new int[][]{{1, 0}};
                yield new int[][]{};
            }
            case 3 -> layer == 1 ? new int[][]{{-1, 0}, {1, 0}} : new int[][]{};
            case 4 -> layer == 0 ? new int[][]{{-1, 0}, {1, 0}} : new int[][]{};
            case 5 -> new int[][]{{1, 0}};
            case 6 -> layer <= 1 ? new int[][]{{1, 0}} : new int[][]{};
            case 7 -> layer == 0 ? new int[][]{{1, 0}} : new int[][]{};
            case 8 -> layer == 1 ? new int[][]{{1, 0}} : new int[][]{};
            case 9 -> layer == 0 ? new int[][]{{-1, 0}} : new int[][]{};
            default -> layer == 1 ? new int[][]{{-1, 0}} : new int[][]{};
        };
    }

    private BiFunction<Integer, Integer, int[]> getOffsetTransform(int rotation) {
        Direction facing = this.playerNpc.getDirection();
        int forwardX = facing.getStepX();
        int forwardZ = facing.getStepZ();
        int rightX = -forwardZ;
        int rightZ = forwardX;

        for (int i = 0; i < rotation; i++) {
            int nextForwardX = rightX;
            int nextForwardZ = rightZ;
            int nextRightX = -forwardZ;
            int nextRightZ = forwardX;
            forwardX = nextForwardX;
            forwardZ = nextForwardZ;
            rightX = nextRightX;
            rightZ = nextRightZ;
        }

        int finalRightX = rightX;
        int finalForwardX = forwardX;
        int finalRightZ = rightZ;
        int finalForwardZ = forwardZ;
        return (right, forward) -> new int[]{
                right * finalRightX + forward * finalForwardX,
                right * finalRightZ + forward * finalForwardZ
        };
    }

    private boolean placeIfReplaceable(ServerLevel serverLevel, BlockPos pos) {
        if (!this.canPlaceAt(serverLevel, pos)) {
            return false;
        }

        ItemStack blockStack = InventoryUtils.consumeItem(this.playerNpc, this::isDefensiveBlock, 1).orElse(ItemStack.EMPTY);
        BlockState blockState = InventoryUtils.getBlockState(blockStack);
        if (blockStack.isEmpty() || blockState == null || !blockState.canOcclude()) {
            this.giveOrDrop(blockStack);
            return false;
        }

        serverLevel.setBlockAndUpdate(pos, blockState);
        this.playerNpc.swing(InteractionHand.MAIN_HAND, true);
        serverLevel.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.9F, 1.0F);
        return true;
    }

    private boolean canPlaceAt(ServerLevel serverLevel, BlockPos pos) {
        return serverLevel.isInWorldBounds(pos)
                && serverLevel.getWorldBorder().isWithinBounds(pos)
                && serverLevel.getBlockState(pos).canBeReplaced();
    }

    private boolean isDefensiveBlock(ItemStack stack) {
        if (stack.isEmpty()
                || !(stack.getItem() instanceof BlockItem blockItem)
                || stack.is(Items.CRAFTING_TABLE)
                || stack.is(Items.CHEST)
                || stack.is(Items.FURNACE)
                || stack.getItem() instanceof BedItem
                || blockItem.getBlock().defaultBlockState().is(Blocks.TORCH)) {
            return false;
        }

        return blockItem.getBlock().defaultBlockState().canOcclude();
    }

    private void giveOrDrop(ItemStack stack) {
        if (!stack.isEmpty() && !InventoryUtils.addItem(this.playerNpc, stack)) {
            this.playerNpc.spawnAtLocation(stack);
        }
    }
}
