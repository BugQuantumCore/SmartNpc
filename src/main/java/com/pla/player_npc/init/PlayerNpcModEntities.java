package com.pla.player_npc.init;

import com.pla.player_npc.PlayerNpc;
import com.pla.player_npc.entity.*;
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
public class PlayerNpcModEntities {

    public static final DeferredRegister<EntityType<?>> REGISTRY = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, PlayerNpc.MODID);
    public static final String PLAYER_NPC_ID = "player_npc";
    public static final RegistryObject<EntityType<PlayerNpcEntity>> PLAYER_NPC = register(PLAYER_NPC_ID, Builder.<PlayerNpcEntity>of(PlayerNpcEntity::new, MobCategory.MONSTER).setShouldReceiveVelocityUpdates(true).setTrackingRange(256).setUpdateInterval(3).setCustomClientFactory(PlayerNpcEntity::new).sized(0.6F, 1.8F));
    private static <T extends Entity> RegistryObject<EntityType<T>> register(String s, Builder<T> builder) {
        return PlayerNpcModEntities.REGISTRY.register(s, () -> builder.build(s));
    }

    @SubscribeEvent
    public static void registerSpawnPlacements(SpawnPlacementRegisterEvent event) {
        event.register(
                PlayerNpcModEntities.PLAYER_NPC.get(),
                SpawnPlacements.Type.ON_GROUND,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                PlayerNpcEntity::canSpawn,
                SpawnPlacementRegisterEvent.Operation.REPLACE
        );
    }

    @SubscribeEvent
    public static void registerAttributes(EntityAttributeCreationEvent entityAttributeCreationEvent) {
        entityAttributeCreationEvent.put(PlayerNpcModEntities.PLAYER_NPC.get(), PlayerNpcEntity.createAttributes().build());
    }
}
