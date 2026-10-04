package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.PlayerNpcFishingBobberEntity;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Fabric port of the Forge {@code DeferredRegister<EntityType<?>>} registry.
 * The Forge-only {@code setCustomClientFactory} hook has no Fabric equivalent;
 * the fishing bobber now syncs its angler through synched entity data instead
 * of Forge's additional spawn payload.
 */
public class SmartNpcModEntities {
    public static final String PLAYER_NPC_ID = "player_npc";
    public static final String PLAYER_NPC_FISHING_BOBBER_ID = "player_npc_fishing_bobber";

    public static final EntityType<PlayerNpcEntity> PLAYER_NPC = register(PLAYER_NPC_ID,
            EntityType.Builder.<PlayerNpcEntity>of(PlayerNpcEntity::new, MobCategory.CREATURE)
                    .clientTrackingRange(256)
                    .updateInterval(3)
                    .sized(0.6F, 1.8F));

    public static final EntityType<PlayerNpcFishingBobberEntity> PLAYER_NPC_FISHING_BOBBER = register(PLAYER_NPC_FISHING_BOBBER_ID,
            EntityType.Builder.<PlayerNpcFishingBobberEntity>of(PlayerNpcFishingBobberEntity::new, MobCategory.MISC)
                    .clientTrackingRange(64)
                    .updateInterval(5)
                    .sized(0.25F, 0.25F));

    private static <T extends Entity> EntityType<T> register(String id, EntityType.Builder<T> builder) {
        return Registry.register(
                BuiltInRegistries.ENTITY_TYPE,
                new ResourceLocation(SmartNpc.MODID, id),
                builder.build(id)
        );
    }

    public static void register() {
        // Forge SpawnPlacementRegisterEvent/EntityAttributeCreationEvent equivalents.
        SpawnPlacements.register(
                PLAYER_NPC,
                SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                PlayerNpcEntity::canSpawn
        );
        FabricDefaultAttributeRegistry.register(PLAYER_NPC, PlayerNpcEntity.createAttributes().build());
    }
}
