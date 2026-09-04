# Building Interest Flow

World-source lookup for a missing sand, plant, bed, or other block is nearest-first and retained.
It freezes the shared adaptive radius 8/16/32 scope for the episode and inspects eight loaded columns
per selector pass across the existing vertical band. A partial pass is not cached as absence; the
same cursor resumes promptly while the NPC remains near its search center, and the exploration
fallback treats PENDING separately from a completed miss. Because columns are nearest-first, the
first valid positive is returned immediately; only a negative result must cover the full radius.
Bed lookup uses this same bounded path instead of an atomic radius-32 volume sweep.

Blueprint missing-material selection scans the full layout in bounded cursor slices and retains the
best deterministic candidate instead of returning the first block in file order. Priority is wood /
logs first, stone second, ordinary non-primary and utility materials third, and beds/carpets last.
No provisional bed result is exposed while later slices are still able to discover a higher-priority
need; that pending scan continues to defer daytime return-home work. While the cursor is pending, the
last completed structurally matching result remains authoritative, and an active log/stone episode
retains its previous demand until the refresh completes. Equal-priority materials retain blueprint order. Existing log/stone goals consume the primary
kinds; `GatherMissingBuildMaterialGoal` handles the ordinary tier and reaches bed only after those
needs are satisfied.

The bounded search owns a simulated inventory ledger for its complete cursor pass. Every unmatched
blueprint placement consumes one direct or craftable item from that snapshot before the next block
is evaluated. Without this ledger, one carried log, plank, or stone could be reused by every
remaining block check, leaving an unfinished large house reporting `needLogSupply=false` and
`needCobbleSupply=false`. The ledger survives cursor slices and is discarded whenever the inventory
hash, home, dimension, or layout changes. It never mutates the NPC's real inventory.

For a resource-holding builder, daytime missing non-primary build material remains gathering work.
`ReturnHomeGoal` must evaluate that material deferral before its higher-priority ready-build return:
otherwise a blueprint can be `build=true` while still reporting (for example) `missing=bed`, causing
ReturnHome to interrupt `ExploreAroundGoal` every time its cooldown expires. During daylight and clear
weather, missing-material gathering is allowed to continue. `BuildHouseGoal` starts and continues
placement only at night or during thunder, and its shared ready-work predicate must observe the same
window so it cannot suppress daytime material goals when the builder itself is unable to start.
Higher-priority sleep and safety goals can still preempt.
Completed supply routes retain their normal return behavior.

A generic pillar recovery obstruction inside the protected build footprint is not cleared. Escape first selects an open alternate pillar column; if none exists it runs `PathStuckFallbackAi` toward a safe non-footprint surface stand, clears stale pillar/place state, and replans from the new feet. If no safe step-off exists, the bounded episode ends for a later retry rather than destroying the structure or retaining MOVE indefinitely.

## Source

- `src/main/java/com/pla/smart_npc/entity/PlayerNpcEntity.java`
- `src/main/java/com/pla/smart_npc/entity/goal/GatherLogsGoal.java`
- `src/main/java/com/pla/smart_npc/entity/goal/GatherStoneGoal.java`
- `src/main/java/com/pla/smart_npc/entity/goal/ExploreAroundGoal.java`
- `src/main/java/com/pla/smart_npc/entity/goal/TerraformBuildSiteGoal.java`
- `src/main/java/com/pla/smart_npc/entity/goal/BuildHouseGoal.java`
- `src/main/java/com/pla/smart_npc/entity/goal/ReturnHomeGoal.java`
- `src/main/java/com/pla/smart_npc/entity/goal/BeingAtHomeGoal.java`
- `src/main/java/com/pla/smart_npc/entity/ai/*.java`

## Purpose

Building-interest NPCs now use a clearer worker chain instead of putting all resource and build logic in `PlayerNpcEntity`.

## Flow

