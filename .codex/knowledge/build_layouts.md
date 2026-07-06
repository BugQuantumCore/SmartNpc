# Player NPC Build Layouts

## Source

- `src/main/java/com/pla/player_npc/util/PlayerNpcBuildLayout.java`
- `src/main/java/com/pla/player_npc/util/PlayerNpcBuildLayoutLoader.java`
- `src/main/java/com/pla/player_npc/util/PlayerNpcBuildMaterialUtil.java`
- `src/main/java/com/pla/player_npc/util/PlayerNpcBlueprintLayoutReader.java`
- `src/main/java/com/pla/player_npc/entity/goal/BuildHouseGoal.java`
- `src/main/resources/data/player_npc/builds/*.blueprint`

## Blueprint Format

PlayerNpc now reads Structurize v1 `.blueprint` files directly from datapacks/resource packs:

```text
data/<namespace>/builds/<layout_id>.blueprint
```

The reader uses the core Structurize compressed-NBT layout:

- `version`
- `size_x`, `size_y`, `size_z`
- `palette`
- packed `blocks`
- optional `tile_entities`
- optional `required_mods`

The unpacking logic is based on the GPL-licensed Structurize `BlueprintUtil` implementation. PlayerNpc converts each blueprint block into `PlayerNpcBuildLayout.RelativeBlock` so the existing NPC inventory, crafting, clearing, and placement behavior can stay PlayerNpc-native.

## Authoring Workflow

Use Structurize's Scan Tool to scan the build and save a `.blueprint`, then place that file in a datapack under:

```text
data/player_npc/builds/<name>.blueprint
```

Do not rename scanned files after scanning if you still want Structurize/MineColonies style-pack compatibility. For PlayerNpc-only datapacks, the file path becomes the layout id, for example `data/player_npc/builds/houses/oak_house.blueprint` becomes `player_npc:houses/oak_house`.

## Runtime Behavior

- Blueprints are selected randomly from loaded layouts.
- The NPC treats the saved home origin as the blueprint minimum corner.
- The blueprint stores canonical target `BlockState`s, but build completion and placement use `PlayerNpcBuildMaterialUtil` to accept compatible equivalent material families.
- Air blocks from the blueprint are kept, so the builder can clear safe conflicting blocks inside the scanned volume.
- Block entity NBT is applied after placement with local scan coordinates rewritten to the world position.
- Missing required mods are logged; missing mod blocks may load as air.

Accepted material substitutions include:

- Any plank/log variant for scanned planks/logs.
- Any matching wooden door, trapdoor, fence, fence gate, stair, or slab for scanned wooden variants.
- Any bed color for scanned beds, and any carpet color for scanned carpets.
- Cobblestone-like blocks can be replaced by cobblestone, mossy cobblestone, cobbled deepslate, blackstone, sandstone, or red sandstone.
- Loose fill blocks can be replaced by dirt, coarse/rooted dirt, grass block, sand, red sand, gravel, or mud.
- Stone masonry blocks can be replaced by stone, smooth stone, stone bricks, bricks, polished stone families, deepslate brick families, tuff, calcite, and sandstone masonry variants.
- Stone stairs/slabs can be replaced by another stone-family stair/slab while preserving shared orientation/shape properties.

## Legacy JSON

The old generated shelter JSON library and the hand-authored `player_npc:structure_v1` example were removed. The JSON parser remains in `PlayerNpcBuildLayoutLoader` only as a compatibility path for older local test packs; new builds should be `.blueprint`.

## Inspector Status

The inspector shows a `Build:` line from `PlayerNpcBuildStatusUtil`, including whether a builder has no selected build, has a missing layout, has a finished layout, or has required blocks still missing.

This is not a full Structurize hologram/build-tool UI yet. It is server-authoritative progress/missing-block status attached to the existing PlayerNpc inspector.
