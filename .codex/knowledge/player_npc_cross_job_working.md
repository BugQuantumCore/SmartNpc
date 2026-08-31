# Player NPC Cross-Job Working

## Ownership

- `PlayerNpcEntity` owns the daily job scheduler, persisted selected job/day, and the two pre-roll locks. It must not grow job-specific base state machines.
- `PlayerNpcHomeUtil` remains authoritative for builder home geometry. `PlayerNpcFarmPlan`/`FarmAi` remain authoritative for farm geometry. `PlayerNpcBaseUtil` alone owns the dimension-aware permanent camp anchor for non-builder/non-farmer Mining or Fishing NPCs.
- `MiningNightCampGoal` resolves the night destination and establishes a permanent camp with `PlayerNpcBaseUtil.setCampBaseIfAbsent(...)`. `ManageHomeBaseGoal` owns chest placement/deposit work; `CheckHomeSuppliesGoal` owns chest withdrawals.
- Call these public owners instead of copying NBT keys, base priority, or footprint geometry into another goal.

## Scheduler Locks And Priority

The daily scheduler rolls one available job per Minecraft day after base selection is ready. Before that roll:

1. An NPC with Building stays locked to Building until a home layout/area is selected.
2. Only when Building is absent, an NPC with Farming stays locked to Farming until a farm plan/area is selected.
3. Therefore Building always wins when Building and Farming are both present.

After selection, the rolled job gates ordinary work. Building is additionally active as a night/thunder home duty once its home exists, even if another job was rolled. Do not restore the old rule that forced Building until the entire structure was finished; the lock concerns selecting the owned area, not completing every build block.

## Base And Night-Return Priority

Base resolution is persistent and independent of the rolled daytime job:

1. Builder home (`PlayerNpcHomeUtil`) is authoritative whenever Building is present.
2. Otherwise, a Farming NPC uses the saved farm gate/area (`FarmAi`).
3. Otherwise, an NPC with Mining or Fishing uses the first permanent night camp stored by `PlayerNpcBaseUtil`.

This means a Builder+Farmer that rolled Farming returns to the house at night. A Farmer without Building returns to the farm even after Mining/Fishing work. A Miner/Fisher keeps the first camp when a later day rolls the other job. A mixed Explorer plus any of those base-owning jobs follows the same base rule.

The camp record contains position and dimension. `setCampBaseIfAbsent(...)` must stay write-once: never silently move a permanent camp on a later night or job roll.

## Farm/Home Separation

Farm selection must reject every farm ground, fence, and gate position that overlaps the builder home footprint. `FarmAi.isPlanGeometryValid(...)` is the shared invariant and must be used by creation/migration/validation. Farm, chest, furnace, material gathering, and obstruction-clearing code must continue querying the shared home/farm protection helpers before changing blocks.

## Chests, Deposit, And Supply

- Builder homes retain the legacy home chest behavior.
- Non-builder Farming bases and permanent Mining/Fishing camps place one explicitly owned chest near their base anchor. Placement rejects builder footprints and protected farm work/entrance blocks and requires an adjacent stand.
- Non-builder chest placement is an incremental base duty: keep its candidate cursor between attempts, share one small navigation-path budget across every adjacent-stand check in a pass, inspect loaded candidates only, and use a long randomized negative-result backoff. A bounded miss must not change chest ownership or base priority.
- `PlayerNpcEntity.ownedChestPos` is the identity for these chests. `ChestAi.findOwnedSupplyChest(...)` validates that exact block; generic nearby-chest searches must not adopt another NPC's chest.
- `CHEST_PROTECT` is an implicit universal characteristic added when every NPC name/profile derives its interests, so old username-only saves gain it without an NBT migration and the inspector lists it automatically. Server Forge interaction/break hooks and NPC chest-open/block-break paths report disturbances of the exact `ownedChestPos`. Loaded owners within a bounded 64-block horizontal/32-block vertical area target the offender; the owner itself is excluded, and each owner is evaluated independently.
- `ManageHomeBaseGoal` may place the chest and deposit inventory at any resolved base. `CheckHomeSuppliesGoal` may withdraw from that owned chest. Non-builder bases do not receive the builder-only bed, crafting-table, or home-furnace placement flow.
- LOOTING behavior must keep excluding the NPC's own tracked chest while remaining able to loot eligible chests owned by other NPCs.

## Explorer Special Cases

- Explorer-only NPCs are intentionally free: no managed base, no base chest, and ordinary exploration behavior continues.
- Explorer mixed with Building/Farming/Mining/Fishing is not explorer-only. Exploration remains a lower-priority roaming/stroll job and night return, chest deposit, and supply checks come from the other job's authoritative base.
- Do not turn Exploring into a second scheduler or base owner.

## Incremental Base Selection

Farm-area selection may span multiple admitted, bounded passes. `FarmAi.isPlanSearchPending(...)` distinguishes that PENDING state from a fully exhausted local search. PENDING keeps the Farming pre-roll lock and suppresses farm-area exploration; exhaustion keeps the lock but permits roaming until movement establishes a new search center. Never interpret a budget/admission deferral as permission to roll another job or fall back to a lower-priority base.

## Cross-Job Performance Boundary

Multiple interests do not grant an NPC concurrent copies of every job's expensive discovery work. The daily job/base rules above decide semantic eligibility; `PlayerNpcAiWorkBudget` independently admits bounded world-search and path batches across all NPCs. Admission deferral must preserve the current scheduler/base lock and retry with a short randomized phase. Force-ticked multi-job NPCs still use exactly one moving center ticket anchor each, not one force area per interest and not a 3x3 grid of independent anchors.

## Change Checklist

When changing cross-job behavior, verify scheduler lock release, daily reroll persistence, base priority at night, camp write-once persistence and dimension checks, farm/home non-overlap, owned-chest identity, and explorer-only freedom together. Update this note whenever one of those contracts changes.
