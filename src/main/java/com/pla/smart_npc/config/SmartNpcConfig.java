package com.pla.smart_npc.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public class SmartNpcConfig {
    public record SpawnConfig(int weight, int minCount, int maxCount) {}

    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    private static final SpawnConfig DEFAULT_PLAYER_NPC_SPAWN = new SpawnConfig(1, 1, 1);
    private static final int DEFAULT_MAX_NATURAL_PLAYER_NPCS = -1;

    public static ForgeConfigSpec.ConfigValue<Boolean> TURN_ON_NPC_CHAT;
    public static ForgeConfigSpec.ConfigValue<Boolean> FORCE_TICK_MANAGE;
    public static ForgeConfigSpec.IntValue MAX_NATURAL_PLAYER_NPCS;
    public static ForgeConfigSpec.IntValue AI_PROCESSING_NPC_LIMIT;
    public static ForgeConfigSpec.DoubleValue AI_TARGET_SERVER_MSPT;
    public static ForgeConfigSpec.ConfigValue<List<? extends Number>> PLAYER_NPC_SPAWN;
    public static ForgeConfigSpec.ConfigValue<List<? extends String>> BLACKLIST_COMPAT_MOD_WEAPON;
    public static ForgeConfigSpec.ConfigValue<Boolean> PERFORMANCE_MONITOR_ENABLED;

    static {
        TURN_ON_NPC_CHAT = BUILDER.comment(
                        "Turn on all chatting for NPC")
                .define("turnOnNpcChat", true);

        FORCE_TICK_MANAGE = BUILDER.comment(
                        "Give each loaded Player NPC one moving distance-2 force-ticket anchor at its current chunk.",
                        "Normal ticket propagation supplies the loaded fringe; no independent neighbor anchors are added.",
                        "Also exposes tracked Player NPCs in remote helper features such as the tab list and teleport command.",
                        "This does not enable or disable the AI scheduler or natural-spawn population cap.",
                        "Normally loaded/ticking NPCs remain scheduled when false; distant unloaded NPCs do not tick or appear in force-manager remote diagnostics.")
                .define("forceTickManage", true);

        MAX_NATURAL_PLAYER_NPCS = BUILDER.comment(
                        "Maximum number of living Player NPCs allowed before natural spawning stops.",
                        "0 disables natural Player NPC spawning. Positive values are fixed caps.",
                        "-1 (the default) starts at max 4, clamped lower on weak JVMs by a CPU/RAM exploration ceiling; this is not a guaranteed safe population.",
                        "Automatic feedback uses a rolling baseline that omits the highest 5% of tick samples, so isolated job/path spikes do not masquerade as passive population cost.",
                        "A full/loaded cap at <=40 baseline MSPT grows by 2 after two five-second checks, only up to 10; <=45 MSPT grows by 1 after three checks.",
                        "At 45-50 baseline MSPT an occupied cap is held without growth; an unfilled probe is retracted if headroom is lost.",
                        "At 50+ baseline MSPT future admission targets one NPC below the measured overloaded population, then waits for natural attrition before reducing again.",
                        "A tested safe cap is persisted across restart, but restore never opens slots above the current living population except the startup allowance.",
                        "A lower automatic cap never despawns existing NPCs. Concurrent spawn reservations prevent fresh-world candidates from overshooting it.",
                        "The population cap is separate from the routine AI worker limit and applies whether forceTickManage is on or off.",
                        "When performanceMonitor.enabled is false or has not produced its first stable sample, automatic mode stays at its startup-safe cap.")
                .defineInRange("maxNaturalPlayerNpcs", DEFAULT_MAX_NATURAL_PLAYER_NPCS, -1, Integer.MAX_VALUE);

        BUILDER.push("aiScheduler");
        AI_PROCESSING_NPC_LIMIT = BUILDER.comment(
                        "Maximum Player NPCs that may hold routine AI worker turns at once.",
                        "-1 starts at one worker and explores up to min(12, 2 per CPU thread, 3 per max-heap GiB).",
                        "Growth requires every current worker slot occupied plus another queued NPC, so unused slots are not learned as safe.",
                        "At/below target (40 ms default), growth needs two five-second checks below 3 workers, then three checks.",
                        "Through min(target+9, 49) ms, cautious growth needs five checks below 3 workers, then six checks.",
                        "The next band holds; at min(target+12, 52) ms, two consecutive checks remove one worker.",
                        "Isolated high tick spikes are trimmed from the baseline.",
                        "0 pauses scheduled routine jobs; positive values set a fixed worker count.",
                        "This is a worker count, not a percentage of memory. Combat and safety behavior remain responsive.",
                        "Scheduling is independent of forceTickManage and covers Player NPC entities that are currently loaded and ticking; remote unloaded NPCs do not request or occupy turns.")
                .defineInRange("processingNpcLimit", -1, -1, 64);
        AI_TARGET_SERVER_MSPT = BUILDER.comment(
                        "Server tick-time target used when processingNpcLimit is -1.",
                        "-1 selects the automatic 40 ms target, leaving headroom below Minecraft's 50 ms / 20 TPS deadline.",
                        "This limits CPU time pressure; it is not a heap-memory allocation percentage.")
                .defineInRange("targetServerMspt", -1.0D, -1.0D, 49.0D);
        BUILDER.pop();

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
                        "Log Smart NPC scoped TPS/MSPT warnings and show current server TPS in the Player NPC inspector.",
                        "Warning thresholds, cooldown, and per-warning trace-line cap use internal monitor defaults.")
                .define("enabled", true);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    public static SpawnConfig getPlayerNpcSpawnConfig() {
        return parseSpawnConfigOrDefault(PLAYER_NPC_SPAWN.get(), DEFAULT_PLAYER_NPC_SPAWN);
    }

    public static boolean isCompatWeaponBlacklisted(String modId) {
        return BLACKLIST_COMPAT_MOD_WEAPON.get().stream().anyMatch(entry -> entry.equalsIgnoreCase(modId));
    }

    public static boolean isForceTickManageEnabled() {
        return FORCE_TICK_MANAGE.get();
    }

    public static int getMaxNaturalPlayerNpcs() {
        return MAX_NATURAL_PLAYER_NPCS.get();
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
