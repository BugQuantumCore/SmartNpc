# ScaredHideGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/ScaredHideGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Adds a rare scared idle behavior where the NPC crouches and does nothing briefly.

## Behavior

When idle near multiple living entities, the NPC can rarely clear its target, stop navigation, hold sneak, and hide for a short random duration. The chance is higher at low health but still uncommon.

The goal holds both `setShiftKeyDown(true)` and `setPose(Pose.CROUCHING)` while active so the player-like sneak animation is visible.

Cooldown uses `PlayerNpcEntity.scaredHideCooldown`, decremented from the entity tick.
