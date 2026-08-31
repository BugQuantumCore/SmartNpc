# ManageHomeBaseGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/ManageHomeBaseGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs maintain a small home area instead of only wandering, gathering, or fighting.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Cooldown uses `PlayerNpcEntity.manageHomeCooldown`.
- Uses or creates the NPC's saved `PlayerNpcHomeUtil` home area.
- Home utility placement/storage only runs when the NPC is physically near that saved home area; temporary crafting-table recovery can still run near the temporary table.

## Behavior

Places a crafting table in the home area when needed. It consumes a carried crafting table first; otherwise it uses the correct recipe path where logs convert to planks and four planks are consumed.

If `CraftBasicGearGoal` had placed a temporary crafting table outside the saved home area and the NPC is still nearby, this goal walks to that table, temporarily equips an axe when available or clears the hand when not, breaks it over time, shows main-hand swings, sends vanilla block crack progress through `PlayerNpcEntity.showBlockBreakProgress`, plays the table hit sound during recovery, updates the inspector task with break progress, then returns the table to the custom inventory. This recovery must run even before the NPC has selected/saved a home; otherwise a `canUse()` success with a `start()` early return can repeatedly block lower-priority gathering and stone-mining goals. The final break sound/effect comes from `ServerLevel.destroyBlock`, so it only plays after the table is actually removed. Do not remove temporary crafting tables instantly.

The goal clears crack progress when recovery moves out of range, completes, fails, or stops.

Places a chest when the NPC can provide one or can craft one from eight planks. If the recorded owned chest position no longer contains a chest, the NPC chats a missing-storage reaction, clears that stale marker, and can place/craft a new chest when materials are available. If the custom inventory is more than half full, the goal moves partial non-combat stacks into the home chest while keeping weapons, tools, armor, food, arrows, ender pearls, buckets, beds, tables, chests, and any stack that can satisfy the currently unfinished blueprint. A FISHING profile also keeps fishing rods and string, preventing inventory cleanup from removing both the active tool and its replacement recipe; unrelated profiles do not gain this reservation. Build-resource protection is requirement-driven through `PlayerNpcBuildStatusUtil.shouldKeepForCurrentBuild(...)`, so wood-like, stone-like, glass/sand, torch fuel/sticks, wool/bed, potted plant, and other active build inputs must not be deposited while the house is still missing those blocks.

`CheckHomeSuppliesGoal` uses the same current-build material guard when it decides which build supplies to pull back out of the chest. Do not reintroduce broad "any BlockItem" chest withdrawals; they cause storage/build loops and make the NPC take useless blocks.

Places a bed when the NPC has a bed item or can craft one from three same-color wool plus three planks.