1. Spawn rolls `logSupplyGoal` and `stoneSupplyGoal`. At Minecraft day start (`dayTime % 24000 == 0`), `PlayerNpcEntity` rerolls both supply goals once for the new day and wakes gather/explore cooldowns.
2. At day time `1`, `PlayerNpcEntity` selects one daily job from job interests (`BUILDING`, `MINING`, `FARMING`, `FISHING`, `EXPLORING`). If the NPC has `BUILDING` but no saved home layout id, BUILDING is forced until the base/layout is chosen.
3. Building goals are active only when BUILDING is the selected daily job, while the base-selection lock is forcing BUILDING, or during night/thunder home-duty once a saved home exists. Characteristics such as `HUNT_MONSTERS` remain opportunistic and do not become the daily job.
4. Logs are gathered first while `ResourceAi.countLogs(playerNpc) < playerNpc.getLogSupplyGoal()`.
5. `GatherLogsGoal` uses `TreeAi` to find a connected log cluster, mines nearest logs first, clears covering leaves with `ClearBlockAi`, and uses `PillarUpAi` with dirt when upper logs need one-block-at-a-time pillaring. Building-site prep arbitration happens before the route starts; once a bounded route is admitted, it may finish even if Terraform's short-lived actionable-prep cache expires. A conservative cache miss must not stop the active episode because `TerraformBuildSiteGoal` intentionally cannot start until that episode ends; doing so creates a foliage/cooldown/reselect loop. Safety, weather, home-return, and live material-demand checks still end the route normally.
   `gatherCooldown` throttles repeated actionable-log scans only; it must not gate the lower-priority `ExploreAroundGoal` log route. An active tree route retains MOVE while its next-target search is waiting for or continuing an admitted bounded slice, instead of stopping after one partially mined/finished log. The log-search registration enables `continueAcrossReachedTargets`, so while log demand remains and no usable log target is nearby, exploration owns its reachable path to completion and selects/moves toward the next reachable area within the same running goal. A ready active farmer with no planting supplies yields this passive log exploration to `FarmCropGoal.shouldExploreForFarmSupplies(...)`; an actionable tree can still be claimed by the higher-priority gather goal. Other farm, fishing, stone, and generic exploration registrations retain their stop/restart behavior.
6. If log gathering needs dirt for pillaring, it temporarily targets nearby dirt or grass, mines it, then returns to the tree queue.
7. Once logs are satisfied, `BuildHouseGoal` may select and save a base/layout using wood-only bootstrap reserves. Actual block placement is still blocked until stone supply is met.
8. After the base is saved, `CraftBasicGearGoal` should craft a wooden shovel when `TerraformBuildSiteGoal.needsShovelForPrep(...)` detects shovel-clearing work. If the NPC is already adjacent to the home crafting table, crafting should proceed even when vanilla pathing to the exact stand cell returns `path=none`.
9. `TerraformBuildSiteGoal` prepares the saved base footprint before stone gathering starts. It yields to gear crafting when the next clear target needs a shovel and no shovel is available.
   Its initial and between-block footprint searches remain cursor-sliced for TPS, but an incomplete scan reserves the builder work turn and retains Terraform ownership until it resolves. Each admitted running-goal tick inspects at most four clear blocks or one support column and then resumes on the next normal goal tick; only shared-admission denial adds randomized retry backoff. Do not stack an extra one-to-four-tick delay after a successful bounded slice, because that turns a modest negative footprint pass into many seconds of targetless `checking build site` MOVE ownership. Lower-priority resource gathering and log exploration must treat that pending reservation as prep priority, not as a completed no-work result. A completed negative result remains authoritative for its matching supply/fill phase so Terraform releases MOVE instead of immediately repeating the full pass.
   A completed scan with no prep target is authoritative and Terraform's own `canUse()` must honor it; only a pending scan may publish `prep=true` and retain MOVE. The negative result is invalidated when the home/layout/dimension or log, stone, or fill-material supply phase changes, when a failed clear target becomes retryable, or by the bounded safety refresh. This prevents a prepared large blueprint from immediately entering another long `checking build site` pass and starving its missing-resource goals.
   High clear targets are not treated as directly reachable just because the NPC is horizontally close. If the target is more than three blocks above the NPC's feet, terraform must try temporary scaffold/pillar placement first. Temporary terraform scaffold may use dirt/cobble-style fill blocks or planks, and may convert one raw log into planks when no scaffold stack exists. Support filling still excludes planks/logs. During a support-fill placement windup the NPC visibly holds the exact selected fill block in main hand; the one-item held stack is consumed only after placement succeeds, while completion, final failure, target invalidation, vertical-escape handoff, or goal interruption returns any unconsumed fill item and restores the original hand.
