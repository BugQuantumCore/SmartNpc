package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.item.InventoryViewerItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockSource;
import net.minecraft.core.Registry;
import net.minecraft.core.dispenser.OptionalDispenseItemBehavior;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fabric port of the Forge item registry. {@code ForgeSpawnEggItem} is replaced
 * by vanilla's public {@link SpawnEggItem} (entity types register first, so no
 * lazy supplier is required).
 */
public class SmartNpcModItems {

    public static final Item INVENTORY_VIEWER = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(SmartNpc.MODID, "player_npc_inspector"),
            new InventoryViewerItem()
    );

    public static final Item PLAYER_NPC_SPAWN_EGG = Registry.register(
            BuiltInRegistries.ITEM,
            new ResourceLocation(SmartNpc.MODID, "player_npc_spawn_egg"),
            new SpawnEggItem(
                    SmartNpcModEntities.PLAYER_NPC,
                    0xFFF144,
                    0x69DFDA,
                    new Item.Properties()
            )
    );

    public static void register() {
        // Class initialization registers the items; this method only exists to
        // make the registration order explicit in SmartNpc#onInitialize.
        // Attach a spawn-egg dispense behavior as well, which ForgeSpawnEggItem
        // used to provide out of the box.
        OptionalDispenseItemBehavior dispenseBehavior = new OptionalDispenseItemBehavior() {
            @Override
            public ItemStack execute(BlockSource blockSource, ItemStack stack) {
                EntityType<?> type = ((SpawnEggItem) stack.getItem()).getType(stack.getTag());
                net.minecraft.world.level.Level level = blockSource.getLevel();
                BlockState blockState = blockSource.getBlockState();
                if (type == null || !(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
                    return stack;
                }

                BlockPos spawnPos = blockSource.getPos().relative(
                        blockState.getValue(DispenserBlock.FACING));
                CompoundTag entityTag = stack.getTag() == null
                        ? new CompoundTag()
                        : stack.getTag().getCompound("EntityTag").copy();
                entityTag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
                entityTag.remove("Pos");
                java.util.Optional<Entity> spawned = EntityType.create(entityTag, serverLevel);
                if (spawned.isEmpty()) {
                    return stack;
                }

                Entity entity = spawned.get();
                entity.moveTo(spawnPos.getX() + 0.5D, spawnPos.getY(), spawnPos.getZ() + 0.5D, 0.0F, 0.0F);
                serverLevel.addFreshEntity(entity);
                stack.shrink(1);
                setSuccess(true);
                return stack;
            }
        };
        DispenserBlock.registerBehavior(PLAYER_NPC_SPAWN_EGG, dispenseBehavior);
    }
}
