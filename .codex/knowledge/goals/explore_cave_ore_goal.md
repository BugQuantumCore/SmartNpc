# ExploreCaveOreGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/ExploreCaveOreGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs seek cave-adjacent ores instead of only gathering surface logs/stone/dirt.

## Behavior

Requires a pickaxe in hand or inventory. The NPC searches nearby underground/cave-exposed blocks for iron, coal, and copper ore variants, pathfinds to a valid stand position, equips a pickaxe temporarily if needed, animates block breaking, destroys the ore with vanilla drops, damages the pickaxe, and restores the previous main-hand item.

Inspector detail shows the ore block id, coordinates, and mining progress.

Cooldown uses `PlayerNpcEntity.oreMiningCooldown`.
