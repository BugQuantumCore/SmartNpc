# BuildHouseGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/BuildHouseGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Provides first-pass block-by-block building behavior for Player NPCs.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Requires enough non-utility block items for a loaded build layout.
- Chooses a clear, flat footprint before starting the house.
- Uses the Player NPC home area stored by `PlayerNpcHomeUtil`.
- Cooldown uses `PlayerNpcEntity.buildHouseCooldown`.

## Behavior

Builds a compact starter shelter from whatever non-utility block items the NPC has. The selected layout is loaded from data resources under `src/main/resources/data/player_npc/builds/`.

The build layout provides positions and block roles such as `floor`, `wall`, `roof`, `door`, `torch`, `window_fence`, `window_trapdoor`, and `roof_stair`; it does not require exact block ids for generic structure blocks. The goal consumes any valid building block from the NPC inventory, while preserving utility blocks such as crafting tables, chests, furnaces, and beds for home management.

Door/torch/fence/trapdoor/stair roles are optional utility placements. The goal tries to use matching items from inventory or craft simple oak versions from planks/sticks/coal when possible. If the NPC lacks the resources, it skips the optional role instead of aborting the whole build.

Before building, the goal scans around the NPC for a clear footprint. Every footprint cell must have solid support underneath and air through the layout height. Existing home utility blocks are allowed only when the layout is reusing the saved home area.

The chosen area is saved as the NPC home so `ManageHomeBaseGoal` can place/use the crafting table, bed, and chest in the same base.

There are 520 generated small layouts plus the original example resource. Generated sizes include 3x3, 4x4, 5x5, 3x4, 4x3, 4x5, and 5x4 rectangles and L-shaped footprints. Every generated layout includes a door role.
