package com.pla.smart_npc.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public class SmartNpcConfig {
    public record SpawnConfig(int weight, int minCount, int maxCount) {}

    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    private static final SpawnConfig DEFAULT_PLAYER_NPC_SPAWN = new SpawnConfig(1, 1, 1);

    public static ForgeConfigSpec.ConfigValue<Boolean> TURN_ON_NPC_CHAT;
    public static ForgeConfigSpec.ConfigValue<List<? extends Number>> PLAYER_NPC_SPAWN;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLIST_COMPAT_MOD_WEAPON;
    public static ForgeConfigSpec.ConfigValue<Boolean> PERFORMANCE_MONITOR_ENABLED;
    public static ForgeConfigSpec.DoubleValue PERFORMANCE_WARNING_AVERAGE_MSPT;
    public static ForgeConfigSpec.DoubleValue PERFORMANCE_WARNING_SPIKE_MSPT;
    public static ForgeConfigSpec.IntValue PERFORMANCE_WARNING_COOLDOWN_TICKS;
    public static ForgeConfigSpec.IntValue PERFORMANCE_WARNING_NPC_TRACE_LIMIT;

    static {
        TURN_ON_NPC_CHAT = BUILDER.comment(
                        "Turn on all chatting for NPC")
                .define("turnOnNpcChat", true);

        PLAYER_NPC_SPAWN = BUILDER.comment(
                        "Spawn config for Player NPC. Format: [weight, minCount, maxCount]. Weight is added to the spawn pool in each overworld biome. 0 disables spawning")
                .defineList("spawnPlayerNpc", List.of(
                        DEFAULT_PLAYER_NPC_SPAWN.weight(),
                        DEFAULT_PLAYER_NPC_SPAWN.minCount(),
                        DEFAULT_PLAYER_NPC_SPAWN.maxCount()
                ), element -> element instanceof Number);

        BLACKLIST_COMPAT_MOD_WEAPON = BUILDER.comment(
                        "Mod ids whose mobs_equipment JSON should not distribute weapons to Player NPC")
                .defineList("blacklistCompatModWeapon", List.of(), element -> element instanceof String);

        BUILDER.push("performanceMonitor");
        PERFORMANCE_MONITOR_ENABLED = BUILDER.comment(
                        "Log Smart NPC scoped TPS/MSPT warnings and show current server TPS in the Player NPC inspector")
                .define("enabled", true);
        PERFORMANCE_WARNING_AVERAGE_MSPT = BUILDER.comment(
                        "Warn when the rolling 5-second average server tick time is at least this many milliseconds. 50 ms is 20 TPS")
                .defineInRange("averageMsptWarningThreshold", 75.0D, 50.0D, 1000.0D);
        PERFORMANCE_WARNING_SPIKE_MSPT = BUILDER.comment(
                        "Warn immediately when one server tick takes at least this many milliseconds")
                .defineInRange("spikeMsptWarningThreshold", 200.0D, 50.0D, 10000.0D);
        PERFORMANCE_WARNING_COOLDOWN_TICKS = BUILDER.comment(
                        "Minimum server ticks between Smart NPC performance warning log entries")
                .defineInRange("warningCooldownTicks", 200, 20, 72000);
        PERFORMANCE_WARNING_NPC_TRACE_LIMIT = BUILDER.comment(
                        "Maximum active Player NPC goal trace lines to include per performance warning")
                .defineInRange("npcTraceLimit", 8, 0, 64);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static SpawnConfig getPlayerNpcSpawnConfig() {
        return parseSpawnConfigOrDefault(PLAYER_NPC_SPAWN.get(), DEFAULT_PLAYER_NPC_SPAWN);
    }

    public static boolean isCompatWeaponBlacklisted(String modId) {
        return BLACKLIST_COMPAT_MOD_WEAPON.get().stream().anyMatch(entry -> entry.equalsIgnoreCase(modId));
    }

    private static SpawnConfig parseSpawnConfigOrDefault(List<? extends Number> rawValues, SpawnConfig defaultConfig) {
        if (rawValues == null || rawValues.size() != 3) {
            return defaultConfig;
        }

        Integer weight = toExactInteger(rawValues.get(0));
        Integer minCount = toExactInteger(rawValues.get(1));
        Integer maxCount = toExactInteger(rawValues.get(2));

        if (weight == null) {
            return defaultConfig;
        }
        if (weight == 0) {
            return new SpawnConfig(0, 1, 1);
        }

        if (minCount == null || maxCount == null) {
            return defaultConfig;
        }
        if (weight < 0 || weight > 1000 || minCount < 1 || minCount > 64 || maxCount < minCount || maxCount > 64) {
            return defaultConfig;
        }

        return new SpawnConfig(weight, minCount, maxCount);
    }

    private static Integer toExactInteger(Number number) {
        if (number == null) return null;

        double valueAsDouble = number.doubleValue();
        long roundedValue = Math.round(valueAsDouble);

        if (Math.abs(valueAsDouble - roundedValue) > 1e-9) return null;
        if (roundedValue < Integer.MIN_VALUE || roundedValue > Integer.MAX_VALUE) return null;

        return (int) roundedValue;
    }
}
