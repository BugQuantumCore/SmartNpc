# Player NPC Structure Building References

## Scope

This note records the July 2026 review of three local reference repos:

- `minecolonies-release-1.20`
- `Structurize-version-1.20`
- `workers-1.20.1-2.0.3_decompiled`

Use this as design guidance for Player NPC structure building, material planning, repair, terraforming, and daily work selection.

## Licensing

`minecolonies-release-1.20/LICENSE` is GPL v3. PlayerNpc is GPL, and `PlayerNpcBlueprintLayoutReader` intentionally ports the small Structurize v1 blueprint serialization/unpacking behavior needed to read `.blueprint` files. Keep credit to MineColonies/Structurize explicit when touching this code. Public distribution should keep `NOTICE.md` with the mod.

The Workers repo here is a decompiled reference. Treat it as behavioral reference only.

## Current PlayerNpc Support

PlayerNpc now supports exact datapack-provided house layouts through `PlayerNpcBuildLayoutLoader`.

Current build resources live under:

```text
data/<namespace>/builds/*.blueprint
```

The primary format is Structurize v1 `.blueprint`, which is compressed NBT containing:

- `version`
- `size_x`, `size_y`, and `size_z`
- `palette`
- packed `blocks`
- optional `tile_entities`
- optional `required_mods`

`PlayerNpcBlueprintLayoutReader` converts the `.blueprint` data into `PlayerNpcBuildLayout.RelativeBlock` entries. The previous 1000 generated role-based shelter JSON files and the single hand-authored JSON example were removed. The JSON parser remains only for compatibility with older local test packs.

Current limitations:

- No full Structurize hologram/build-tool UI.
- No vanilla structure NBT loading.
- No `.schematic` or `.schem` parser.
- No rotation/mirror metadata in the layout.
- The PlayerNpc home origin is the blueprint minimum corner, not the Structurize anchor offset.
- Markers are stored as strings but most home-management goals do not consume marker positions yet.
- No persisted iterator index yet; unfinished builds resume by rescanning the saved exact layout.
- No finished/repair status flag beyond checking whether required canonical block states or accepted material substitutes still match.

## MineColonies And Structurize Pattern

MineColonies delegates low-level blueprint placement to Structurize:

- `Structurize-version-1.20/src/main/java/com/ldtteam/structurize/blueprints/v1/Blueprint.java`
- `Structurize-version-1.20/src/main/java/com/ldtteam/structurize/placement/StructurePlacer.java`
- `minecolonies-release-1.20/src/main/java/com/minecolonies/coremod/entity/ai/basic/AbstractEntityAIStructure.java`
- `minecolonies-release-1.20/src/main/java/com/minecolonies/coremod/entity/ai/basic/AbstractEntityAIStructureWithWorkOrder.java`
- `minecolonies-release-1.20/src/main/java/com/minecolonies/coremod/entity/ai/workers/EntityAIStructureBuilder.java`
- `minecolonies-release-1.20/src/main/java/com/minecolonies/coremod/entity/ai/workers/BuildingStructureHandler.java`

Key ideas to reimplement cleanly:

- Compare blueprint target blocks against world blocks to decide whether a block is already correct, missing, or conflicting.
- Use stages instead of one flat placement loop:
  - clear terrain
  - place solid/foundation blocks
  - place weak/secondary blocks
  - remove water or non-solid conflicts
  - decorate and place utility blocks
  - finalize
- Persist a progress position and current stage so work can resume.
- Return explicit build step results such as success, missing item, break conflicting block, limit reached, and finished.
- When an item is missing, pause building and register a material request instead of abandoning the structure.
- For repair or upgrade work, skip the full clear stage and only replace missing or wrong blocks.
- Treat completion as reaching `FINISHED` on the final stage, then mark the work order/building complete.

MineColonies terraforming is mostly staged clearing plus per-block conflict mining. It does not need to be copied wholesale. For PlayerNpc, keep terrain editing small and bounded: only within the selected footprint, only replaceable or mineable blocks, and avoid block entities and protected inventories.

