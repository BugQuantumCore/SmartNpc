# SleepAtHomeGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/SleepAtHomeGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets some Player NPCs sleep at night when they have a home bed.

## Behavior

At night, an idle NPC with a saved home bed has a chance to walk to the bed and sleep for a short random duration. The chance is not 100%, so some NPCs still stand around, travel, or hunt at night.

The goal stops if combat starts or daytime arrives.

Cooldown uses `PlayerNpcEntity.sleepCooldown`.
