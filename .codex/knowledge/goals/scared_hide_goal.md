# ScaredHideGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/ScaredHideGoal.java`
- `src/main/java/com/pla/smart_npc/entity/ai/CautiousThreatAi.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Adds a rare scared response where the NPC crouches and watches a nearby threat.

## Behavior

When idle, the NPC may select the nearest valid cautious threat within ten blocks, stop navigation,
hold crouch, and continuously watch it. The chance is higher at low health but remains uncommon.
The episode ends when combat begins, the cached threat becomes invalid, or it leaves range.
Unrelated passive entities and creative/spectator players cannot trigger hiding.

The goal holds both `setShiftKeyDown(true)` and `setPose(Pose.CROUCHING)` while active so the player-like sneak animation is visible.

Threat acquisition uses a randomized `CanUseThrottle` interval of at least 20 ticks. Active ticks
perform no entity scan or path creation. Cooldown uses `PlayerNpcEntity.scaredHideCooldown`,
decremented from the entity tick.
