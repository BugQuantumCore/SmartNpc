# LootNearbyChestGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/LootNearbyChestGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs loot useful supplies from world chests.

## Behavior

The goal searches nearby chests outside the NPC's saved home area. If the NPC has inventory space, it walks to the chest and transfers up to four useful stacks per activation.

Useful loot includes food, arrows, ender pearls, buckets, fuel/materials, weapons, tools, armor, shields, and block items.

Home chests are skipped so the NPC does not immediately steal back stored items.

When looting, the goal fires the vanilla chest block event to open the lid, plays `CHEST_OPEN`, transfers loot, then schedules a short delayed close event with `CHEST_CLOSE`.

Cooldown uses `PlayerNpcEntity.lootChestCooldown`.
