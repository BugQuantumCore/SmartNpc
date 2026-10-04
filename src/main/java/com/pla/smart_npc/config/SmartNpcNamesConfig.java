package com.pla.smart_npc.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pla.smart_npc.clazz.PlayerNpcInterest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Player NPC roster config. Semantics are identical to the Forge edition's
 * {@code smart_npc-names.toml}: a table of {@code "skinName[:Display Name]" = [INTEREST, ...]}
 * entries (a legacy array of {@code "name|INTEREST|..."} strings is also accepted and
 * migrated). On Fabric the values live in {@code config/smart_npc-names.json}.
 */
public final class SmartNpcNamesConfig {
    private static final Object ENTRY_CACHE_LOCK = new Object();
    private static volatile List<String> cachedPlayerNpcNameEntries;
    private static volatile long playerNpcNameEntriesRevision;
    private static final List<String> DEFAULT_PLAYER_NPC_NAMES = List.of(
            "Technoblade|EXPLORING|HUNT_MONSTERS|LOOTING|TROLL_HIT",
            "Dream|EXPLORING|MINING|HUNT_PLAYERS|LOOTING|HUNT_ANIMALS|TROLL_HIT|CHEST_PROTECT|COWARD",
            "MrBeast|BUILDING|EXPLORING|LOOTING|TROLL_HIT|CHEST_PROTECT",
            "Skeppy|BUILDING|FARMING|TROLL_HIT|LOOTING|HUNT_MONSTERS",
            "Sapnap|EXPLORING|HUNT_ANIMALS|HUNT_VILLAGERS|HUNT_PLAYERS|TEAMUP",
            "ExplodingTNT|MINING|EXPLORING|TROLL_HIT|LOOTING|HUNT_MONSTERS|CHEST_PROTECT|COWARD",
            "GeorgeNotFound|BUILDING|FISHING|CAUTIOUS",
            "TommyInnit|EXPLORING|FARMING|TROLL_HIT",
            "Philza|FISHING|HUNT_MONSTERS|LOOTING|CHEST_PROTECT",
            "Ranboo|FISHING|BUILDING|CAUTIOUS|HUNT_ANIMALS|TEAMUP",
            "Quackity|FARMING|BUILDING|EXPLORING|HUNT_PLAYERS|HUNT_ANIMALS|HUNT_MONSTERS|COWARD",
            "Tubbo|FARMING|CAUTIOUS|LOOTING",
            "DanTDM|EXPLORING|FISHING|HUNT_MONSTERS|TROLL_HIT|TEAMUP",
            "PopularMMOs|EXPLORING|BUILDING|HUNT_MONSTERS|TROLL_HIT|LOOTING|CHEST_PROTECT",
            "Darkere|BUILDING|FARMING|FISHING|HUNT_ANIMALS|LOOTING|TEAMUP",
            "Darkhax|FISHING|CAUTIOUS",
            "Emberwalker|FARMING|MINING|CAUTIOUS|TEAMUP|COWARD",
            "Gigabit101|BUILDING|FISHING|LOOTING|HUNT_PLAYERS|CHEST_PROTECT",
            "Kamefrede|MINING|FARMING|HUNT_MONSTERS|LOOTING",
            "KnightMiner_|MINING|EXPLORING|HUNT_MONSTERS|LOOTING",
            "Lat|MINING|TROLL_HIT|HUNT_VILLAGERS|CHEST_PROTECT|TEAMUP",
            "LexManos|EXPLORING|FISHING|LOOTING|HUNT_PLAYERS|COWARD",
            "Mrbysco|BUILDING|EXPLORING|MINING|LOOTING",
            "P3pp3rF1y|FARMING|EXPLORING|HUNT_MONSTERS|TROLL_HIT|TEAMUP",
            "Ray|BUILDING|HUNT_PLAYERS|LOOTING|TROLL_HIT|CHEST_PROTECT",
            "Ridanis|FISHING|HUNT_ANIMALS|CAUTIOUS|LOOTING",
            "SOTMead|FARMING|HUNT_ANIMALS|LOOTING|HUNT_MONSTERS|CHEST_PROTECT|COWARD",
            "ShyNieke|EXPLORING|HUNT_MONSTERS|LOOTING|TEAMUP",
            "SkySom|EXPLORING|BUILDING|LOOTING|HUNT_PLAYERS|TROLL_HIT",
            "Soaryn|EXPLORING|FARMING|HUNT_MONSTERS|LOOTING",
            "ValkyrieofNight|FISHING|FARMING|CAUTIOUS|LOOTING",
            "XCompWiz|FARMING|BUILDING|TROLL_HIT|LOOTING|HUNT_VILLAGERS|CHEST_PROTECT|COWARD",
            "DaReal_BingoBear|MINING|BUILDING|HUNT_ANIMALS|LOOTING|CAUTIOUS",
            "darkphan|BUILDING|FISHING|LOOTING|HUNT_MONSTERS|TEAMUP",
            "direwolf20|FISHING|FARMING|EXPLORING|LOOTING|HUNT_MONSTERS|HUNT_ANIMALS",
            "dmodoomsirius|BUILDING|FARMING|FISHING|LOOTING|TROLL_HIT|HUNT_PLAYERS|CHEST_PROTECT",
            "malte0811|FARMING|CAUTIOUS|HUNT_ANIMALS|LOOTING|TEAMUP|COWARD",
            "nekosune|MINING|FARMING|FISHING|HUNT_PLAYERS|TROLL_HIT|LOOTING",
            "neptunepink|FISHING|HUNT_PLAYERS|HUNT_MONSTERS|LOOTING|CHEST_PROTECT",
            "vadis365|BUILDING|FARMING|HUNT_VILLAGERS|TROLL_HIT|LOOTING|TEAMUP",
            "wyld|EXPLORING|HUNT_ANIMALS|LOOTING|CAUTIOUS|TEAMUP",
            "paulsoaresjr|BUILDING|FARMING|LOOTING|HUNT_MONSTERS|CHEST_PROTECT",
            "Mhykol|FISHING|EXPLORING|HUNT_MONSTERS|LOOTING|HUNT_ANIMALS|CHEST_PROTECT",
            "Vswe|BUILDING|EXPLORING|LOOTING|CAUTIOUS|TEAMUP|COWARD",
            "TurkeyDev|EXPLORING|FARMING|FISHING|TROLL_HIT|HUNT_ANIMALS|LOOTING",
            "Gen_Deathrow|EXPLORING|HUNT_MONSTERS|HUNT_PLAYERS|LOOTING|CHEST_PROTECT|TEAMUP",
            "Sevadus|EXPLORING|FISHING|HUNT_VILLAGERS|CAUTIOUS|LOOTING|CHEST_PROTECT|COWARD"
    );