10. Stone gathering starts only after a saved base exists, log supply is met, a pickaxe is carried, and no actionable terraform work remains. `GatherStoneGoal.isStoneSupplyPhaseActive(...)` is the central gate for this phase: enough logs, pickaxe, prepared base, plus unmet cobblestone supply or current build stone need. `GatherStoneGoal` uses `StoneAi` to queue connected stone clusters, mines nearby stone outside the protected base/build/support volume, and only starts breaking after the NPC is standing in a same-Y adjacent stand position or can mine with a clear ray from its current position. Stand cells may be already reachable or clearable by `ClearBlockAi`; if none is actionable, `DigDownForStoneGoal` chooses a random dig site near but outside the base and digs a stair downward for stone.
11. `BuildHouseGoal` and build-return readiness require both daily supply goals to be met before actual blueprint placement. This applies after a base/layout exists; it must not block the first no-home bootstrap path that only needs logs to choose and save a base.
12. If the blueprint is missing non-primary material after log and stone supplies are met, `GatherMissingBuildMaterialGoal` runs before more build placement. It ignores log/stone-family requirements, gathers sand for glass/glass panes, gathers any nearby plant for plant-like blocks, gathers a loose bed or hunts sheep while a bed/carpet requirement is still missing, and otherwise only gathers world blocks that match the missing blueprint block/family. Existing furnace/crafting utilities handle smelting sand into glass and crafting panes/beds afterward.
13. When the material checklist is satisfied, return/build behavior can route the NPC back to the known base and `BuildHouseGoal` places the blueprint.
14. After stone supply is met in a dig site below the saved base, `ReturnHomeGoal` should route the NPC back to the known base before log exploration or missing-material exploration can issue another climb request. This return path does not require both daily supply goals to be met; it exists to get the NPC out of the mine and back to the saved work area.
15. At night or during thunder, an NPC with a home/base should yield gathering to `ReturnHomeGoal`. `ReturnHomeGoal` uses `ReturnPositionAi` to route back toward the saved base while allowing safe lower steps, local obstruction clearing, and upward escape requests when stuck underground. When runnable or pending-reserved construction exists, a shelter return hands MOVE off at a safe home-work-area surface and records arrival cooldown so it cannot immediately preempt construction again. Once near the base, ready terraform/build work may continue; otherwise `BeingAtHomeGoal` handles indoor standing, walking, looking, utility spots, doors, and sneaking.
16. Home utility material needs such as torch-charcoal or stone smelting must not suppress primary log/stone exploration while those daily supply targets are still unmet. The primary resource pass must always be able to explore for the missing logs or stone during safe daytime weather, except for the completed-stone-trip return described above.

## Reusable AI Helpers

- `ResourceAi`: material counts and resource classification.
- `TreeAi`: connected tree/log detection and nearest-first ordering.
- `ToolAi`: main-hand tool or item swapping and restoration.
- `ToolAi` must use `PlayerNpcEntity.setMainHandItemForAi(...)` and the cached weapon helpers when swapping tools. This keeps temporary work tools from being promoted as the permanent weapon, while still allowing a cached axe/pickaxe/shovel to be found and moved back to inventory after work.
- `WeaponAi`: reusable best-melee-weapon equip/restore helper for hunting and combat-adjacent utility work.
- `StoneAi`: connected stone-cluster detection and nearest-first ordering for stone gathering.
- `BreakingBlockAi`: shared block-breaking loop for logs, dirt, stone, and obstruction clearing. It owns vanilla-style hardness/tool-speed break timing, break progress, hit sounds, visible main-hand attack animation, tool selection, held-tool durability damage, and random mining sneak.
- `ClearBlockAi`: reusable obstruction clearing, currently used for leaves covering log targets or pillar routes. It delegates mining to `BreakingBlockAi`.
- `ClearBlockAi.isPhysicalObstructionState(...)` is the preferred predicate for route clearing such as dig-site, stone-stand, pickup, and return-home movement. Do not use broad `!state.isAir()` route predicates, because non-colliding grass, flowers, and other plant clutter should not be destroyed just because the NPC is walking through them.
- `PillarUpAi`: one-block pillar placement using a configured block item/state. Its player-paced delay runs before the jump so the placement check cannot miss the short airborne clearance window. After landing on the exact new support, two consecutive grounded ticks confirm a stable step before the caller continues; the longer landing timeout and per-tick support-chain validation remain unchanged. While airborne it remembers the first live overhead collision block; if the jump cannot gain enough height, `FAILED` exposes that exact block after the NPC falls so the owning goal can clear one blocker and retry. Pillar/scaffold entity checks must ignore the inspectator rider, entities riding the NPC, spectators, items, and entities sharing the NPC's root vehicle.
- `ReturnPositionAi`: reusable return-to-position movement helper for home/future farmland returns. It wraps `PathNavigationAi`, `ClearBlockAi`, `BreakingBlockAi`, `ToolAi`, a bounded dirt `PillarUpAi` step for upward return paths, and upward escape requests so long-distance returns can clear small blockers and climb out of holes without goal-specific navigation code.
- `PathNavigationAi`: shared navigation wrapper. Besides direct Minecraft paths and safe lower steps, it can choose a nearby reachable local waypoint when the final destination has no direct path. This is used by return-home so a NPC can leave pits/corners and keep making progress toward base.
- `SneakingAi`: reusable shift-key and crouch-pose control used by home behavior and block mining. While active it also hides the Player NPC display name through a synced render flag, including inspectator name forcing.
- `PlayerNpcBuildMaterialUtil.findMissingBuildMaterialNeed(...)`: shared blueprint checklist query used by build, custom material gathering, glass smelting, and bed/sheep logic.

