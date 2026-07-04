# IronGolemTrollGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/IronGolemTrollGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Adds a very rare iron golem trolling behavior without making village destruction common.

## Behavior

If an idle NPC has at least three normal placeable blocks, sees an iron golem nearby, passes a low random chance, and is off a long cooldown, it builds a three-block pillar, moves onto the top, and targets the golem.

Cooldown uses `PlayerNpcEntity.ironGolemTrollCooldown`. It is 10-20 minutes and the random chance is intentionally low.
