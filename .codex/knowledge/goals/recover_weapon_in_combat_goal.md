# RecoverWeaponInCombatGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/RecoverWeaponInCombatGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets Player NPCs recover usable dropped weapons during combat instead of fighting empty-handed forever after being disarmed.

## Behavior Summary

Searches for recoverable weapon items, moves to them, picks or equips them, and updates Player NPC cached weapon state. This goal has high priority because it repairs combat capability.

Ground-item discovery is a single eight-block `ItemEntity` query behind a randomized 10-15 tick activation throttle. Player NPC approach and 10-tick repaths use a scoped `0.15F` navigation path. The generic `Mob` fallback retains its original navigation call, but the registered Player NPC path cannot expand without a visited-node ceiling.
