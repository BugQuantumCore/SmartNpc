package com.pla.player_npc.clazz;

import net.minecraft.util.RandomSource;

public enum PlayerNpcTarget {
    MONSTER_HUNTER,
    VILLAGER_HUNTER,
    PLAYER_HUNTER,
    HOSTILE_HUNTER,
    PASSIVE_HUNTER,
    ANIMAL_HUNTER;

    public static PlayerNpcTarget random(RandomSource randomSource) {
        PlayerNpcTarget[] values = values();
        return values[randomSource.nextInt(values.length)];
    }
}
