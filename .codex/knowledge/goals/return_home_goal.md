# ReturnHomeGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/ReturnHomeGoal.java`
- `src/main/java/com/pla/smart_npc/entity/ai/ReturnPositionAi.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Makes `BUILDING` Player NPCs with a saved home occasionally travel back instead of wandering forever.

It is also the long-range night/thunder shelter handoff. `BeingAtHomeGoal` handles short-range indoor behavior after the NPC is already near the home/base area.

Pure mining NPCs are not gated into `ReturnHomeGoal`; they should not use a saved-home return path or return home at night. Mining-only surface escape belongs to `ExploreCaveOreGoal` and is limited to daytime log resupply.

## Behavior

If the NPC is far from its home, has no combat target, and `PlayerNpcEntity.returnHomeCooldown` allows it, it pathfinds to the home center. It is more likely to run when its custom inventory is more than half full, so `ManageHomeBaseGoal` can later deposit items into the home chest.

Nearby home utility work bypasses the ordinary return cooldown. Within 48 blocks of the saved home center, the NPC returns home when it needs home storage, home crafting table work, cooking/smelting, or sleep. This keeps crafting/cooking/storage goals from acting remotely while still allowing temporary field crafting when the NPC is far from home.

Build work can also bypass the ordinary return cooldown from any distance when an unfinished saved home has a valid next placement, both daily log and stone supply targets are met, and the NPC is outside the build work area. `BuildHouseGoal.hasReadyHomeBuildWork(...)` owns the supply gate so return-home does not preempt an active log/stone material trip.

Night/thunder shelter return bypasses that material-reserve gate. If a home/base exists and the NPC is away from the home work area, `ReturnHomeGoal` should start even when logs or stone are still below target. Resource goals should yield during shelter weather so return can claim movement.

In the building-interest bootstrap flow, the base is selected before building-driven stone gathering. Building stone gathering and dig-down mining therefore use the saved home center as the known return destination. After the stone target is met, return/build behavior should route the NPC back to that base instead of choosing a build area from the mine location.

If the NPC finishes its stone supply while below the saved home/base area, `ReturnHomeGoal` can bypass both the ordinary return cooldown and the material-reserve gate even when logs have dipped below the daily target. This uses the `returning from dig site` detail and lets `ReturnPositionAi` own pathing, safe drops, route clearing, and upward escape requests. Log gathering and log exploration should yield to this condition first; otherwise the NPC can sit in a dig pit and repeatedly request exploration climbs instead of returning to the known base.

Movement is delegated to `ReturnPositionAi`, which wraps `PathNavigationAi`, `ClearBlockAi`, `BreakingBlockAi`, `ToolAi`, a bounded dirt `PillarUpAi` step, and upward escape requests. It can safely step down, clear local body/head/path blockers outside the protected home box, direct-pillar a short upward return path, and request broader pillar escape if the NPC is stuck underground while returning.

Under the saved home footprint is treated as outside the house for return-home purposes. `ReturnHomeGoal` enters `homeSurfaceRecoveryReturn` when the NPC is below the home origin Y in the home work margin. It also recognizes the boundary case where the NPC is exactly at origin Y, has no sky access, and the local motion-blocking heightmap surface is more than one block above its feet. This catches a builder in a nearby underground tunnel while leaving a normal open-sky NPC at origin Y alone. `BuildHouseGoal` yields while this predicate is true so it cannot monopolize movement by rotating through unreachable placements.

Surface recovery requests use a real surface target instead of always using `homeCenter` at `origin + 1`. Outside the exact blueprint footprint, return-home prefers the nearest standable open-sky heightmap cell in the bounded home work margin and requests enough pillar capacity to reach it. Directly under the exact footprint, it requests a short climb in the NPC's current column. During that forced under-footprint request, `EscapeHoleWithBlockGoal` may clear only the current vertical column despite normal home protection, allowing it to break the floor/base above the NPC and pillar up. Terraform/build are expected to repair the damage afterward.

This under-home exception is not used by stone gathering. `GatherStoneGoal` should still skip stone targets and clear-block candidates in the protected home/build/below-home footprint. If stone gathering finds the NPC inside the footprint, it must stop stone breaking/clearing and route outside the footprint before stone path clearing; if no outside egress route exists, it drops the stone target instead of clearing from inside. Return-home recovery is allowed to break upward through the base only because its job is to rescue the NPC from below the house.

Builder home return uses the last working `ad93109`/`731df2d` recovery sequence: after pathing and ordinary clearing fail, it tries the bounded direct-pillar fallback, then requests forced upward escape after four failed routes or whenever vertical escape is already required. `ReturnHomeGoal` enables this historical sequence only while BUILDING is the active daily job; a multi-interest NPC currently working as a farmer uses the normal return helper instead. Farming and mining callers also retain the newer relocation/path-stuck behavior. Return-route clearing must never select the block directly under the NPC or any remembered temporary pillar support, including after `ClearBlockAi` retargets dynamically, so the restored fallback cannot break its own pillar. If a genuine vertical pillar blocker is clearable and not otherwise protected, it can clear that blocker with `ClearBlockAi`. Terraform must not run while an upward escape target exists or while the NPC is in `ai.player_npc.pillaring_up`, so fill-support does not replace the just-cleared escape block before the NPC climbs.

`BeingAtHomeGoal` may only start or continue while the NPC is within ReturnHome's margin-four work area and does not need home-surface recovery. Its unfinished-home shelter fallback must enforce the same bounds for both the current-position shortcut and every generated stand candidate. This prevents a loaded NPC that is merely within the old broad 28-block radius, or is underground near the base, from taking indoor idle ownership before `ReturnHomeGoal` can recover it.

When the final home-center path is `path=none`, `ReturnPositionAi` should call `PathNavigationAi.moveToWithLocalFallback(...)`. That helper scans nearby standable cells and path-checks a bounded number of local waypoints, preferring cells that are closer to home, under open sky, or upward enough to leave a pit/corner. This prevents return-home from standing still when the home is reachable only after first walking to a nearby exit.

An uphill builder return may also follow the bounded direct pathfinder result when A* cannot reach the
final home node but does find useful progress up the terrain. This opt-in applies only to builder home
return. The incomplete endpoint must be loaded, standable, no lower than the NPC, and strictly closer
to home; every retained path node must stay loaded and fluid-free without a drop over the safe-return
limit. The same `Path` object is handed to navigation instead of paying for a second path search.

Open-sky builder return does not synthesize a forced climb merely because the goal is interrupted
below a distant mountain home. Direct pillaring waits for the normal failed-route threshold. A forced
upward escape is reserved for actual underground recovery and is not requested while the NPC stands
on an owned temporary pillar. The explicit below-home-footprint surface recovery remains available.
While an uphill builder return is pending, generic column descent yields at admission, continuation,
each goal tick, and the final breaking predicate. Night/thunder makes this intent independent of a
temporary idle or cooking state, so a brief goal interruption cannot remove return-route supports.

Trace strings include:

- `ai.player_npc.returning_home`
- `returning to home shelter`
- `returning to build site`
- `returning from dig site`
- `returning to surface`
- `returning to home utility`
- `local route @ x y z`
- `clearing return path @ x y z`
- `clearing return pillar space @ x y z`
- `searching route; attempts=N; directPath=...; localCandidates=N; localChecks=N; localRoute=none; clear=...; pillar=...`

Movement speed is `1.0D`.