    static final JsonConfig CONFIG = new JsonConfig("smart_npc-names.json");
    private static final JsonObject CONFIG_RAW_DEFAULTS;

    static {
        CONFIG_RAW_DEFAULTS = createRosterJson(DEFAULT_PLAYER_NPC_NAMES);
        CONFIG.defineElement("playerNpcNames", CONFIG_RAW_DEFAULTS);
        CONFIG.addReloadListener(SmartNpcNamesConfig::invalidatePlayerNpcNameEntries);
    }

    private SmartNpcNamesConfig() {
    }

    public static void load() {
        CONFIG.load();
    }

    /** Re-reads the file and invalidates cached roster entries (Forge: config reload event). */
    public static void reload() {
        CONFIG.reload();
    }

    public static List<String> getPlayerNpcNameEntries() {
        List<String> entries = cachedPlayerNpcNameEntries;
        if (entries != null) {
            return entries;
        }
        synchronized (ENTRY_CACHE_LOCK) {
            entries = cachedPlayerNpcNameEntries;
            if (entries == null) {
                entries = List.copyOf(toNameEntryStrings(CONFIG.raw("playerNpcNames")));
                cachedPlayerNpcNameEntries = entries;
            }
            return entries;
        }
    }

    /** Cheap generation check used by loaded NPCs to refresh interests after a config reload. */
    public static long getPlayerNpcNameEntriesRevision() {
        return playerNpcNameEntriesRevision;
    }

