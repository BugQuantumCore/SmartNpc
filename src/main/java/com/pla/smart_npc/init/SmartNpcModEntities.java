package com.pla.smart_npc.init;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.entity.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityType.Builder;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.SpawnPlacementRegisterEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@EventBusSubscriber(bus = Bus.MOD)
public class SmartNpcModEntities {

    public static final DeferredRegister<EntityType<?>> REGISTRY = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, SmartNpc.MODID);
    public static final String PLAYER_NPC_ID = "player_npc";
    public static final RegistryObject<EntityType<PlayerNpcEntity>> PLAYER_NPC = register(PLAYER_NPC_ID, Builder.<PlayerNpcEntity>of(PlayerNpcEntity::new, MobCategory.CREATURE).setShouldReceiveVelocityUpdates(true).setTrackingRange(256).setUpdateInterval(3).setCustomClientFactory(PlayerNpcEntity::new).sized(0.6F, 1.8F));
    public static final String PLAYER_NPC_FISHING_BOBBER_ID = "player_npc_fishing_bobber";
    public static final RegistryObject<EntityType<PlayerNpcFishingBobberEntity>> PLAYER_NPC_FISHING_BOBBER = register(PLAYER_NPC_FISHING_BOBBER_ID, Builder.<PlayerNpcFishingBobberEntity>of(PlayerNpcFishingBobberEntity::new, MobCategory.MISC).setShouldReceiveVelocityUpdates(true).setTrackingRange(64).setUpdateInterval(5).setCustomClientFactory(PlayerNpcFishingBobberEntity::new).sized(0.25F, 0.25F));

    private static <T extends Entity> RegistryObject<EntityType<T>> register(String s, Builder<T> builder) {
        return SmartNpcModEntities.REGISTRY.register(s, () -> builder.build(s));
    }

    @SubscribeEvent
    public static void registerSpawnPlacements(SpawnPlacementRegisterEvent event) {
        event.register(
                SmartNpcModEntities.PLAYER_NPC.get(),
                SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                PlayerNpcEntity::canSpawn,
                SpawnPlacementRegisterEvent.Operation.REPLACE
        );
    }

    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent entityAttributeCreationEvent) {
        entityAttributeCreationEvent.put(SmartNpcModEntities.PLAYER_NPC.get(), PlayerNpcEntity.createAttributes().build());
    }
}
