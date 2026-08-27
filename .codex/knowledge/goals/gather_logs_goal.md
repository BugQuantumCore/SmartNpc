# GatherLogsGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/GatherLogsGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Pillar dirt recovery

When a selected log requires pillaring and carried dirt is below the current pillar plan's requirement, dirt recovery has priority over foliage/route-obstruction probing. Both operations use the NPC's single shared expensive-work admission; probing an unusable obstruction first would otherwise consume that admission every tick and starve the dirt search.

Scheduler contention inside an already-active gather goal is described as work being "queued for a shared expensive-work slice." The distinct phrase "waiting for AI worker turn" is reserved for an unscheduled NPC fallback and must not be used for an active goal that already holds a worker resource.
