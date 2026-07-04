package com.pla.player_npc.world;

import com.pla.player_npc.PlayerNpc;
import com.pla.player_npc.config.PlayerNpcConfig;
import com.pla.player_npc.init.PlayerNpcModEntities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraftforge.common.world.ModifiableBiomeInfo;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class PlayerNpcWorldSpawns {
    private static final Logger LOGGER = LogManager.getLogger();

    private PlayerNpcWorldSpawns() {}
    public static void addBiomeSpawns(ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        PlayerNpcConfig.SpawnConfig spawnConfig = PlayerNpcConfig.getPlayerNpcSpawnConfig();
        addSpawn(builder, ResourceLocation.fromNamespaceAndPath(PlayerNpc.MODID, PlayerNpcModEntities.PLAYER_NPC_ID), spawnConfig);
    }

    private static void addSpawn(ModifiableBiomeInfo.BiomeInfo.Builder builder,
                                 ResourceLocation entityId,
                                 PlayerNpcConfig.SpawnConfig spawnConfig) {

        if (spawnConfig.weight() <= 0) return;
        EntityType<?> rawType = ForgeRegistries.ENTITY_TYPES.getValue(entityId);
        if (rawType == null) {
            LOGGER.warn("Spawn config refers to missing entity type: {}", entityId);
            return;
        }

        @SuppressWarnings("unchecked")
        EntityType<? extends Mob> mobType = (EntityType<? extends Mob>) rawType;

        builder.getMobSpawnSettings()
                .getSpawner(mobType.getCategory())
                .add(new MobSpawnSettings.SpawnerData(mobType, spawnConfig.weight(), spawnConfig.minCount(), spawnConfig.maxCount()));
    }
}
