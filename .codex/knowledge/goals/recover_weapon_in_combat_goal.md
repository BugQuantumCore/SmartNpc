# RecoverWeaponInCombatGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/RecoverWeaponInCombatGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets Player NPCs recover usable dropped weapons during combat instead of fighting empty-handed forever after being disarmed.

## Behavior Summary

Searches for recoverable weapon items, moves to them, picks or equips them, and updates Player NPC cached weapon state. This goal has high priority because it repairs combat capability.

