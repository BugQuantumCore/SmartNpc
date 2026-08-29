# GatherLogsGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/GatherLogsGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Pillar dirt recovery

When a selected log requires pillaring and carried dirt is below the current pillar plan's requirement, dirt recovery has priority over foliage/route-obstruction probing. Both operations use the NPC's single shared expensive-work admission; probing an unusable obstruction first would otherwise consume that admission every tick and starve the dirt search.

Scheduler contention inside an already-active gather goal is described as work being "queued for a shared expensive-work slice." The distinct phrase "waiting for AI worker turn" is reserved for an unscheduled NPC fallback and must not be used for an active goal that already holds a worker resource.

One admitted path is still synchronous and can be pathological. Every GatherLogs diagnostic path used for log/dirt stand selection, pillar-base relocation, pillar descent, and a missing/rejected stand-path rebuild must use `PathNavigationAi.createBoundedPath(...)` with the local 0.03 visited-node multiplier; dirt recovery uses a 0.05 multiplier. The helper restores the navigation default in `finally`; only the selection probe is bounded, while a retained successful Path is followed normally. Running stand rebuilds must also reacquire or reuse the NPC's current-tick expensive-work admission.

Tree discovery is also an atomic server-thread eligibility cost. `TreeAi.findNearest(...)` caps a pass at 384 log/block reads and 96 leaf reads. A miss is not proof that the whole radius is empty: GatherLogs yields and ExploreAround moves the observation center, allowing later bounded passes to discover farther trees. Do not restore the previous 1,536/512 pass; runtime warnings attributed it to 97-192 ms `GatherLogsGoal.canUse` calls and to 108-144 ms `ExploreAroundGoal.canUse` calls through the log-priority predicate with only four loaded NPCs.

Before any heightmap or path work, dirt recovery checks a small loaded cube for dirt already mineable from the NPC's current safe stand. General dirt discovery scans at most 16 nearest-first columns per admitted pass. The dirt column cursor remains PENDING when its admitted pass is incomplete; a node-bounded miss advances through later columns/candidates rather than retrying an unbounded path or declaring that no dirt exists.
`canUse()` performs only cheap eligibility/admission and starts a deferred search episode; it does not scan trees, choose a path stand, or move. `start()` also does not navigate. The first running search occurs no earlier than the following tick, then Tree discovery is a retained admitted search rather than a complete synchronous activation scan. `TreeAi` reads at most 50 log-search blocks and 32 leaf blocks per call; a stationary GatherLogs NPC retains the next column and keeps the episode alive without applying the terminal gather cooldown until the full loaded search area is covered. A found tree resets the cursor and preserves its log queue. This fixes repeated 127.5-223.5 ms `GatherLogsGoal.canUse` failures whose final NPC state was idle. Explore/GatherStone callers retain their existing moving-center behavior but receive the same small atomic TreeAi bound.
