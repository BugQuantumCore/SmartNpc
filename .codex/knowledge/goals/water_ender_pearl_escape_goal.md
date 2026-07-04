# WaterEnderPearlEscapeGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/WaterEnderPearlEscapeGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Lets Player NPCs escape water with an ender pearl without depending on Epic Fight code.

## Activation

- Server side only.
- NPC must be in water or bubbles.
- Requires ender pearl inventory supply and no pearl cooldown.
- Does not run while healing.

## Behavior

Finds a dry standable landing position, consumes one ender pearl, temporarily renders it in the offhand, throws it toward the landing, starts cooldown, and restores the previous offhand item.

