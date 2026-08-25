# Startup Work Gated Goal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/StartupWorkGatedGoal.java`
- `src/main/java/com/pla/smart_npc/entity/PlayerNpcEntity.java`

## Contract

`StartupWorkGatedGoal` is the central server-startup wrapper for routine Player NPC work. It checks
the server tick before calling the delegate's `canUse()` or `canContinueToUse()`. The minimum grace
is 60 ticks, followed by a stable 0-60 tick UUID-derived per-NPC offset. All work wrappers for one NPC
release together, while existing per-goal `CanUseThrottle` phases continue to spread that NPC's
individual expensive scans.

The gate is based on the current server process tick and is never saved to entity NBT. NPCs spawned
after startup therefore work immediately. Goal running state is not persisted by vanilla, and the
continuation guard defensively stops any work lifecycle that could otherwise exist during the grace.

Only routine work is wrapped: pickup/supply, home/build, crafting/cooking, gathering, mining,
farming, fishing, looting, exploration, and other non-emergency characteristic activities. Target
selection, melee/ranged combat and combat utilities, low-health flee/heal, floating, cautious
avoidance/hiding, calls for help, hole/high-column escape, combat weapon recovery, obstruction
breaking, and door traversal remain registered directly and can react from the first server tick.

Diagnostics must recursively unwrap both `StartupWorkGatedGoal` and `InterestGatedGoal`, so traces
and TPS snapshots continue to show the actual delegate class rather than wrapper names.
