# RareSneakGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/RareSneakGoal.java`
- `src/main/java/com/pla/smart_npc/entity/ai/CautiousThreatAi.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Adds a rare cautious crouch response to a real nearby threat.

## Behavior

When idle and off cooldown, the NPC may select the nearest valid cautious threat within eight
blocks. It holds crouch and continuously looks at that cached threat; it stops if combat begins,
the threat becomes invalid, or the threat leaves range. Creative/spectator players do not qualify.
This remains intentionally rare and distinct from the longer low-health-biased hide response.

The goal sets both `setShiftKeyDown(...)` and `setPose(Pose.CROUCHING/STANDING)` so the client renderer can show the player-like sneak animation.

Threat acquisition uses a randomized `CanUseThrottle` interval of at least 20 ticks. Active ticks
perform no entity scan or path creation; they only validate/look at the cached entity and hold
crouch. Cooldown uses `PlayerNpcEntity.rareSneakCooldown`, decremented from the entity tick.
