# FarmCropGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/FarmCropGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Adds simple farming behavior for Player NPCs.

## Behavior

The goal can harvest mature crops nearby. If the NPC has a saved home, a hoe, and wheat seeds, it can also create a small wheat patch beside the home by tilling dirt/grass and planting wheat.

The NPC moves into range before harvesting or planting. The goal is idle-only and uses `PlayerNpcEntity.farmCooldown`.