## Log Stand And Pillar Rules

- `GatherLogsGoal` must not fall back to the NPC's current position when a target log has no reachable stand position. That caused first-spawn stalls where the inspector showed `log @ ...` while navigation targeted the NPC's own feet.
- Log stand selection scans adjacent target columns and nearby surface cells, preferring positions horizontally close to the log column before distance to the NPC.
- `GatherLogsGoal.hasNearbyLogTarget` must mean an actionable log target, not only any tree in scan range. Exploration should still run when a tree exists but no reachable stand/break position can be selected.
- If path creation to the selected stand position fails while the log is out of break range, `GatherLogsGoal` should reselect immediately instead of waiting for `MAX_GATHER_TICKS`.
- Dirt collection for `PillarUpAi` uses the same stand finder but seeds surface dirt/grass candidates first, then bounded nearby dirt candidates. This avoids rejecting ground dirt because of old log-only `target.relative(direction).below()` assumptions.
- If a high log needs pillaring and no dirt is in inventory, inspector detail should move to `searching dirt for pillar` or `collecting dirt for pillar`, not remain silently stuck on `log @ ...`.
- If an overhead collision prevents a log-gathering pillar jump, `GatherLogsGoal` consumes the blocker reported by `PillarUpAi`, clears that single physical block, and retries the same climb. It must preserve current/temporary pillar supports, owned farm cells, home/build blocks, temporary crafting tables, fluids, block entities, and unbreakable blocks; an unsafe blocker causes relocation instead of destruction.
- Furnace work must defer to unmet primary log supply during safe daytime. Once `GatherLogsGoal` owns an active tree, dirt, pillar-up, or pillar-descent episode, it retains that ownership until the episode ends even if night or thunder begins; `CookFoodGoal` and non-emergency `CraftBasicGearGoal` must not redirect its navigation partway through the column route. Gear crafting may still complete one starter recipe before the initial log episode begins, and genuine underground/upward-escape pickaxe crafting remains an exception.

## Terraform And Pickup Vertical Reach Rules

- `TerraformBuildSiteGoal` has its own temporary scaffold cleanup because it may need to clear blocks inside the future footprint before normal build placement. Its scaffold list and still-valid high work target must survive goal interruption/restart, and every placed scaffold must also be registered with the NPC's temporary-pillar-support memory. An interrupted Terraform pass resumes the high target before cleanup; when that target is already complete or invalid, it resumes explicit top-down cleanup before selecting new blueprint work. Ordinary blueprint and route clearing must reject those supports; only that cleanup path may remove them. A clear target more than three blocks above the NPC's feet should show `pillar up @ ...` or `pillar blocked for clear @ ...`; it should not sit on `clear @ ...` with navigation already done. If no dirt/cobble-style scaffold is available, terraform can convert one inventory log to planks for temporary scaffold.
- A forced climb requested specifically for a below-home `FILL_SUPPORT` target creates a bounded Terraform handoff for that exact support. `DescendHighColumnGoal` yields only while the handoff target remains actionable, allowing Terraform to regain its normal priority after the escape cooldown and place the support before column cleanup. Completion, invalidation, missing fill material, bounded placement failure, inactive building work, or timeout clears the handoff so ordinary abandoned-pillar descent resumes. This does not change emergency escape or combat priority, and routine support-fill recovery must not interrupt an already-running `GatherStoneGoal` episode.
- High item pickup should prefer a nearby pickup pillar route before accepting a vanilla path to the item column. This prevents dropped sticks/saplings above the NPC from reporting a reachable ground path while the NPC waits below the item.
- Pickup pillars can use planks, dirt-like blocks, or stone-like blocks and may use the current feet block as the pillar base when the NPC is already standing below the item.

