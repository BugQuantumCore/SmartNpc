# GatherMaterialsGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/GatherMaterialsGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Gives idle Player NPCs a basic worker-style material gathering job so they do not only combat.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Inventory must not be mostly full.
- Cooldown after each attempt uses `PlayerNpcEntity.gatherCooldown`.

## Behavior

Searches nearby blocks for logs, stone, cobblestone, coal ore, dirt, or grass blocks. The NPC pathfinds to one, looks at it, swings periodically, emits block break particles, then breaks the block and lets normal item drops/pickup handle materials.

Material choice is progression-aware:

- If the NPC still lacks starter tools/weapons, it prioritizes nearby logs until it has enough wood to place a crafting table or craft starter gear.
- Once it has a pickaxe and still needs stone gear, it prioritizes stone/cobblestone/deepslate/cobbled deepslate.
- If it has raw food, has a pickaxe, and lacks furnace fuel, it can prioritize coal ore.
- It pauses gathering when the gear crafting goal is ready to place a table or craft the next tool, using `PlayerNpcEntity.craftGearCooldown` to avoid fighting the craft goal.
- It records the target block id and coordinates in `PlayerNpcEntity`'s synced AI detail field so the inspector can show what block it is trying to mine.
- When gathering logs, after one log is mined the goal searches for the nearest connected/nearby log in the same tree area before falling back to a new tree search.

When the NPC has a matching axe, pickaxe, or shovel in its custom inventory, the goal temporarily equips it while mining and restores the previous main-hand item afterward. Mining now triggers the Player NPC renderer's main-hand attack animation timer, not just vanilla `swing`, so block breaking is visible on the model.

Mining duration uses a vanilla-style block hardness and held-tool speed calculation instead of a fixed 24 tick delay. Empty-hand log mining is slow, axe log mining is faster, and wrong-tool mining receives the slower incorrect-tool penalty.

When a block is successfully destroyed, the equipped main-hand tool loses durability through `PlayerNpcEntity.hurtMainHandItem(1)`.

The inspector task detail includes `walking` while pathing and `current/required ticks` while mining, for example `minecraft:oak_log @ 12 64 -8 18/60t`.

The goal now finds a nearby valid stand position for the target block and pathfinds there, instead of trying to path into the solid target block. It also repaths periodically and gives up after a short failed-path timeout so the NPC does not remain stuck in `Gathering materials` forever.
