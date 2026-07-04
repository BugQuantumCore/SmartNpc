# RareSneakGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/RareSneakGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Adds rare player-like crouch spam near players and Player NPCs.

## Behavior

When idle, off cooldown, and near a `Player` or `PlayerNpcEntity`, the NPC has a small chance to toggle sneak for a few seconds. This is intentionally rare and does not run during combat.

The goal sets both `setShiftKeyDown(...)` and `setPose(Pose.CROUCHING/STANDING)` so the client renderer can show the player-like sneak animation.

Cooldown uses `PlayerNpcEntity.rareSneakCooldown`, decremented from the entity tick.