## Stone Digging Rules

- `GatherStoneGoal.hasNearbyStoneTarget` must mean an actionable stone target with an adjacent stand position that is either reachable now or can be opened by `ClearBlockAi`. It should not return true for distant stone that would make navigation target the NPC's current feet.
- `GatherStoneGoal.isStoneSupplyPhaseActive(...)` is the shared phase predicate. It must require a carried pickaxe, met log supply (`!shouldPrioritizeLogGathering()`), a prepared saved base, and either unmet cobblestone supply or current build stone need. Do not duplicate extra ad hoc stone gates in dig-down, exploration, or gear-crafting callers; route those decisions through this predicate.
- `GatherStoneGoal` should continue mining reachable blocks from the current connected `StoneAi` cluster before rescanning globally, similar to log queue behavior from `TreeAi`.
- `GatherStoneGoal` must not mine by break-distance alone. The NPC must be at a same-Y horizontal stand cell adjacent to the target stone; otherwise it should keep routing to the stand, run `ClearBlockAi` against body/head/path blockers, or reselect.
- If movement to a stone stand fails, or if direct safe-drop movement is accepted but the NPC repeatedly does not arrive at the stand cell, `GatherStoneGoal` should run `ClearBlockAi` against local body/head/path blockers before reselecting.
- `DigDownForStoneGoal` should open a downward stair only until `GatherStoneGoal.hasNearbyStoneTarget(...)` becomes true. Once exposed stone is actionable, dig-down must stop without a gather cooldown so `GatherStoneGoal` can take over and use `ClearBlockAi`/`BreakingBlockAi` for remaining dirt around the stone stand.
- `DigDownForStoneGoal` should use exact `PathNavigationAi` routing for dig-site work cells after the initial surface approach, `BreakingBlockAi` for stair/dig-site mining, and `ClearBlockAi` only for body/head/path blockers toward the current dig cell. Trace detail should include `walking to dig site @ x y z` while routing to a dig cell.
- Dig-site route clearing should only clear physical collision blockers. It should ignore short grass, flowers, and other non-colliding plant clutter while walking to the dig site; actual stair digging can still mine physical dirt/grass blocks when they are the selected dig target.
- `GatherStoneGoal` searches a small vertical band below the NPC while near a dig pit so exposed stone below foot level can be claimed after dig-down opens it.
- During active stone gathering, `CraftBasicGearGoal` should only craft missing pickaxe/shovel essentials. Do not let stone-tool upgrades, swords, axes, or fishing rods preempt `GatherStoneGoal`; count cached `mainWeaponItem` and `offWeaponItem` tools when checking whether a tool exists or what tier is best.
- `CookFoodGoal` should defer during safe daytime while `GatherStoneGoal.isStoneSupplyPhaseActive(...)` is true. Furnace work can resume at night/thunder or after the primary stone phase is closed.
- Stone gathering and dig-down must skip the protected base support volume below the saved home footprint, not only blocks inside the visible home box.
- Stone target selection and stone path clearing are not allowed to mine the saved home/build footprint or the volume below it. `GatherStoneGoal.isInsideProtectedStoneTarget(...)` rejects visible home blocks, build-footprint X/Z cells, and below-home-footprint cells, and `startClearingRoute(...)` removes those candidates before `ClearBlockAi` starts.
- If the NPC is standing inside the home/build footprint while stone gathering is active, `GatherStoneGoal.handleHomeEgressBeforeStone(...)` must stop active stone breaking/clearing and route it to a standable ring outside the footprint first. If no outside egress route is found, the stone target is dropped instead of continuing `ClearBlockAi` from inside the house. This is the stone-gathering path; it must not carve a basement from inside the house.
- `GatherStoneGoal.markStoneAccessClearing(...)` temporarily marks stone access clearing while `ClearBlockAi` opens a stone stand. Terraform must treat this as non-actionable prep so it does not fill or replace blocks while the stone route is being opened. The running gather episode remains authoritative after that short marker expires; routine Terraform support-fill planning waits until `GatherStoneGoal.stop()` ends the episode.
- Once stone supply is closed while the NPC is below and near the saved base, return-home owns getting out of the dig site. Do not let log top-off exploration or missing-material exploration issue repeated `exploration climb request` details from that pit.
- `GatherLogsGoal`, `GatherStoneGoal`, and `DigDownForStoneGoal` should not continue normal resource work through night/thunder once a saved home exists. Log gathering may finish only its descent-from-pillar cleanup before yielding.

