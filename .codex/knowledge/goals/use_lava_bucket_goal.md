# UseLavaBucketGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/UseLavaBucketGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Lets Player NPCs place lava near a close combat target when they have a lava bucket.

## Activation

- Server side only.
- NPC must be alive, not riding, not no-AI, and not healing.
- Requires a lava bucket in the Player NPC inventory.
- Requires a close living target that is not already in water.
- Uses the shared `PlayerNpcEntity.bucketCooldown`.

## Behavior

Consumes one lava bucket, places lava near the target, returns an empty bucket to the Player NPC inventory, swings the main hand, and calls `PlayerNpcEntity.setBucketCooldown()`.

`UseWaterBucketGoal` and `UseLavaBucketGoal` intentionally share the same bucket cooldown.
