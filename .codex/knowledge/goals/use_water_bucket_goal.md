# UseWaterBucketGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/UseWaterBucketGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Lets Player NPCs use a water bucket defensively when they are burning or in lava.

## Activation

- Server side only.
- NPC must be alive, not riding, not no-AI, and not healing.
- Requires a water bucket in the Player NPC inventory.
- Runs only when the NPC is on fire or in lava.
- Uses the shared `PlayerNpcEntity.bucketCooldown`.
- Does not require the NPC to be on the ground, so it can still attempt lava/fire saves while falling or swimming.

## Behavior

Consumes one water bucket, places a water source at the NPC, above/below it, or in a nearby front/back space, clears fire, and gives the NPC an empty bucket.

After a short delay, the goal tries to pick the placed source back up: it consumes the empty bucket, removes the water source if it is still present, and returns a water bucket to the inventory.

`UseWaterBucketGoal` and `UseLavaBucketGoal` both call `PlayerNpcEntity.setBucketCooldown()`, so water and lava bucket use cannot chain immediately.
