# TrollHitGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/TrollHitGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets Player NPCs prank a player-like target without committing to a kill.

## Behavior

The goal can target valid non-allied monsters, golems, villagers, players, Player NPCs, and configured compatible target classes, while excluding creative players and compatibility targets marked to avoid trolling. It can use the current valid target or rarely select a nearby valid entity while idle. The NPC moves in at max speed `1.0D`, hits once with normal melee handling, immediately clears its target, then runs away for a short duration.

The rare idle entity scan remains guarded by a 40-tick cadence and the 3.5% chance is tested before the AABB query. Approach and escape repaths use a scoped `0.15F` path on their 10-tick cadence, so TROLL_HIT remains an unwrapped characteristic without permitting an unbounded synchronous route.

The inspector state is `ai.player_npc.troll_hit` while approaching and `ai.player_npc.troll_running` while escaping.

Cooldown uses `PlayerNpcEntity.trollHitCooldown`.
