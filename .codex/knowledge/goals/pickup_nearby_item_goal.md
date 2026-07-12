# PickupNearbyItemGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/PickupNearbyItemGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Makes idle Player NPCs actively walk to nearby useful dropped items instead of relying only on the small passive collision pickup radius. This goal must stay opportunistic; it should not block log/material work for distant or delayed drops.

## Activation

- Server side only.
- NPC must be alive, not healing, not riding, not no-AI, and not fighting a live target.
- Searches an 8 block horizontal radius and 5 block vertical radius for ordinary nearby drops.
- While post-animal-kill loot priority is active, searches a wider local area around the animal death position.
- Skips ordinary pickup while `PlayerNpcEntity.shouldPrioritizeLogGathering()` is true, unless post-animal-kill loot priority is active.
- Only targets `InventoryUtils.isInventoryBackedSupplyDrop(...)` items that can fit in the custom inventory.
- Starts only when an actual reachable dropped item entity exists. The priority death position is a search bias, not a standalone wait task.

## Behavior

The goal selects the nearest reachable useful item, sets AI state `ai.player_npc.collecting_item`, pathfinds to the item, looks at it, clears that selected item's pickup delay, and calls `PlayerNpcEntity.tryPickupItemEntity(...)`. It must not sit still waiting for the item to enter inventory.

It runs above worker/resource goals and above `BurnNearbyItemGoal`, so animal drops and other useful supplies are collected before the NPC starts another idle job or burns unwanted items.

When `PlayerNpcEntity` kills an animal or sees its current animal target die, it stores the death position for a short post-kill loot priority window, clears the animal target, and wakes idle work cooldowns. During that window `PickupNearbyItemGoal` uses the death position to widen and bias item search, then paths directly to the selected item. If no item entity exists, the goal does not start and clears the stale priority instead of walking to the old death position and waiting there.

The pickup stop radius is intentionally tight enough that the NPC moves into collision range instead of standing near a dropped item and hoping it enters inventory. If the item is nearby but pickup still fails, the goal runs an active close-range approach: it keeps moving toward the item, applies a small direct push toward it, and jumps toward it when the item is above, on a ledge, or pathing reports done/stuck. After a short failed close-range approach it tries to mine a local obstruction on the item path.

`PlayerNpcSmartTargetGoal` and `HuntSheepForBedGoal` yield new animal/sheep targets while collectable supply drops are nearby and can fit in inventory. This prevents animal hunting from immediately retargeting another animal before the previous loot is picked up. Monster/player targeting is not blocked by this rule.

When direct item-entity pathing fails, `PickupNearbyItemGoal` tries reachable stand positions around the dropped item before giving up. Path checks require `Path.canReach()` so partial paths do not lock the goal forever. If a nearby physical collision block is blocking the item/stand path, pickup keeps the original item target, temporarily mines that local obstruction, shows `clearing pickup path ...` in inspector detail, then resumes pathing to the item. Non-colliding plants and replaceable clutter are ignored for pickup path clearing. Obstruction clearing is short-capped and skips protected home blocks, fluids, unbreakable blocks, and block entities so pickup remains opportunistic and cannot become a terrain-digging task.

When pickup temporarily changes the main hand for path clearing, a required tool stored in `previousMainHand` counts as available and is restored as the real main-hand item before reuse. The temporary stash is cleared at that point so later durability damage is not overwritten by an older saved copy.

`BurnNearbyItemGoal` also reserves inventory-backed supply drops from burning when they cannot be picked up, so meat and animal drops are not disposed of just because the NPC has a burn tool.

## Useful Drop Categories

`InventoryUtils.isInventoryBackedSupplyDrop(...)` includes combat supplies, food, placeable blocks, utility materials, saplings, records, throwable potions, and animal loot such as wool, leather, feathers, eggs, rabbit drops, string, bones, ink sacs, phantom membrane, slime balls, scutes, goat horns, and honeycomb.
