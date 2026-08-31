# CheckHomeSuppliesGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/CheckHomeSuppliesGoal.java`
- `src/main/java/com/pla/smart_npc/util/PlayerNpcBuildMaterialUtil.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs check their own home chest and furnace once per Minecraft day before falling back to gathering/mining/exploring.

## Behavior

The goal is baseline AI and only runs when the NPC has a saved home/base, is near it, has no combat target, and needs supplies such as food, wood, fuel, arrows, building blocks, fishing bootstrap supplies, or has furnace output waiting.

It checks the home chest first, then the home furnace. Each check is marked in persistent entity data by Minecraft day:

- `PlayerNpcLastHomeChestCheckDay`
- `PlayerNpcLastHomeFurnaceCheckDay`

These are daily check markers, not tick cooldowns. They reset naturally when `serverLevel.getDayTime() / 24000L` changes.

The cheap due check runs before supply discovery. Building-material demand uses `PlayerNpcBuildMaterialUtil.findMissingBuildMaterialNeed`, which resumes across at most eight blueprint blocks per admitted slice and publishes whether more slices are pending. A pending result defers the entire home-supply decision even when food/tool/fuel also happens to be needed; the goal must not mark a daily chest/furnace check complete before the building-material pass has resolved. Chest item matching then compares only against that resolved missing target/material family rather than running a full blueprint scan once per chest slot.

Chest behavior:

- walks to a usable adjacent stand position,
- opens/closes the chest,
- withdraws limited useful stacks such as food, logs/planks/sticks, coal/charcoal/fuel, arrows, building blocks, torches, utility blocks, buckets, and ore/ingot supplies,
- for a FISHING profile, recognizes a missing fishing rod and independently restores a carried string reserve below two; this is an urgent tool-supply retry and does not grant fishing supplies to unrelated profiles,
- marks the chest checked even if it is missing or unreachable so resource AI does not loop on the same empty check.

Furnace behavior:

- walks to a usable adjacent stand position,
- takes output from the home furnace if present,
- marks the furnace checked even if it is missing, empty, or unreachable.

## Performance Boundary

- Chest and furnace discovery/planning are separate selector passes; one activation never compounds both storage path batches.
- Stand discovery retains its target/cursor across retries, checks free geometry candidates first, and creates at most one path in an admitted pass. A successful selection path is reused by movement.
- Every storage selection or recovery path uses a scoped `0.05F` visited-node multiplier. A denied shared expensive-work slice is PENDING, not an unreachable stand and not permission to mark the daily check complete.
- Retried stand selection reuses its already validated chest/furnace target rather than rescanning the home layout or volume on each path candidate.
- The urgent missing-tool retry, including a FISHING rod/string shortage, may use the exact tracked owned chest after the daily discovery/migration check has already completed. It does not repeat the full blueprint/home chest search every ten seconds.
- Candidate block/stand reads require loaded chunks. CheckHome observes the loaded home; it must not load or generate a chunk for a storage probe.

The goal does not replace `CookFoodGoal`; cooking still owns loading furnace input/fuel and temporary furnace placement/recovery.
