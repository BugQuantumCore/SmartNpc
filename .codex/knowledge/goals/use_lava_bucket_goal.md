# UseLavaBucketGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/UseLavaBucketGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Lets Player NPCs place lava near a close combat target when they have a lava bucket.

## Activation

- Server side only.
- NPC must be alive, not riding, not no-AI, and not healing.
- Requires a lava bucket in the Player NPC inventory.
- Requires a close living target that is not already in water.
- Uses the shared `PlayerNpcEntity.bucketCooldown`.
- Offensive lava activation and its atomic start/consume commit wait while the actual
  obstruction goal owns `isClearingCombatObstruction()`, including nested tool crafting.
  Emergency water-bucket handling is independent and retains its safety priority.

## Behavior

Consumes one lava bucket, places lava near the target, returns an empty bucket to the Player NPC inventory, swings the main hand, and calls `PlayerNpcEntity.setBucketCooldown()`.

`UseWaterBucketGoal` and `UseLavaBucketGoal` intentionally share the same bucket cooldown.

Placement revalidates loaded state and restores the filled bucket if the mutation
fails. Only a successful placement signals main-hand use. Epic Fight attack/chase
yield to MOVE/LOOK ownership and remain blocked during USE_MAINHAND recovery.
