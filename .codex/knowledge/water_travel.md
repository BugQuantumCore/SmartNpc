# Player NPC Water Travel

## Source

- `src/main/java/com/pla/smart_npc/entity/ai/WaterEscapeAi.java`
- `src/main/java/com/pla/smart_npc/entity/ai/PathNavigationAi.java`
- `src/main/java/com/pla/smart_npc/entity/PlayerNpcEntity.java`
- Direct owners currently include `GatherLogsGoal`, `GatherStoneGoal`, `BuildHouseGoal`, and `ExploreAroundGoal`.

## Ownership Rule

Player NPC has no global `EscapeWaterCurrentGoal`. `FloatGoal` remains priority zero and owns
buoyancy/JUMP only, allowing the active work goal to retain MOVE and its work destination. A global
water goal must not choose an unrelated nearby bank because it does not know whether the NPC was
crossing a river toward a log, farm supply, build position, or exploration target.

Movement goals that need stronger water recovery use a goal-owned `WaterEscapeAi` directly or the
instance inside their `PathNavigationAi`. Goals with a repath interval must tick water travel every
goal tick, not only when the next path is created, and must stop the helper from their own `stop()`.
Log gathering, stone gathering, and `ExploreAroundGoal` retain vanilla's reduced goal tick cadence;
the capped water impulse persists between updates and does not justify promoting their full state
machines with `requiresUpdateEveryTick()`. `FloatGoal` continues to own only JUMP.

## Destination-Preserving Behavior

When a preferred destination exists, `WaterEscapeAi` swims toward that same destination and uses
`FloatGoal` for buoyancy. It normally selects a dry exit only when the exit reduces horizontal
distance to the owning destination; an exit on the bank the NPC just left is rejected. A destination
directly above or below the current water column is the exception: horizontal swimming cannot reach
it, so the best dry side exit may briefly increase distance and let the owner repath from land.
Destination swimming and no-progress episodes are bounded so an unreachable occupied target cannot
consume AI time forever. The absolute episode start, swim count, best distance, and failure history
survive transitions through dry-exit and footing fallback; resuming swimming must not reset them.
Because destination swimming stops land navigation, `MoveControl.setWantedPosition(...)` alone is
not horizontal propulsion for this NPC. Water travel also applies a small capped X/Z acceleration
toward the owner destination (and destination-side dry exit); omitting it leaves the NPC bobbing in
place until the stall timeout despite a correct retained target.
The acceleration, look update, fluid-state check, and progress arithmetic are cheap enough for each
owning goal tick. Dry-exit discovery and shallow/wall footing discovery are not: all destination recovery
world scans share one absolute `tickCount` gate and run at most once per 20 server ticks, independent
of how often an owning goal invokes the helper. Those scans also acquire `PlayerNpcAiWorkBudget`;
denial schedules a short jittered retry while cheap steering continues and does not count as a failed
stall episode. Once a GatherLogs destination episode fails, abandon that exact log route before
admitted reselection instead of feeding the same target back into a permanently failed helper.

Do not slow the whole `GoalSelector` to 20 ticks to protect these scans. That phase-locks all NPC
activation, target, farm, tree, fishing-water, and path work into a once-per-second server-thread
burst. Keep vanilla selector cadence and use each expensive goal's randomized `CanUseThrottle` plus
the helper's absolute 20-tick recovery gate.

Water steering can expose an existing land-recovery cost when it successfully delivers several
NPCs to their targets at once. In particular, log pillaring must not rescan a 21x5x21 dirt volume
and path every possible stand on each goal tick. Missing-dirt and alternate-base recovery are
staggered by at least 20 ticks, tree work is local, and both outer targets and inner stand-path
checks have hard budgets.

Footing placement is recovery, not the first action after entering water. It is considered only
after destination swimming stalls, and only in one-block-deep water or while next to a solid wall or
shore obstruction. The placed position is biased toward the owning destination. Ordinary river
crossings do not place blocks or switch banks.

Land pillar requests are invalid while the NPC is fluid-aware wet (entity water flag, feet fluid,
head fluid, or water directly below an airborne surface-bobbing body). `PlayerNpcEntity` clears stale upward requests centrally, and
`EscapeHoleWithBlockGoal` uses the same wet predicate for activation and continuation. This prevents
an old exploration climb from preempting the active water crossing at a fluid tick boundary.

`ReturnHomeGoal.needsHomeSurfaceRecovery(...)` must also reject the same fluid-aware wet state.
Water bobbing can floor `blockPosition().getY()` one block below a saved home origin while X/Z is
still inside the build footprint. Treating that as an underground-home failure lets priority-four
ReturnHome cancel a priority-six gather route, issue a land upward request that water safety clears,
and leave only FloatGoal; the gatherer then rescans the same tree after its cooldown. Re-evaluate
home surface recovery after the NPC is dry instead.

A started log/stone supply episode or log/stone ExploreAround route owns its destination during clear daytime, including after it crosses
the saved home work-area boundary. Ready build/utility work must not let priority-four ReturnHome
preempt that priority-six supply owner; night/thunder and explicit exploration/home-surface recovery
remain allowed to interrupt it. Without this ownership latch the NPC advances a few water blocks,
stops, rescans the same tree/path after cooldown, then starts an unrelated ExploreAround route.
