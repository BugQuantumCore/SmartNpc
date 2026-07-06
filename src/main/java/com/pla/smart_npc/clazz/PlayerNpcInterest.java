package com.pla.smart_npc.clazz;

public enum PlayerNpcInterest {
    BUILDING("Building"),
    MINING("Mining"),
    FARMING("Farming"),
    FISHING("Fishing"),
    HUNT_MONSTERS("Hunt Monsters"),
    HUNT_ANIMALS("Hunt Animals"),
    HUNT_PLAYERS("Hunt Players"),
    HUNT_VILLAGERS("Hunt Villagers"),
    TROLL_HIT("Troll Hit"),
    EXPLORING("Exploring"),
    LOOTING("Looting"),
    CAUTIOUS("Cautious");

    private final String displayName;

    PlayerNpcInterest(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return this.displayName;
    }
}