    public static Optional<NameEntry> parseNameEntry(String rawEntry) {
        if (rawEntry == null) {
            return Optional.empty();
        }

        String[] fields = rawEntry.split("\\|", -1);
        if (fields.length < 2) {
            return Optional.empty();
        }

        String[] names = fields[0].trim().split(":", 2);
        String skinName = names[0].trim();
        if (!skinName.matches("[A-Za-z0-9_]{1,16}")) {
            return Optional.empty();
        }

        String displayName = null;
        if (names.length > 1) {
            displayName = names[1].trim();
            if (displayName.isEmpty() || displayName.length() > 64) {
                return Optional.empty();
            }
        }

        Set<PlayerNpcInterest> interests = new LinkedHashSet<>();
        for (int i = 1; i < fields.length; i++) {
            String interestName = fields[i].trim();
            if (interestName.isEmpty()) {
                return Optional.empty();
            }
            try {
                PlayerNpcInterest interest = PlayerNpcInterest.valueOf(interestName.toUpperCase(Locale.ROOT));
                if (!interests.add(interest)) {
                    return Optional.empty();
                }
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
        }

        if (interests.stream().noneMatch(PlayerNpcInterest::isJob)) {
            return Optional.empty();
        }
        return Optional.of(new NameEntry(skinName, displayName, List.copyOf(interests)));
    }

    private static List<String> toNameEntryStrings(JsonElement element) {
        List<String> result = new ArrayList<>();
        if (element instanceof JsonArray legacyEntries) {
            // legacy list format: "skinName|INTEREST|..."
            for (JsonElement legacyEntry : legacyEntries) {
                if (legacyEntry.isJsonPrimitive() && legacyEntry.getAsJsonPrimitive().isString()) {
                    String entry = legacyEntry.getAsString();
                    if (parseNameEntry(entry).isPresent()) {
                        result.add(entry);
                    }
                }
            }
            return result;
        }
        if (!(element instanceof JsonObject roster)) {
            return result;
        }
        for (Map.Entry<String, JsonElement> entry : roster.entrySet()) {
            parseRosterEntry(entry.getKey(), entry.getValue()).ifPresent(result::add);
        }
        return result;
    }

    private static Optional<String> parseRosterEntry(String combinedName, JsonElement value) {
        if (!(value instanceof JsonArray rawInterests) || rawInterests.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder legacyEntry = new StringBuilder(combinedName);
        for (JsonElement rawInterest : rawInterests) {
            if (!rawInterest.isJsonPrimitive() || !rawInterest.getAsJsonPrimitive().isString()) {
                return Optional.empty();
            }
            legacyEntry.append('|').append(rawInterest.getAsString());
        }
        return Optional.of(legacyEntry.toString());
    }

    private static JsonObject createRosterJson(List<String> entries) {
        JsonObject roster = new JsonObject();
        for (String rawEntry : entries) {
            parseNameEntry(rawEntry).ifPresent(entry -> {
                JsonArray interests = new JsonArray();
                for (PlayerNpcInterest interest : entry.interests()) {
                    interests.add(interest.name());
                }
                roster.add(combinedName(entry), interests);
            });
        }
        return roster;
    }

    private static String combinedName(NameEntry entry) {
        return entry.displayName() == null
                ? entry.skinName()
                : entry.skinName() + ":" + entry.displayName();
    }

    static void invalidatePlayerNpcNameEntries() {
        synchronized (ENTRY_CACHE_LOCK) {
            cachedPlayerNpcNameEntries = null;
            playerNpcNameEntriesRevision++;
        }
    }

    public record NameEntry(String skinName, String displayName, List<PlayerNpcInterest> interests) {
    }
}