Structurize also provides client preview/build-tool systems:

- `BlueprintRenderer`
- `BlueprintHandler`
- scan/build tool UI and placement messages

Full Structurize UI integration is still heavy. It would add dependencies such as Structurize, BlockUI, and likely Domum Ornamentum. PlayerNpc currently integrates the `.blueprint` file format and keeps placement, material use, crafting, and inspector status native.

## Workers Pattern

Workers uses smaller, area-oriented state machines that are closer to PlayerNpc scale.

Relevant classes:

- `workers-1.20.1-2.0.3_decompiled/com/talhanation/workers/entities/ai/BuilderWorkGoal.java`
- `workers-1.20.1-2.0.3_decompiled/com/talhanation/workers/entities/ai/BuildArea.java`
- `workers-1.20.1-2.0.3_decompiled/com/talhanation/workers/entities/ai/FarmerWorkGoal.java`
- `workers-1.20.1-2.0.3_decompiled/com/talhanation/workers/entities/ai/FishermanWorkGoal.java`
- `workers-1.20.1-2.0.3_decompiled/com/talhanation/workers/entities/ai/MinerWorkGoal.java`

Useful ideas:

- Keep a build area with stored structure NBT and state flags.
- Parse target blocks into placement stacks and conflicting blocks into breaking stacks.
- Build bottom-up by Y level.
- Request missing items for the current layer.
- Normalize some material requirements, such as grass/farmland needing dirt.
- Treat multiblock parts specially, such as beds or double plants.
- Farming, fishing, and mining are long-lived work-area goals instead of random one-off actions.

Do not copy decompiled code. Reuse only the design ideas.

## Implemented Structure Format

Use Structurize `.blueprint` files for custom PlayerNpc houses:

```text
data/player_npc/builds/<name>.blueprint
```

Recommended import targets:

1. Structurize Scan Tool output (`.blueprint`) copied into a PlayerNpc datapack.
2. Vanilla Minecraft structure NBT (`.nbt`) using Mojang `StructureTemplate` APIs, as a future direct loader.
3. Optional offline converters for `.schematic` or `.schem` into Structurize `.blueprint`.

Runtime `.schematic` support is lower priority because formats vary and require extra parsing. A converter is safer than making every NPC load third-party schematic formats in game.

Implemented `.blueprint` import:

- Read compressed NBT with `NbtIo.readCompressed`.
- Decode Structurize v1 `palette` entries with `NbtUtils.readBlockState`.
- Unpack `blocks` using Structurize's two-shorts-per-int order.
- Preserve air blocks so the builder clears the scanned volume.
- Preserve block entity NBT and rewrite local x/y/z when placing.

Structures should be scanned in the orientation the NPC should build. Multiblock entries such as beds and doors are represented as exact block states from the scan.

Implemented material substitution:

- `PlayerNpcBuildMaterialUtil` treats the scanned state as canonical and allows equivalent material families at build time.
- Wood families include planks, logs, doors, trapdoors, fences, fence gates, stairs, and slabs.
- Bed and carpet colors are interchangeable.
- Cobblestone-like, loose fill, stone masonry, stone stair, and stone slab families are interchangeable within their own family. Podzol is loose fill alongside dirt/coarse dirt/rooted dirt/grass/sand/gravel/mud, so an unavailable podzol blueprint cell may consume carried dirt rather than blocking otherwise-ready construction.
- Shared blockstate properties are copied to substitutes so orientation and shape remain close to the blueprint.

## Build Task Persistence

Persist build task state on the NPC, not only the home rectangle:

- structure id
- origin
- rotation and mirror
- stage
- progress local position or iterator index
- status: planned, building, paused_missing_materials, finished, repair
- missing material cache
- discovered marker positions
- finished flag and built structure version

Keep existing `PlayerNpcHomeUtil` fields for compatibility, but add a richer build task record when exact structures are introduced.

## Missing Block And Repair Algorithm

The PlayerNpc-native version should use this loop:

