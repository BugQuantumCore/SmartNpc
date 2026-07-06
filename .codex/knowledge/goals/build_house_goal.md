# BuildHouseGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/BuildHouseGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Provides first-pass block-by-block building behavior for Player NPCs.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Requires enough non-utility block items or plank-equivalent wood supply for a loaded build layout.
- Chooses a clear, flat footprint before starting the house.
- Uses the Player NPC home area stored by `PlayerNpcHomeUtil`.
- Cooldown uses `PlayerNpcEntity.buildHouseCooldown`.

## Behavior

Builds a compact starter shelter from whatever non-utility block items or plank-equivalent wood supply the NPC has. The selected layout is loaded from data resources under `src/main/resources/data/player_npc/builds/`.

The goal can start an initial base when the NPC has at least 16 buildable/plank-equivalent blocks and can afford the layout floor/foundation roles, even if it cannot yet pay for the whole shelter. If materials run out mid-build, the goal stops with a short retry cooldown instead of waiting the full house cooldown, so later gathering can continue the base.

The build layout provides positions and block roles such as `floor`, `wall`, `roof`, `door`, `torch`, `window_fence`, `window_trapdoor`, and `roof_stair`; it does not require exact block ids for generic structure blocks. The goal consumes valid building blocks from the NPC inventory, while preserving utility blocks such as crafting tables, chests, furnaces, and beds for home management. Raw logs are not treated as direct building blocks here; wood is counted as plank-equivalent so the NPC can convert logs/planks into actual build materials.

Floor/foundation roles use full build blocks or planks. The goal no longer crafts or prefers slabs for the base because slab floors made the generated houses look wrong.

Door/torch/fence/trapdoor/stair roles are optional utility placements. The goal tries to use matching items from inventory or craft simple oak versions from planks/sticks/coal when possible. Torch crafting uses the vanilla-sized one coal/charcoal plus one stick to four torches helper. If the NPC lacks the resources, it skips the optional role instead of aborting the whole build.

Before building, the goal scans around the NPC for a clear footprint. Every footprint cell must have solid support underneath and air through the layout height. Existing home utility blocks are allowed only when the layout is reusing the saved home area.

The chosen area is saved as the NPC home so `ManageHomeBaseGoal` can place/use the crafting table, bed, and chest in the same base. When a build attempt stops, the manage-home cooldown is cleared so home utilities can be placed in the built zone quickly.

There are 1000 generated rectangular layouts plus the original example resource. Generated sizes include 5x5, 6x6, 7x7, 8x8, 9x9, 10x10, 5x6, 6x7, 7x8, 8x9, and 9x10. L-shaped generated layouts were removed. Every generated layout includes a door role.
