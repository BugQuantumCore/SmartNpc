# LootNearbyChestGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/LootNearbyChestGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs loot world chests.

## Behavior

The goal searches nearby chests outside the NPC's saved home area and skips the NPC's recorded owned chest. It chooses a valid adjacent standing position, walks next to the chest, looks at it, opens the chest lid with the vanilla block event, and transfers acceptable item stacks sequentially with a short delay between takes until the chest has no acceptable items or the NPC inventory cannot accept more.

Loot is no longer filtered to a useful-item list. Any chest item can be moved if the NPC inventory has an empty slot or a compatible partial stack.

Home chests are skipped so the NPC does not immediately steal back stored items. `PlayerNpcEntity.ownedChestPos` records the specific chest the NPC placed or adopted for home storage.

The NPC must be standing beside the chest to interact, so it should not loot through walls. When looting finishes or the goal stops, the goal fires the vanilla chest close block event and plays `CHEST_CLOSE`.

After moving gear into inventory, the goal asks `PlayerNpcEntity.equipBetterGearFromInventory()` to equip better weapons/tools or armor.

Cooldown uses `PlayerNpcEntity.lootChestCooldown`.

## Performance Contract

LOOTING is an opportunistic characteristic and remains registered outside the routine-worker wrapper. Its 21x7x21 search volume therefore must never be traversed in one selector activation: the search retains its origin/cursor/best candidate and reads at most 128 loaded positions per pass, retrying partial passes with a short jitter. The origin is kept through ordinary walking and resets only after the NPC moves more than four blocks away, so sliced searches still finish while the NPC strolls. Unloaded positions are skipped rather than synchronously loading chunks.

Initial movement and 20-tick repaths use `PathNavigationAi.createBoundedPath(..., 0.15F)`. This characteristic stays available without a routine job resource, while each synchronous path has a deterministic visited-node ceiling.
