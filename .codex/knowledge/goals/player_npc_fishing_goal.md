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
- Log supply and cobblestone supply must both be satisfied: `!shouldPrioritizeLogGathering()` and `!shouldPrioritizeCobblestoneGathering()`.
- Requires a usable fishing rod in the main hand or inventory.
- Requires a reachable local fishing spot found by the bounded water/shore scan.

## Support Flow

Fishing does not skip support supplies just because the NPC has a rod. The intended order is:

1. Satisfy log supply through `GatherLogsGoal`.
2. Satisfy stone supply through `GatherStoneGoal` or `DigDownForStoneGoal`.
3. Craft or use an existing fishing rod.
4. Search for reachable water and fish.

`CraftBasicGearGoal` may craft a missing fishing rod after critical starter tools are handled and after the active stone-support phase has closed. Do not treat rod crafting as the blocker when the NPC already carries or holds a usable rod; then the remaining blockers are support supplies, water search, navigation, or climb/escape handling.

## Water Search

`findFishingSpot(...)` scans nearby surface source water within a bounded radius. A water block must have open collision above, sky visibility, and enough nearby source water for fishing. A stand position must be standable, sky-visible, within cast distance, have a clear cast ray to the water, and either already be close enough or have a completed reachable path.

Water exploration is handled by the separate `ExploreAroundGoal` registration with detail `exploring for water`. That exploration starts only when `shouldExploreForFishingWater(...)` is true, so it still waits for met log supply, met cobblestone supply, and a rod. The fishing water exploration registration allows bounded upward escape and currently caps the request at 18 pillar blocks. Those exploration climb requests should only be satisfied after `EscapeHoleWithBlockGoal` reaches open-sky body space and the NPC is no longer trapped; being one block from the requested target while still under leaves is not enough.

## Behavior

The goal temporarily equips a fishing rod from inventory if the main hand is not already a rod, using `PlayerNpcEntity.setMainHandItemForAi(...)`. It moves to the chosen stand with detail `walking to water @ x y z`, faces the water, casts a `PlayerNpcFishingBobberEntity` with detail `casting @ x y z`, then tracks the bobber with `hook @ x y z`.

Continuation requires the active main-hand rod. If another behavior replaces the rod, the fishing goal ends instead of retrieving with the wrong item.

When the bobber is ready, detail changes to `bite @ x y z`; after a short reaction delay, the goal retrieves. The custom bobber owns loot generation through vanilla `BuiltInLootTables.FISHING`, inserts loot into the NPC inventory, and drops overflow. The returned rod-damage value is applied to the active main-hand rod through `hurtMainHandItem(...)`.

Stop discards any remaining bobber, restores the previous main-hand item after stashing or dropping the temporary rod, and sets `fishingCooldown`.

## Trace Strings

- `ai.player_npc.fishing`
- `walking to water @`
- `casting @`
- `hook @`
- `bite @`
- `exploring for water`
