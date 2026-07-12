package com.pla.smart_npc.entity.ai;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.InventoryUtils;
import com.pla.smart_npc.util.PlayerNpcCollisionUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class PillarUpAi {
    public enum TickResult {
        IDLE,
        RUNNING,
        PLACED,
        FAILED
    }

    private static final int JUMP_WINDUP_TICKS = 2;
    private static final int MAX_PLACE_WAIT_TICKS = 32;
    private static final int FORCE_PLACE_TICKS = 3;
    private static final double PLACE_CLEARANCE_Y = 0.65D;
    private static final double FALLBACK_PLACE_CLEARANCE_Y = 0.55D;
    private static final double CENTER_EPSILON = 0.05D;
    private static final double CENTER_BLOCKER_PADDING = 0.04D;
    private static final int COLLISION_BLOCKER_FAIL_TICKS = 8;

    private final PlayerNpcEntity playerNpc;
    private final ToolAi toolAi;
    private final PlacingBlockAi placingBlockAi;
    private final ItemLike blockItem;
    private final BlockState placeState;
    private BlockPos placePos;
    private BlockPos lastPlacedPos;
    private BlockPos lastFailureBlockerPos;
    private String lastFailureDetail = "";
    private int jumpDelayTicks;
    private int placeWaitTicks;

    public PillarUpAi(PlayerNpcEntity playerNpc, ToolAi toolAi, ItemLike blockItem, BlockState placeState) {
        this.playerNpc = playerNpc;
        this.toolAi = toolAi;
        this.placingBlockAi = new PlacingBlockAi(playerNpc);
        this.blockItem = blockItem;
        this.placeState = placeState;
    }

    public boolean isRunning() {
        return this.placePos != null;
    }

    public boolean canStart(ServerLevel serverLevel, BlockPos feet) {
        return this.startBlocker(serverLevel, feet).isBlank();
    }

    public String startBlocker(ServerLevel serverLevel, BlockPos feet) {
        if (!this.playerNpc.onGround()) {
            return "not on ground";
        }
        if (!serverLevel.isInWorldBounds(feet) || !serverLevel.getWorldBorder().isWithinBounds(feet)) {
            return "feet out of bounds " + posText(feet);
        }
        if (!this.hasBlock()) {
            return "missing block item " + this.blockItem.asItem().getDescriptionId();
        }

        String spaceBlocker = this.pillarSpaceBlocker(serverLevel, feet);
        if (!spaceBlocker.isBlank()) {
            return spaceBlocker;
        }
        String entityBlocker = this.entityBlockerText(serverLevel, feet);
        if (!entityBlocker.isBlank()) {
            return entityBlocker;
        }
        return "";
    }

    public BlockPos startBlockerPos(ServerLevel serverLevel, BlockPos feet) {
        if (feet == null) {
            return null;
        }
        if (!this.playerNpc.onGround()
                || !serverLevel.isInWorldBounds(feet)
                || !serverLevel.getWorldBorder().isWithinBounds(feet)) {
            return feet.immutable();
        }
        if (!this.hasBlock()) {
            return null;
        }

        BlockPos spaceBlocker = this.pillarSpaceBlockerPos(serverLevel, feet);
        if (spaceBlocker != null) {
            return spaceBlocker;
        }
        if (!this.entityBlockerText(serverLevel, feet).isBlank()) {
            return feet.immutable();
        }
        return null;
    }

    public boolean start(ServerLevel serverLevel, BlockPos feet) {
        if (!this.playerNpc.onGround() || !this.canStart(serverLevel, feet) || !this.toolAi.equipItem(this.blockItem)) {
            return false;
        }

        this.placePos = feet.immutable();
        this.lastPlacedPos = null;
        this.lastFailureBlockerPos = null;
        this.lastFailureDetail = "";
        this.jumpDelayTicks = JUMP_WINDUP_TICKS;
        this.placingBlockAi.resetDelay();
        this.placeWaitTicks = 0;
        this.playerNpc.getNavigation().stop();
        this.lookDownAt(this.placePos);
        return true;
    }

    public TickResult tick(ServerLevel serverLevel) {
        if (this.placePos == null) {
            return TickResult.IDLE;
        }

        if (this.jumpDelayTicks > 0) {
            if (!this.toolAi.equipItem(this.blockItem)) {
                return this.fail("missing block item during pillar", null);
            }
            if (!this.centerOnPillarBase(serverLevel, this.placePos)) {
                this.lookAtFailureBlocker();
                return TickResult.FAILED;
            }
            this.playerNpc.getNavigation().stop();
            this.lookDownAt(this.placePos);
            this.jumpDelayTicks--;
            if (this.jumpDelayTicks <= 0) {
                this.playerNpc.shortPillarJump();
            }
            return TickResult.RUNNING;
        }

        if (this.placingBlockAi.tickDelay(PlacingBlockAi.PILLAR_PLACE_DELAY)) {
            return TickResult.RUNNING;
        }

        this.placeWaitTicks++;
        if (this.placeWaitTicks > MAX_PLACE_WAIT_TICKS) {
            BlockPos blocker = this.findCurrentCollisionBlocker(serverLevel);
            return this.fail("pillar clearance timeout", blocker);
        }

        if (!this.hasPlacementClearance()) {
            BlockPos blocker = this.findCurrentCollisionBlocker(serverLevel);
            if (blocker != null && this.placeWaitTicks >= COLLISION_BLOCKER_FAIL_TICKS) {
                return this.fail("pillar jump blocked by " + blockText(serverLevel.getBlockState(blocker)), blocker);
            }
            this.lookDownAt(this.placePos);
            return TickResult.RUNNING;
        }

        if (!serverLevel.getBlockState(this.placePos).canBeReplaced()) {
            return this.fail("pillar base no longer replaceable", this.placePos);
        }
        if (!this.toolAi.equipItem(this.blockItem)) {
            return this.fail("missing block item before place", null);
        }
        if (!this.canPlaceWithoutClipping(serverLevel, this.placePos, this.placeState)) {
            return this.fail("pillar place clipping", this.findCurrentCollisionBlocker(serverLevel));
        }
        if (!this.consumeBlock()) {
            return this.fail("failed to consume pillar block", null);
        }

        if (!this.placingBlockAi.placeBlock(serverLevel, this.placePos, this.placeState)) {
            InventoryUtils.addItem(this.playerNpc.getInventory(), new ItemStack(this.blockItem));
            return this.fail("failed to set pillar block", this.placePos);
        }

        this.snapAbovePillarIfNeeded(this.placePos);
        this.lastPlacedPos = this.placePos.immutable();
        this.clear();
        return TickResult.PLACED;
    }

    public BlockPos consumeLastPlacedPos() {
        BlockPos placed = this.lastPlacedPos;
        this.lastPlacedPos = null;
        return placed == null ? null : placed.immutable();
    }

    public BlockPos consumeLastFailureBlockerPos() {
        BlockPos blocker = this.lastFailureBlockerPos;
        this.lastFailureBlockerPos = null;
        return blocker == null ? null : blocker.immutable();
    }

    public String consumeLastFailureDetail() {
        String detail = this.lastFailureDetail;
        this.lastFailureDetail = "";
        return detail;
    }

    public void clear() {
        this.placePos = null;
        this.jumpDelayTicks = 0;
        this.placingBlockAi.resetDelay();
        this.placeWaitTicks = 0;
    }

    public String detail() {
        if (this.placePos == null) {
            return "";
        }
        return "pillaring up @ "
                + this.placePos.getX() + " "
                + this.placePos.getY() + " "
                + this.placePos.getZ();
    }

    public boolean hasPillarSpace(ServerLevel serverLevel, BlockPos feet) {
        return this.pillarSpaceBlocker(serverLevel, feet).isBlank();
    }

    private String pillarSpaceBlocker(ServerLevel serverLevel, BlockPos feet) {
        BlockState feetState = serverLevel.getBlockState(feet);
        BlockState headState = serverLevel.getBlockState(feet.above());
        BlockState jumpHeadroomState = serverLevel.getBlockState(feet.above(2));
        if (!feetState.canBeReplaced()) {
            return "feet not replaceable " + blockText(feetState) + " " + posText(feet);
        }
        if (!feetState.getCollisionShape(serverLevel, feet).isEmpty()) {
            return "feet collision " + blockText(feetState) + " " + posText(feet);
        }
        if (!headState.getCollisionShape(serverLevel, feet.above()).isEmpty()) {
            return "head collision " + blockText(headState) + " " + posText(feet.above());
        }
        if (!jumpHeadroomState.getCollisionShape(serverLevel, feet.above(2)).isEmpty()) {
            return "jump collision " + blockText(jumpHeadroomState) + " " + posText(feet.above(2));
        }
        if (!feetState.getFluidState().isEmpty()) {
            return "feet fluid " + posText(feet);
        }
        if (!headState.getFluidState().isEmpty()) {
            return "head fluid " + posText(feet.above());
        }
        if (!jumpHeadroomState.getFluidState().isEmpty()) {
            return "jump fluid " + posText(feet.above(2));
        }
        return "";
    }

    private BlockPos pillarSpaceBlockerPos(ServerLevel serverLevel, BlockPos feet) {
        BlockState feetState = serverLevel.getBlockState(feet);
        BlockState headState = serverLevel.getBlockState(feet.above());
        BlockState jumpHeadroomState = serverLevel.getBlockState(feet.above(2));
        if (!feetState.canBeReplaced()
                || !feetState.getCollisionShape(serverLevel, feet).isEmpty()
                || !feetState.getFluidState().isEmpty()) {
            return feet.immutable();
        }
        if (!headState.getCollisionShape(serverLevel, feet.above()).isEmpty()
                || !headState.getFluidState().isEmpty()) {
            return feet.above().immutable();
        }
        if (!jumpHeadroomState.getCollisionShape(serverLevel, feet.above(2)).isEmpty()
                || !jumpHeadroomState.getFluidState().isEmpty()) {
            return feet.above(2).immutable();
        }
        return null;
    }

    private boolean hasBlock() {
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (mainHand.is(this.blockItem.asItem())) {
            return true;
        }
        for (int i = 0; i < this.playerNpc.getInventory().getContainerSize(); i++) {
            ItemStack stack = this.playerNpc.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(this.blockItem.asItem())) {
                return true;
            }
        }
        return false;
    }

    private boolean consumeBlock() {
        ItemStack mainHand = this.playerNpc.getMainHandItem();
        if (mainHand.is(this.blockItem.asItem())) {
            mainHand.shrink(1);
            return true;
        }
        return this.playerNpc.consumeInventoryItem(this.blockItem, 1).isPresent();
    }

    private boolean hasPlacementClearance() {
        if (this.placePos == null) {
            return false;
        }

        double clearedY = this.playerNpc.getBoundingBox().minY - this.placePos.getY();
        return clearedY >= PLACE_CLEARANCE_Y
                || this.placeWaitTicks >= FORCE_PLACE_TICKS
                && clearedY >= FALLBACK_PLACE_CLEARANCE_Y
                && this.playerNpc.getDeltaMovement().y <= 0.05D;
    }

    private boolean canPlaceWithoutClipping(ServerLevel serverLevel, BlockPos pos, BlockState state) {
        if (this.hasOtherEntityInBlock(serverLevel, pos)) {
            return false;
        }

        List<AABB> boxes = state.getCollisionShape(serverLevel, pos)
                .toAabbs()
                .stream()
                .map(box -> box.move(pos))
                .toList();
        if (boxes.stream().noneMatch(box -> box.intersects(this.playerNpc.getBoundingBox().inflate(0.02D)))) {
            return true;
        }

        double snapUp = pos.getY() + 1.0D - this.playerNpc.getBoundingBox().minY;
        if (snapUp < -0.05D || snapUp > 0.35D) {
            return false;
        }

        AABB snappedBox = this.playerNpc.getBoundingBox().move(0.0D, snapUp + 0.01D, 0.0D);
        return boxes.stream().noneMatch(box -> box.intersects(snappedBox.inflate(0.001D)))
                && PlayerNpcCollisionUtil.noBlockingCollision(serverLevel, this.playerNpc, snappedBox);
    }

    private boolean centerOnPillarBase(ServerLevel serverLevel, BlockPos pos) {
        double targetX = pos.getX() + 0.5D;
        double targetZ = pos.getZ() + 0.5D;
        double dx = targetX - this.playerNpc.getX();
        double dz = targetZ - this.playerNpc.getZ();
        if (Math.abs(dx) <= CENTER_EPSILON && Math.abs(dz) <= CENTER_EPSILON) {
            return true;
        }

        AABB centeredBox = this.playerNpc.getBoundingBox().move(dx, 0.0D, dz);
        BlockPos blocker = this.findCollisionBlocker(serverLevel, centeredBox, CENTER_BLOCKER_PADDING, 0.12D);
        if (blocker != null || !PlayerNpcCollisionUtil.noBlockingCollision(serverLevel, this.playerNpc, centeredBox)) {
            this.lastFailureBlockerPos = blocker == null ? null : blocker.immutable();
            this.lastFailureDetail = "pillar center blocked"
                    + (blocker == null ? "" : " by " + blockText(serverLevel.getBlockState(blocker)) + " @ " + posText(blocker));
            this.clear();
            return false;
        }

        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.setPos(targetX, this.playerNpc.getY(), targetZ);
        this.playerNpc.setDeltaMovement(0.0D, motion.y, 0.0D);
        return true;
    }

    private TickResult fail(String detail, BlockPos blockerPos) {
        this.lastFailureDetail = detail == null ? "pillar failed" : detail;
        this.lastFailureBlockerPos = blockerPos == null ? null : blockerPos.immutable();
        this.lookAtFailureBlocker();
        this.clear();
        return TickResult.FAILED;
    }

    private BlockPos findCurrentCollisionBlocker(ServerLevel serverLevel) {
        return this.findCollisionBlocker(serverLevel, this.playerNpc.getBoundingBox(), 0.08D, 0.35D);
    }

    private BlockPos findCollisionBlocker(ServerLevel serverLevel, AABB box, double horizontalPadding, double topPadding) {
        AABB checkBox = new AABB(
                box.minX - horizontalPadding,
                box.minY + 0.05D,
                box.minZ - horizontalPadding,
                box.maxX + horizontalPadding,
                box.maxY + topPadding,
                box.maxZ + horizontalPadding
        );

        int minX = Mth.floor(checkBox.minX);
        int minY = Mth.floor(checkBox.minY);
        int minZ = Mth.floor(checkBox.minZ);
        int maxX = Mth.floor(checkBox.maxX);
        int maxY = Mth.floor(checkBox.maxY);
        int maxZ = Mth.floor(checkBox.maxZ);
        for (BlockPos mutable : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            BlockPos pos = mutable.immutable();
            BlockState state = serverLevel.getBlockState(pos);
            if (state.getCollisionShape(serverLevel, pos).isEmpty()) {
                continue;
            }
            for (AABB collisionBox : state.getCollisionShape(serverLevel, pos).toAabbs()) {
                if (collisionBox.move(pos).intersects(checkBox)) {
                    return pos;
                }
            }
        }
        return null;
    }

    private void snapAbovePillarIfNeeded(BlockPos pos) {
        double topY = pos.getY() + 1.0D;
        if (this.playerNpc.getBoundingBox().minY >= topY) {
            return;
        }

        Vec3 motion = this.playerNpc.getDeltaMovement();
        this.playerNpc.setPos(this.playerNpc.getX(), topY, this.playerNpc.getZ());
        this.playerNpc.setDeltaMovement(motion.x, Math.max(0.0D, motion.y), motion.z);
        this.playerNpc.fallDistance = 0.0F;
    }

    private boolean hasOtherEntityInBlock(ServerLevel serverLevel, BlockPos pos) {
        return !this.blockingEntitiesInBlock(serverLevel, pos).isEmpty();
    }

    private String entityBlockerText(ServerLevel serverLevel, BlockPos pos) {
        List<Entity> entities = this.blockingEntitiesInBlock(serverLevel, pos);
        if (entities.isEmpty()) {
            return "";
        }
        Entity entity = entities.get(0);
        return "entity in pillar block "
                + posText(pos)
                + " "
                + entity.getType().toShortString()
                + "#"
                + entity.getId();
    }

    private List<Entity> blockingEntitiesInBlock(ServerLevel serverLevel, BlockPos pos) {
        return PlayerNpcCollisionUtil.blockingEntitiesInBox(serverLevel, this.playerNpc, new AABB(pos).inflate(0.05D));
    }

    private void lookDownAt(BlockPos pos) {
        this.playerNpc.getLookControl().setLookAt(
                pos.getX() + 0.5D,
                pos.getY() - 0.5D,
                pos.getZ() + 0.5D,
                60.0F,
                60.0F
        );
    }

    private void lookAtFailureBlocker() {
        if (this.lastFailureBlockerPos == null) {
            return;
        }
        this.playerNpc.getLookControl().setLookAt(
                this.lastFailureBlockerPos.getX() + 0.5D,
                this.lastFailureBlockerPos.getY() + 0.5D,
                this.lastFailureBlockerPos.getZ() + 0.5D,
                60.0F,
                60.0F
        );
    }

    private static String blockText(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static String posText(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