1. Resolve the target block state and optional block entity data for the current local position.
2. Compare world state to target state.
3. If equivalent, skip.
4. If world block is replaceable, place the target block if the NPC has or can craft the item.
5. If world block conflicts and clearing is allowed for the current stage, mine or remove it first.
6. If the required item is missing, record the missing material, pause the build, and let the daily planner choose a gathering/crafting goal.
7. When all required entries compare equal, mark the structure finished.

Repair is the same comparison loop with clearing limited to blocks inside the finished structure. It should not flatten the full footprint again.

## Crafting Requirements

Before placing a required block, the NPC should try to craft it if the recipe is simple or supported by `PlayerNpcCraftingUtil`.

High-value craft targets:

- planks from logs
- sticks
- doors
- stairs
- slabs, if exact structures use them
- trapdoors
- fences and gates
- torches
- chest
- crafting table
- furnace
- bed
- shield
- bow
- fishing rod
- arrows

For exact structures, the material audit should count required final items, then count craftable equivalents from logs, cobblestone, coal, wool, sticks, and planks.

## Daily Planner

PlayerNpc currently registers many goals at similar priorities, so the NPC can switch between unrelated work during a day. Add a daily planner to choose one major work theme per Minecraft day.

Persist:

- current day number, based on `serverLevel.getDayTime() / 24000L`
- daily plan enum/string
- daily plan budget ticks
- daily plan completed flag

Suggested major plans:

- `BUILD_HOUSE`
- `GATHER_BUILD_MATERIALS`
- `GATHER_LOGS`
- `GATHER_STONE`
- `MINE_ORE`
- `FARM`
- `FISH`
- `HUNT_FOOD`
- `EXPLORE`
- `RETURN_HOME`
- `NIGHT_SLEEP`
- `NIGHT_STAY_INSIDE`
- `NIGHT_MONSTER_HUNT`

Long-running work goals should check the selected daily plan before starting. Emergency goals such as combat, fleeing, eating, drowning recovery, and returning home near sunset can override the daily plan.

Planner weighting:

- No house or unfinished house with enough materials: prefer `BUILD_HOUSE`.
- Active build missing materials: prefer `GATHER_BUILD_MATERIALS`.
- Low wood: prefer `GATHER_LOGS`.
- Low stone/build blocks: prefer `GATHER_STONE`.
- Needs iron gear or tools: prefer `MINE_ORE`.
- Farm exists and food is low: prefer `FARM`.
- Has fishing rod and water is nearby: allow `FISH`.
- Food is low: prefer `HUNT_FOOD`.
- Near sunset: override to `RETURN_HOME`.
- At night in a finished house: sleep, stay inside, or hunt monsters depending on bed availability, gear, food, and personality.

This lets unfinished builds remain paused for several days if the NPC chooses other work, then resume when enough materials exist.

## Chest And Supply Audit

Add a home inventory audit phase that opens the owned chest occasionally and checks:

- missing structure materials
- food
- arrows
- torches
- emergency blocks for pillaring or escaping holes
- tool durability and spare tools
- shield, bow, and fishing rod

The audit should withdraw needed materials from the home chest before a build or combat/exploration plan, then deposit surplus after returning home.

When structure markers exist, `ManageHomeBaseGoal` should prefer marked chest, bed, crafting table, and furnace positions instead of searching any interior cell.

## Implementation Slices

1. Add daily plan fields and persistence to `PlayerNpcEntity`.
2. Add a `PlayerNpcDailyPlan` helper that chooses one plan per Minecraft day.
3. Gate long-running goals by daily plan while keeping emergency goals independent.
4. Extend build layout data with optional markers.
5. Make `ManageHomeBaseGoal` use marked utility positions when present.
6. Add exact structure loading from vanilla NBT if non-Structurize workflows are still needed.
7. Add persistent build task state and staged placement.
8. Add material audit and crafting requirements.
9. Add repair scans for finished homes.
10. Consider optional full Structurize preview/build-tool UI after the native `.blueprint` builder is stable.
