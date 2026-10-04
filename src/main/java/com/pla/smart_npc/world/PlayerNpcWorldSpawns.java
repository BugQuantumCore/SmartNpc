package com.pla.smart_npc.world;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.init.SmartNpcModEntities;
import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.tags.BiomeTags;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Fabric port of the Forge data-driven {@code BiomeModifier} pipeline
 * ({@code data/smart_npc/forge/biome_modifier/player_npc_spawns.json} +
 * {@code PlayerNpcMobSpawnBiomeModifier}). Spawns are added through
 * {@link BiomeModifications} for every overworld biome, exactly like the
 * Forge edition gated on {@code BiomeTags.IS_OVERWORLD}.
 *
 * <p>The spawn weight/counts are read once at mod init; changing them in the
 * config requires a game restart, which matches Forge's datapack-driven
 * behaviour (a datapack reload could not alter the modifier either).</p>
 */
public final class PlayerNpcWorldSpawns {
    private static final Logger LOGGER = LogManager.getLogger();

    private PlayerNpcWorldSpawns() {
    }

    public static void registerBiomeSpawns() {
        SmartNpcConfig.SpawnConfig spawnConfig = SmartNpcConfig.getPlayerNpcSpawnConfig();
        addSpawn(SmartNpcModEntities.PLAYER_NPC, spawnConfig);
    }

    private static void addSpawn(net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.Mob> mobType,
                                 SmartNpcConfig.SpawnConfig spawnConfig) {
        if (spawnConfig.weight() <= 0) {
            return;
        }
        if (mobType == null) {
            LOGGER.warn("Spawn config refers to missing entity type: {}", SmartNpc.MODID);
            return;
        }

        BiomeModifications.addSpawn(
                BiomeSelectors.tag(BiomeTags.IS_OVERWORLD),
                mobType.getCategory(),
                mobType,
                spawnConfig.weight(),
                spawnConfig.minCount(),
                spawnConfig.maxCount()
        );
    }
}
