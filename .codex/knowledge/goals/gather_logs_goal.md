# GatherLogsGoal

An unfinished local log scan must not make `hasNearbyUsableLogTarget` return true. It has no
actionable successor yet, and fresh pending scans otherwise reset or interrupt `ExploreAroundGoal`
over and over while the worker remains stationary. Keep the retained bounded cursor in GatherLogs;
allow exploration during PENDING, then let the higher-priority GatherLogs pre-empt when it selects
a real log. This arbitration signal performs no independent scan or path search.

Farming log-supply exploration must remain eligible under opaque cover just like mining, fishing, exploring, and active building. `PlayerNpcEntity.canExploreForLogSupply` may not require sky visibility or immediately adjacent tree cover for a farmer whose required logs are missing; a local search miss must allow relocation to obtain farm/tool supplies. Emergency hole escape retains its higher priority.

The guaranteed center 5x5 log observation is the first slice of an adaptive retained episode.
Stable server headroom expands eventual coverage to 9x9 and then 11x11, but every admitted pass
still observes at most 25 loaded surface columns. The goal freezes scope/origin while pending,
retries the cursor without acquiring MOVE, and caches a miss only after every column is inspected.

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/GatherLogsGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Pillar dirt recovery

When a selected log requires pillaring and carried dirt is below the current pillar plan's requirement, dirt recovery has priority over foliage/route-obstruction probing. Active workers are not denied by the probe queue, but ordering still prevents duplicate costly bounded work in the same goal tick and ensures prerequisite dirt progresses before optional obstruction handling.

An active worker runs its bounded GatherLogs substeps directly. The phrase "waiting for AI worker turn" is reserved for an unscheduled NPC fallback; a holder must not be described as queued behind the probe-only expensive-work gate.

One admitted path is still synchronous and can be pathological. Every remaining GatherLogs diagnostic/recovery path used for pillar-base relocation, pillar descent, or a missing/rejected stand-path rebuild must use `PathNavigationAi.createBoundedPath(...)` with the local 0.03 visited-node multiplier. Dirt target selection is geometric and creates no path; its later normal movement uses the same 0.03 bound. The helper restores the navigation default in `finally`; only route creation is bounded, while a retained successful Path is followed normally. Running stand rebuilds must also reacquire or reuse the NPC's current-tick expensive-work admission.

Tree discovery is also an atomic server-thread eligibility cost. `TreeAi.findNearest(...)` caps a pass at 50 log/block reads and 32 leaf reads. A miss is not proof that a wider region is empty: GatherLogs yields and ExploreAround moves the observation center, allowing later bounded passes to discover farther trees. Do not restore the previous 1,536/512 or 384/96 passes; runtime warnings attributed them to 97-223 ms activation calls.

Before any heightmap or path work, dirt recovery checks a small loaded cube for dirt already mineable from the NPC's current safe stand. General dirt discovery scans at most 16 nearest-first columns per admitted pass. The dirt column cursor remains PENDING when its admitted pass is incomplete; a node-bounded miss advances through later columns/candidates rather than retrying an unbounded path or declaring that no dirt exists.

The 14:17 deployed follow-up measured the remaining dirt slice itself at 56.3 and 61.7 ms with detail `pillar needs dirt; checking nearby area in bounded passes`. The column reads were bounded, but the slice still ended by constructing one synchronous route to a candidate stand. Dirt selection now ends with loaded geometric stand validation only; it stores no path, and normal movement constructs the bounded route on a later goal tick. Thus one admitted dirt observation cannot combine column enumeration and PathFinder work.
`canUse()` performs one complete 5x5 surface-footprint probe and geometric stand selection without PathFinder. It starts the MOVE goal only when that probe produces an immediately actionable local log. A miss returns false, so the retained worker day shift remains available and `ExploreAroundGoal` may start `exploring for logs`; GatherLogs must never remain running merely to search a wide empty area.

The same contract applies after a tree is mined or a target becomes invalid: one bounded local replacement pass may select another actionable log, but a partial/missed pass clears retained search state and stops GatherLogs on the next selector check. The obsolete targetless-deferred state was removed: a running GatherLogs goal has an actionable target or is performing pillar descent/safety cleanup. It never owns MOVE while completing a wide search.

`TREE_SEARCH_RADIUS` remains 6 blocks (a nominal 13x13 area), but a 50-read top-down slice can inspect only about five columns because each column may consume up to nine reads. Immediate GatherLogs eligibility and ExploreAround's nearby-log observation therefore use `TreeAi.findNearestLocalFootprint`: it checks the no-leaves heightmap surface of every loaded column in a 5x5 block footprint (radius 2, 25 reads), then spends a separate bounded tree-expansion budget only after it sees a log. This makes a nearby ordinary surface tree reliably visible as exploration moves past it without interpreting "5x5" as 5x5 chunks or restoring an atomic wide scan. ExploreAround owns relocation and repeated local observation beyond that footprint.

Blocked pillar-base relocation does not combine base enumeration and path construction. It selects the nearest loaded, unprotected, geometrically usable base first, records that relocation, and waits until a later goal tick to build its single bounded route. The 13:35 runtime sampler measured the former combined branch at 123.8 ms in `GatherLogsGoal.tick`.

Replaceable ground decorations at the NPC's feet, including pink petals, are valid soft-cover pillar-space clear targets. They are not structural pillar supports merely because their block position equals the entity's feet; only recorded temporary/support blocks retain that protection. `startBlockerPos` supplies the occupied feet/head/jump-space blocker, not the below-feet structural support, while temporary supports plus farm/home/build protections remain explicit. The direct `ClearBlockAi.start` must pass `allowSoftCover=true`, because petals have an empty collision shape and the default strict clear mode rejects them even when the state predicate accepts them. The 15:15 trace showed Lat repeatedly relocating or reporting `feet not replaceable minecraft:pink_petals` and directly measured one relocation at 104.6 ms. Clear the harmless decoration from the current stand instead of repeatedly searching/pathing to another pillar base.
