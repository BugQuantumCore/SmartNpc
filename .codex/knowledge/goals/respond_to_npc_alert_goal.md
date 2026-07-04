# RespondToNpcAlertGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/RespondToNpcAlertGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets nearby Player NPCs react to help/death alerts from other Player NPCs.

## Activation

- NPC must be alive, idle, and have no target.
- Requires a nearby active alert from `PlayerNpcAlertManager`.

## Behavior

The responder compares its own health and gear score with the alerted threat. Stronger responders target the threat and assist. Weaker responders avoid the threat by pathing away.

Avoid movement uses speed `1.0D`. Keep flee/avoid speeds at `1.0D`; do not raise them to `1.25D+`.

Death/griefing alerts are raised only for player-like killers: vanilla `Player` and `PlayerNpcEntity`. Zombies, creepers, and other monsters should not trigger the "everyone be careful, <name> is griefing" style alert.
