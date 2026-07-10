# ReturnHomeGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/ReturnHomeGoal.java`
- `src/main/java/com/pla/smart_npc/entity/ai/ReturnPositionAi.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Makes Player NPCs with a saved home occasionally travel back instead of wandering forever.

It is also the long-range night/thunder shelter handoff. `BeingAtHomeGoal` handles short-range indoor behavior after the NPC is already near the home/base area.

## Behavior

If the NPC is far from its home, has no combat target, and `PlayerNpcEntity.returnHomeCooldown` allows it, it pathfinds to the home center. It is more likely to run when its custom inventory is more than half full, so `ManageHomeBaseGoal` can later deposit items into the home chest.

Nearby home utility work bypasses the ordinary return cooldown. Within 48 blocks of the saved home center, the NPC returns home when it needs home storage, home crafting table work, cooking/smelting, or sleep. This keeps crafting/cooking/storage goals from acting remotely while still allowing temporary field crafting when the NPC is far from home.

Build work can also bypass the ordinary return cooldown from any distance when an unfinished saved home has a valid next placement, both daily log and stone supply targets are met, and the NPC is outside the build work area. `BuildHouseGoal.hasReadyHomeBuildWork(...)` owns the supply gate so return-home does not preempt an active log/stone material trip.

Night/thunder shelter return bypasses that material-reserve gate. If a home/base exists and the NPC is away from the home work area, `ReturnHomeGoal` should start even when logs or stone are still below target. Resource goals should yield during shelter weather so return can claim movement.

In the building-interest bootstrap flow, the base is selected before stone gathering. Stone gathering and dig-down mining therefore use the saved home center as the known return destination. After the stone target is met, return/build behavior should route the NPC back to that base instead of choosing a build area from the mine location.

Movement is delegated to `ReturnPositionAi`, which wraps `PathNavigationAi`, `ClearBlockAi`, `BreakingBlockAi`, `ToolAi`, a bounded dirt `PillarUpAi` step, and upward escape requests. It can safely step down, clear local body/head/path blockers outside the protected home box, direct-pillar a short upward return path, and request broader pillar escape if the NPC is stuck underground while returning.

When the final home-center path is `path=none`, `ReturnPositionAi` should call `PathNavigationAi.moveToWithLocalFallback(...)`. That helper scans nearby standable cells and path-checks a bounded number of local waypoints, preferring cells that are closer to home, under open sky, or upward enough to leave a pit/corner. This prevents return-home from standing still when the home is reachable only after first walking to a nearby exit.

Trace strings include:

- `ai.player_npc.returning_home`
- `returning to home shelter`
- `returning to build site`
- `returning to home utility`
- `local route @ x y z`
- `clearing return path @ x y z`
- `searching route; attempts=N; directPath=...; localCandidates=N; localChecks=N; localRoute=none; clear=...; pillar=...`

Movement speed is `1.0D`.
