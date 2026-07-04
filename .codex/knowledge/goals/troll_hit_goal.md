# TrollHitGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/TrollHitGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets Player NPCs prank a player-like target without committing to a kill.

## Behavior

The goal only targets `Player` and `PlayerNpcEntity` victims. It can use the current player-like target or rarely select a nearby player-like entity while idle. The NPC moves in at max speed `1.0D`, hits once with normal melee handling, immediately clears its target, then runs away for a short duration.

The inspector state is `ai.player_npc.troll_hit` while approaching and `ai.player_npc.troll_running` while escaping.

Cooldown uses `PlayerNpcEntity.trollHitCooldown`.