## Build Material Checklist Rules

- `GatherMissingBuildMaterialGoal` only runs after a saved home/layout exists, terraform prep is done, and both log and stone supply targets are already met.
- The missing-material checklist must classify wood/log/plank/wooden-family blocks as log supply work and cobblestone/stone-family blocks as stone supply work, so custom material gathering never competes with the primary resource goals.
- Glass and glass pane requirements gather sand or red sand only until enough sand/glass input exists; then return-home/cooking utilities should smelt and build/crafting utilities should make the final glass or pane.
- Plant and potted-plant requirements gather any nearby flower or pottable plant, not an exact color/species, because blueprint material matching accepts plant-family substitutes.
- Bed requirements can be satisfied by breaking a loose nearby bed outside the protected home or by hunting sheep while the bed/carpet requirement remains missing. Once a bed is placed or craftable, this custom gather path should stop.
- Generic non-primary requirements may scan for matching world blocks, but must still skip the protected home and must not claim log/stone-family materials.

## Home Return And Shelter Rules

- `ReturnHomeGoal` is the long-range night/thunder shelter handoff. `BeingAtHomeGoal` is short-range indoor behavior and should not be expected to solve far-away return by itself.
- A Building-interest NPC with neither a persisted home/build area nor a selected layout uses `MiningNightCampGoal`'s bounded local walk/look/sneak activity at night instead of standing idle. The camp center is fixed where the goal starts, movement only chooses exact reachable stand positions within five blocks, and this bootstrap mode performs no furnace, torch, or other camp block mutation. It ends as soon as daytime begins or a home/layout selection appears so `BuildHouseGoal` can resume.
- Night/thunder shelter return must bypass the material-reserve gate that normally prevents build returns while logs or stone are still below target.
- Non-weather home utility return should not starve the primary resource pass. If logs or stone are still below target, exploration suppression should only apply for night/thunder, not for missing torch charcoal, smelting stone, or other home utility work.
- The completed-stone-trip return is a narrow exception to the primary resource pass rule: if stone is satisfied and the NPC is below the saved base, `ReturnHomeGoal` suppresses log exploration/gathering until the NPC reaches the home work area. This prevents pathfinding/TPS loops from trying to explore outward from a mine pit.
- `ReturnHomeGoal` protects blocks inside the saved home area while using `ReturnPositionAi` to clear blockers on the route.
- Under-home return is a separate recovery path from stone gathering. If the NPC is below the saved home footprint, or is at the home-origin Y inside the work margin while the local terrain surface is still above it and sky is blocked, it is treated as outside the house until it reaches the home surface. This equality case catches a builder that returned through a nearby underground tunnel without misclassifying an ordinary open-sky stand at the build origin. `ReturnHomeGoal` starts `homeSurfaceRecoveryReturn`, selects a standable open-sky surface target outside the exact blueprint footprint when possible, requests a forced upward escape, and lets `ReturnPositionAi`/`EscapeHoleWithBlockGoal` clear and pillar out. `BuildHouseGoal` must yield while this recovery predicate is true instead of cycling unreachable placements.
- During `homeSurfaceRecoveryReturn`, return-home may clear/break home floor or base blocks directly above an NPC trapped at/below the saved origin Y under the exact footprint so it can pillar out. `EscapeHoleWithBlockGoal` limits this exception to a forced upward request whose NPC and target are both inside the build footprint and only clears the NPC's current vertical column. Owned farm cells, temporary crafting tables, block entities, fluids, and unbreakable blocks remain protected, and terraform/build repair the cleared blueprint block later.
- Missing non-primary build-material gathering protects the complete horizontal build footprint at every Y plus the support block directly beneath the worker. It therefore cannot harvest the house foundation/terrain merely because that block lies below the Y-bounded home area. Its direct break reach is 1.8 blocks: the builder must approach a cardinal stand beside one outside-footprint source, break that one source, and then yield to the higher-priority pickup goal instead of centrally excavating several blocks and falling into the resulting pit.
- A forced home-surface pillar whose path ends one cell beside its selected base gets a one-second no-progress watcher. It replans from the current valid column or another adjacent base and emits `waiting for pillar base route`/`using current pillar base`/`shifting pillar base` detail instead of remaining indefinitely in `pillaring_up` with `detail=none`. If an overhead footprint block is clearable, alternate open pillar columns are tried before the narrowly permitted current-column house-block clearance.
- Builder home return uses the historical `ad93109`/`731df2d` recovery order: route/clear, bounded direct pillar, then forced upward escape after four failed routes or an already-detected vertical trap. `ReturnHomeGoal` enables this mode only while BUILDING is the active daily job; mixed-interest workers on farming or another job use normal return recovery. Other `ReturnPositionAi` users also retain modern relocation/path-stuck recovery. Return clearing must always protect the current support block and every remembered temporary pillar support, including dynamically retargeted clear blocks, so the restored direct-pillar fallback cannot break its own pillar. Terraform must not run while `getUpwardEscapeTarget()` is set or while the current state is `ai.player_npc.pillaring_up`, so fill-support logic cannot immediately replace the just-cleared escape block before the NPC climbs.
- If direct navigation to the saved home center returns `path=none`, `ReturnPositionAi` should use `PathNavigationAi.moveToWithLocalFallback(...)`; trace detail should show `local route @ x y z` when a waypoint is selected.
- If return still cannot route, trace detail should include `attempts`, `directPath`, `localCandidates`, `localChecks`, `localRoute`, `clear`, and `pillar` fields. Those fields are emitted from `ReturnPositionAi` and `PathNavigationAi` to show whether direct pathing, local waypoint search, obstruction clearing, or pillar fallback failed.
- `BeingAtHomeGoal` opens nearby home doors, prefers indoor stands beside bed -> furnace/smoker/blast furnace -> crafting table, then falls back to ordinary interior stand positions. It may start and continue only inside ReturnHome's margin-four home work area and when home-surface recovery is not needed. For unfinished homes with no valid indoor/utility stand yet, sheltering home behavior may fall back only to safe stand positions inside that same bounded work area; it must not accept the NPC's far-away loaded position as home.
- If the NPC has material and actionable or pending-reserved terraform/build work after returning near the base, `BeingAtHomeGoal` must yield during both day and shelter time. If the house is finished or construction cannot actually run because its supply/material requirement is missing, `BeingAtHomeGoal` can take over around/inside the home area.

