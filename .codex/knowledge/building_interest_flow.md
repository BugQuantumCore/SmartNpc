# Building Interest Flow

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
2. Logs are gathered first while `ResourceAi.countLogs(playerNpc) < playerNpc.getLogSupplyGoal()`.
3. `GatherLogsGoal` uses `TreeAi` to find a connected log cluster, mines nearest logs first, clears covering leaves with `ClearBlockAi`, and uses `PillarUpAi` with dirt when upper logs need one-block-at-a-time pillaring.
4. If log gathering needs dirt for pillaring, it temporarily targets nearby dirt or grass, mines it, then returns to the tree queue.
5. Once logs are satisfied, `BuildHouseGoal` may select and save a base/layout using wood-only bootstrap reserves. Actual block placement is still blocked until stone supply is met.
6. After the base is saved, `CraftBasicGearGoal` should craft a wooden shovel when `TerraformBuildSiteGoal.needsShovelForPrep(...)` detects shovel-clearing work. If the NPC is already adjacent to the home crafting table, crafting should proceed even when vanilla pathing to the exact stand cell returns `path=none`.
7. `TerraformBuildSiteGoal` prepares the saved base footprint before stone gathering starts. It yields to gear crafting when the next clear target needs a shovel and no shovel is available.
8. Stone gathering starts only after a saved base exists and no actionable terraform work remains. `GatherStoneGoal` uses `StoneAi` to queue connected stone clusters, mines exposed nearby stone outside the protected base/support volume, and only starts breaking after the NPC is standing in a reachable adjacent stand position. If none is actionable, `DigDownForStoneGoal` chooses a random dig site near but outside the base and digs a stair downward for stone.
9. `BuildHouseGoal` and build-return readiness require both daily supply goals to be met before actual blueprint placement. This applies after a base/layout exists; it must not block the first no-home bootstrap path that only needs logs to choose and save a base.
10. If the blueprint is missing non-primary material after log and stone supplies are met, `GatherMissingBuildMaterialGoal` runs before more build placement. It ignores log/stone-family requirements, gathers sand for glass/glass panes, gathers any nearby plant for plant-like blocks, gathers a loose bed or hunts sheep while a bed/carpet requirement is still missing, and otherwise only gathers world blocks that match the missing blueprint block/family. Existing furnace/crafting utilities handle smelting sand into glass and crafting panes/beds afterward.
11. When the material checklist is satisfied, return/build behavior can route the NPC back to the known base and `BuildHouseGoal` places the blueprint.
12. At night or during thunder, an NPC with a home/base should yield gathering to `ReturnHomeGoal`. `ReturnHomeGoal` uses `ReturnPositionAi` to route back toward the saved base while allowing safe lower steps, local obstruction clearing, and upward escape requests when stuck underground. Once near the base, ready terraform/build work may continue; otherwise `BeingAtHomeGoal` handles indoor standing, walking, looking, utility spots, doors, and sneaking.

## Reusable AI Helpers

- `ResourceAi`: material counts and resource classification.
- `TreeAi`: connected tree/log detection and nearest-first ordering.
- `ToolAi`: main-hand tool or item swapping and restoration.
- `StoneAi`: connected stone-cluster detection and nearest-first ordering for stone gathering.
- `BreakingBlockAi`: shared block-breaking loop for logs, dirt, stone, and obstruction clearing. It owns break progress, hit sounds, visible main-hand attack animation, tool selection, held-tool durability damage, and random mining sneak.
- `ClearBlockAi`: reusable obstruction clearing, currently used for leaves covering log targets or pillar routes. It delegates mining to `BreakingBlockAi`.
- `PillarUpAi`: one-block pillar placement using a configured block item/state.
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

## Stone Digging Rules

- `GatherStoneGoal.hasNearbyStoneTarget` must mean an actionable stone target with a reachable adjacent stand position. It should not return true for buried or distant stone that would make navigation target the NPC's current feet.
- `GatherStoneGoal` should continue mining reachable blocks from the current connected `StoneAi` cluster before rescanning globally, similar to log queue behavior from `TreeAi`.
- `GatherStoneGoal` must not mine by break-distance alone. The NPC must be at a same-Y horizontal stand cell adjacent to the target stone; otherwise it should keep routing to the stand, run `ClearBlockAi`, or reselect.
- If movement to a stone stand fails, or if direct safe-drop movement is accepted but the NPC repeatedly does not arrive at the stand cell, `GatherStoneGoal` should run `ClearBlockAi` against local body/head/path blockers before reselecting.
- `DigDownForStoneGoal` should use `PathNavigationAi` for lower stair movement and `ClearBlockAi` for one-block-high or obstructed dig entries. Trace detail should include `walking to dig site @ x y z` while routing to a dig cell.
- Stone gathering and dig-down must skip the protected base support volume below the saved home footprint, not only blocks inside the visible home box.
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
- Night/thunder shelter return must bypass the material-reserve gate that normally prevents build returns while logs or stone are still below target.
- `ReturnHomeGoal` protects blocks inside the saved home area while using `ReturnPositionAi` to clear blockers on the route.
- If direct navigation to the saved home center returns `path=none`, `ReturnPositionAi` should use `PathNavigationAi.moveToWithLocalFallback(...)`; trace detail should show `local route @ x y z` when a waypoint is selected.
- If return still cannot route, trace detail should include `attempts`, `directPath`, `localCandidates`, `localChecks`, `localRoute`, `clear`, and `pillar` fields. Those fields are emitted from `ReturnPositionAi` and `PathNavigationAi` to show whether direct pathing, local waypoint search, obstruction clearing, or pillar fallback failed.
- `BeingAtHomeGoal` opens nearby home doors, prefers indoor stands beside bed -> furnace/smoker/blast furnace -> crafting table, then falls back to ordinary interior stand positions. For unfinished homes with no valid indoor/utility stand yet, sheltering home behavior should fall back to a safe stand around the saved base area.
- If the NPC has material and actionable terraform/build work after returning near the base, the higher-priority build goals can continue. If material is missing, `BeingAtHomeGoal` can take over around/inside the home area.

## Trace Strings

Useful inspector/latest.log detail strings:

- `ai.player_npc.gathering_logs`
- `ai.player_npc.gathering_stone`
- `ai.player_npc.gathering_build_material`
- `ai.player_npc.returning_home`
- `ai.player_npc.being_at_home`
- `searching for logs`
- `clearing leaves @`
- `mining log @`
- `mining dirt for pillar @`
- `searching dirt for pillar`
- `pillaring up @`
- `searching for stone`
- `mining stone @`
- `digging dirt for stone search @`
- `walking to dig site @`
- `clearing dig path @`
- `clearing stone path @`
- `returning to home shelter`
- `local route @`
- `searching route; attempts=`
- `clearing return path @`
- `walking to home utility`
- `sneaking inside home`

## Rules

- Keep `entity.ai` flat; helper class names should end with `Ai`.
- Do not add goal-specific helpers or temporary target state to `PlayerNpcEntity`.
- Reusable mechanics such as breaking, sneaking, tool switching, clear-block, resource counts, and pillaring belong in `entity.ai`.
- Goal-local decisions such as which resource to target, when to switch from logs to dirt, and how to pick a stand position belong in the goal class.
