# PlayerNpcFishingGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/PlayerNpcFishingGoal.java`
- `src/main/java/com/pla/smart_npc/entity/PlayerNpcFishingBobberEntity.java`
- Registered from `PlayerNpcEntity.registerGoals()` for `PlayerNpcInterest.FISHING`.

## Purpose

Runs the non-combat fishing job after the fisher has completed its support supply chain.

## Activation

- Server side only.
- NPC must be alive, not healing, not a passenger, not no-AI, and have no combat target.
- `PlayerNpcInterest.FISHING` must be the selected daily job.
- `PlayerNpcEntity.fishingCooldown` must be `0`.
- No upward escape target can already be active.
- Requires a usable fishing rod in the main hand or inventory.
- With a usable carried rod, an active/retry-ready log or stone support attempt may run first, but a failed support search opens the shared gather-cooldown window. During that window the raw reserve does not block direct fishing or fishing-water exploration.
- Requires a reachable local fishing spot found by the bounded water/shore scan.

## Support Flow

Fishing uses support supplies to bootstrap a missing rod and its stone support. The intended order is:

1. If the rod is missing/broken and its recipe needs wood, satisfy that demand through `GatherLogsGoal`.
2. Satisfy stone support through `GatherStoneGoal` or `DigDownForStoneGoal`.
3. Craft the missing rod or use an existing carried rod.
4. Search for reachable water and fish.

Fishing profiles receive 3-7 starter string on a normal new spawn. A persisted, versioned migration repairs legacy fishers whose carried string reserve is below the two required by the rod recipe; it adds only the missing amount and never supplies unrelated NPCs. This also leaves a fisher that currently has a rod with one replacement recipe rather than waiting for the rod to break before discovering that its old starter supply was lost. The compatibility repair is independent of the routine worker scheduler and does not gate or replace the fishing job.

Home storage must not strand that bootstrap. `ManageHomeBaseGoal` keeps fishing rods and string carried by a FISHING profile rather than depositing them during half-full inventory cleanup. `CheckHomeSuppliesGoal` also treats a missing rod or a carried string reserve below two as urgent supply needs, so rods/string deposited by an older build can be recovered from the NPC's owned chest on the short missing-tool recheck cadence.

Fishing arbitration uses `GatherLogsGoal.isLogGatheringEpisodeActive(...)` plus the shared gather cooldown. An already selected log route keeps control until it finishes, and a retry-ready supply demand gets one attempt. If the attempt finds no actionable target and sets `gatherCooldown`, a carried rod can fish or explore for water instead of idling until that reserve is met. A genuinely missing/broken rod still blocks fishing and permits log gathering when rod crafting needs wood.

`CraftBasicGearGoal` may craft a missing fishing rod after critical starter tools are handled and after the active stone-support phase has closed. Do not treat rod crafting as the blocker when the NPC already carries or holds a usable rod; then the remaining blockers are support supplies, water search, navigation, or climb/escape handling.

## Water Search

`findFishingSpot(...)` scans nearby surface source water within a bounded radius. A water block must have open collision above, sky visibility, and enough nearby source water for fishing. A stand position must be standable, sky-visible, within cast distance, have a clear cast ray to the water, and either already be close enough or have a completed reachable path.

Water exploration is handled by the separate `ExploreAroundGoal` registration with detail `exploring for water`. That exploration starts only when `shouldExploreForFishingWater(...)` is true, so it still waits for met log supply, met cobblestone supply, and a rod. The fishing water exploration registration allows bounded upward escape and currently caps the request at 10 pillar blocks. Those exploration climb requests should only be satisfied after `EscapeHoleWithBlockGoal` reaches open-sky body space and the NPC is no longer trapped; being one block from the requested target while still under leaves is not enough.

The missing-string `strolling around` registration is ordinary random walking, not water-surface recovery, and must keep `allowUpwardEscapeRequest=false`. If that stroll's current or one-second remembered navigation corridor is stopped by a physical foliage collision, `ExploreAroundGoal` may clear only the confirmed leaf/vine blocker through shared `ClearBlockAi`/`BreakingBlockAi`, then repath to the same stroll target. It does not guess nearby blocks or clear logs, and every active/retargeted blocker must stay outside saved home/build areas, owned farm protection, temporary crafting tables, and temporary pillar supports while remaining fluid-free, block-entity-free, breakable foliage. The pass is bounded to four clear starts per exploration run.

## Behavior

The goal temporarily equips a fishing rod from inventory if the main hand is not already a rod, using `PlayerNpcEntity.setMainHandItemForAi(...)`. It moves to the chosen stand with detail `walking to water @ x y z`, faces the water, casts a `PlayerNpcFishingBobberEntity` with detail `casting @ x y z`, then tracks the bobber with `hook @ x y z`.

Continuation requires the active main-hand rod. If another behavior replaces the rod, the fishing goal ends instead of retrieving with the wrong item.

When the bobber is ready, detail changes to `bite @ x y z`; after a short reaction delay, the goal retrieves. The custom bobber owns loot generation through vanilla `BuiltInLootTables.FISHING` and spawns each result with vanilla-style motion toward the NPC, where normal contact pickup handles inventory insertion and overflow. The returned rod-damage value is applied to the active main-hand rod through `hurtMainHandItem(...)`.

Stop discards any remaining bobber, restores the previous main-hand item after stashing or dropping the temporary rod, and sets `fishingCooldown`.

Full-inventory maintenance runs above fishing/crafting at priority three. A completely full inventory
checks for safe junk once per second instead of waiting for the ordinary five-second cleanup cadence;
cleanup retains and revalidates one exact slot, then throws only that stack so one usable slot is
freed. Rotten flesh, bowls, lily pads, tripwire hooks, ordinary flowers/decorative plants, and
strictly inferior unenchanted vanilla tools are eligible. Food, rare flowers, named/enchanted items,
saplings, fishing string, materials, and useful gear remain protected. Uneven ground or foliage cannot block cleanup:
the goal may use a short loaded, collision-free throw point even when no sturdy landing cell exists,
and it clears the inventory slot only after the item entity is accepted by the world.

Crafting capacity is transactional. Log-to-plank conversion and recipe assembly first simulate input
consumption, recipe remainders, and output insertion in a copied 27-slot inventory. The real inventory
is committed only when everything fits, so a full inventory can use an input slot that becomes empty
without losing a log, planks, recipe inputs, containers, or the crafted fishing rod on failure.

## Trace Strings

- `ai.player_npc.fishing`
- `walking to water @`
- `casting @`
- `hook @`
- `bite @`
- `exploring for water`
- `fishing blocked: no usable carried rod`
- `fishing blocked: log support=`
- `fishing blocked: stone support=`
- `fishing blocked: no reachable surface water`
