package com.pla.smart_npc.config;

import java.util.List;

/**
 * Server behaviour config. Semantics (names, defaults, ranges) are identical to the
 * Forge edition's {@code smart_npc-server.toml}; on Fabric the values are stored in
 * {@code config/smart_npc-server.json}.
 */
public class SmartNpcConfig {
    public record SpawnConfig(int weight, int minCount, int maxCount) {}

    static final JsonConfig CONFIG = new JsonConfig("smart_npc-server.json");

    public static JsonConfig.BooleanValue REMOTE_NPC_DEPARTURE_ENABLED;
    public static JsonConfig.IntValue REMOTE_NPC_DEPARTURE_MIN_MINUTES;
    public static JsonConfig.IntValue REMOTE_NPC_DEPARTURE_MAX_MINUTES;
    private static final SpawnConfig DEFAULT_PLAYER_NPC_SPAWN = new SpawnConfig(1, 1, 1);
    private static final int DEFAULT_MAX_NATURAL_PLAYER_NPCS = -1;

    public static JsonConfig.BooleanValue TURN_ON_NPC_CHAT;
    public static JsonConfig.BooleanValue SHOW_NPC_CHAT_PREFIX;
    public static JsonConfig.StringValue NPC_CHAT_LOCALE;
    public static JsonConfig.IntValue FORCE_TICK_MANAGE;
    public static JsonConfig.IntValue MAX_NATURAL_PLAYER_NPCS;
    public static JsonConfig.IntValue AI_PROCESSING_NPC_LIMIT;
    public static JsonConfig.DoubleValue AI_TARGET_SERVER_MSPT;
    public static JsonConfig.NumberListValue PLAYER_NPC_SPAWN;
    public static JsonConfig.StringListValue BLACKLIST_COMPAT_MOD_WEAPON;
    public static JsonConfig.BooleanValue PERFORMANCE_MONITOR_ENABLED;

    static {
        REMOTE_NPC_DEPARTURE_ENABLED = CONFIG.defineBoolean("remoteNpcDeparture.enabled", true);
        REMOTE_NPC_DEPARTURE_MIN_MINUTES = CONFIG.defineInt("remoteNpcDeparture.minMinutes", 10);
        REMOTE_NPC_DEPARTURE_MAX_MINUTES = CONFIG.defineInt("remoteNpcDeparture.maxMinutes", 30);

        TURN_ON_NPC_CHAT = CONFIG.defineBoolean("turnOnNpcChat", true);
        SHOW_NPC_CHAT_PREFIX = CONFIG.defineBoolean("showNpcChatPrefix", false);
        NPC_CHAT_LOCALE = CONFIG.defineString("npcChatLocale", "en_us");

        FORCE_TICK_MANAGE = CONFIG.defineInt("forceTickManage", -1);
        MAX_NATURAL_PLAYER_NPCS = CONFIG.defineInt("maxNaturalPlayerNpcs", DEFAULT_MAX_NATURAL_PLAYER_NPCS);
        AI_PROCESSING_NPC_LIMIT = CONFIG.defineInt("aiScheduler.processingNpcLimit", -1);
        AI_TARGET_SERVER_MSPT = CONFIG.defineDouble("aiScheduler.targetServerMspt", -1.0D);

        PLAYER_NPC_SPAWN = CONFIG.defineNumberList("spawnPlayerNpc", List.of(
                DEFAULT_PLAYER_NPC_SPAWN.weight(),
                DEFAULT_PLAYER_NPC_SPAWN.minCount(),
                DEFAULT_PLAYER_NPC_SPAWN.maxCount()
        ));

        BLACKLIST_COMPAT_MOD_WEAPON = CONFIG.defineStringList("blacklistCompatModWeapon", List.of());

        PERFORMANCE_MONITOR_ENABLED = CONFIG.defineBoolean("performanceMonitor.enabled", false);
    }

    private SmartNpcConfig() {
    }

    public static void load() {
        CONFIG.load();
    }

    /** Reloads from disk; called when the server (re)starts a datapack cycle. */
    public static void reload() {
        CONFIG.reload();
    }

    public static SpawnConfig getPlayerNpcSpawnConfig() {
        return parseSpawnConfigOrDefault(PLAYER_NPC_SPAWN.get(), DEFAULT_PLAYER_NPC_SPAWN);
    }

    public static boolean isCompatWeaponBlacklisted(String modId) {
        return BLACKLIST_COMPAT_MOD_WEAPON.get().stream().anyMatch(entry -> entry.equalsIgnoreCase(modId));
    }

    public static boolean isForceTickManageEnabled() {
        return getForceTickMode() != 0;
    }

    public static int getForceTickMode() {
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