## Trace Strings

Useful inspector/latest.log detail strings:

- `ai.player_npc.gathering_logs`
- `ai.player_npc.gathering_stone`
- `ai.player_npc.gathering_build_material`
- `ai.player_npc.returning_home`
- `ai.player_npc.being_at_home`
- `ai.player_npc.building_night_camp`
- `returning from dig site`
- `searching for logs`
- `clearing leaves @`
- `mining log @`
- `mining dirt for pillar @`
- `searching dirt for pillar`
- `pillaring up @`
- `pillar up @`
- `pillar blocked for clear @`
- `pillaring to`
- `searching for stone`
- `mining stone @`
- `digging dirt for stone search @`
- `walking to dig site @`
- `clearing dig path @`
- `clearing stone path @`
- `returning to home shelter`
- `returning to surface`
- `local route @`
- `searching route; attempts=`
- `clearing return path @`
- `clearing return pillar space @`
- `leaving home before stone @`
- `walking to home utility`
- `sneaking inside home`

## Rules

- Keep `entity.ai` flat; helper class names should end with `Ai`.
- Do not add goal-specific helpers or temporary target state to `PlayerNpcEntity`.
- Reusable mechanics such as breaking, sneaking, tool switching, clear-block, resource counts, and pillaring belong in `entity.ai`.
- Goal-local decisions such as which resource to target, when to switch from logs to dirt, and how to pick a stand position belong in the goal class.
