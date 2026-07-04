# ManageHomeBaseGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/ManageHomeBaseGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs maintain a small home area instead of only wandering, gathering, or fighting.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Cooldown uses `PlayerNpcEntity.manageHomeCooldown`.
- Uses or creates the NPC's saved `PlayerNpcHomeUtil` home area.

## Behavior

Places a crafting table in the home area when needed, using the correct recipe path: logs convert to planks, then four planks are consumed.

If `CraftBasicGearGoal` had placed a temporary crafting table outside the saved home area and the NPC is still nearby, this goal walks to that table, breaks it over time, shows main-hand swing/break particles, updates the inspector task with break progress, then returns the table to the custom inventory. Do not remove temporary crafting tables instantly.

Places a chest when the NPC can provide one or can craft one from eight planks. If the custom inventory is more than half full, the goal moves partial non-combat stacks into the home chest while keeping weapons, tools, armor, food, arrows, ender pearls, buckets, beds, tables, and chests.

Places a bed when the NPC has a bed item or can craft one from three same-color wool plus three planks.
