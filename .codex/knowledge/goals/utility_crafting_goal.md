# UtilityCraftingGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/UtilityCraftingGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Provides first-pass crafting groundwork for non-combat utility behavior near water.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- NPC must be near water and not already have a boat.
- Requires wood logs or planks in inventory.
- Cooldown uses `PlayerNpcEntity.craftCooldown`.

## Behavior

Consumes five wood to create an oak boat when possible. If only four wood are available, it places a crafting table nearby. This is intentionally simple groundwork for later recipe/tool/building expansion.
