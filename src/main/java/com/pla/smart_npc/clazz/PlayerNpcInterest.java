package com.pla.smart_npc.clazz;

public enum PlayerNpcInterest {
    BUILDING("Building"),
    MINING("Mining"),
    FARMING("Farming"),
    FISHING("Fishing"),
    EXPLORING("Exploring"),
    HUNT_MONSTERS("Hunt Monsters"),
    HUNT_ANIMALS("Hunt Animals"),
    HUNT_PLAYERS("Hunt Players"),
    HUNT_VILLAGERS("Hunt Villagers"),
    TROLL_HIT("Troll Hit"),
    LOOTING("Looting"),
    CAUTIOUS("Cautious"),
    COWARD("Coward"),
    CHEST_PROTECT("Chest Protect"),
    TEAMUP("Team Up");

    private final String displayName;

    PlayerNpcInterest(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }

    public boolean isJob() {
        return switch (this) {
            case BUILDING, MINING, FARMING, FISHING, EXPLORING -> true;
            default -> false;
        };
    }

    /**
     * Jobs whose daily work breaks or places world blocks. Adventure-mode NPCs never run
     * these jobs, while fishing/exploring (and every characteristic) stay fully available.
     */
    public boolean isWorldMutationJob() {
        return switch (this) {
            case BUILDING, MINING, FARMING -> true;
            default -> false;
        };
    }

    public boolean isCharacteristic() {
        return !this.isJob();
    }
}
