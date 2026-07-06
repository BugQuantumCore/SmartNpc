# RandomCombatJumpGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/RandomCombatJumpGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Makes Player NPCs randomly jump during combat when `epicfight_player_npc` is not installed.

## Activation

- Player NPC must have a living target.
- NPC must be alive, not riding, not in water/lava, and on ground.
- Uses a short randomized cooldown before each jump attempt.

## Behavior

When selected, calls `PlayerNpcEntity.jump()`, which uses vanilla-style `0.42D` vertical lift and adds forward movement so the motion looks closer to player combat movement.
