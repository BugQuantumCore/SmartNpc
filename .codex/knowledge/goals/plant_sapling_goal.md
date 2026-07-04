# PlantSaplingGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/PlantSaplingGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets Player NPCs replant saplings they pick up or already carry.

## Behavior

When idle with a sapling in inventory, the goal finds nearby air where that sapling block can survive, consumes one sapling, places it, swings, and plays a grass placement sound. It uses the sapling block state's normal survival check, so invalid ground is skipped.

Cooldown uses `PlayerNpcEntity.saplingPlantCooldown`.
