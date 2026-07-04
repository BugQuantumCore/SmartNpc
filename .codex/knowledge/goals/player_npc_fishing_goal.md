# PlayerNpcFishingGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/PlayerNpcFishingGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Adds a non-combat fishing job using vanilla fishing loot tables.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Requires nearby water and a fishing rod in hand or inventory.
- Cooldown uses `PlayerNpcEntity.fishingCooldown`.

## Behavior

Temporarily equips a fishing rod if needed, looks toward water, swings/casts, waits a randomized fishing time, rolls vanilla `BuiltInLootTables.FISHING`, inserts loot into the NPC inventory or drops overflow, then restores the previous main-hand item.

Retrieving the catch damages the active fishing rod by 1 durability.
