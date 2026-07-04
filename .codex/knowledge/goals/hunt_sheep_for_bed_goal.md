# HuntSheepForBedGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/HuntSheepForBedGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Gives Player NPCs a simple way to gather wool for a bed.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Runs only if the NPC has no bed item and fewer than three wool items.
- Cooldown uses `PlayerNpcEntity.huntSheepCooldown`.

## Behavior

Finds the nearest adult sheep in range and sets it as the NPC's target. The existing combat goals then handle chasing and attacking.
