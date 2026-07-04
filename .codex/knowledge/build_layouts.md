# Player NPC Build Layouts

## Source

- `src/main/java/com/pla/player_npc/util/PlayerNpcBuildLayout.java`
- `src/main/java/com/pla/player_npc/util/PlayerNpcBuildLayoutLoader.java`
- `src/main/java/com/pla/player_npc/event/NpcGearLoadEvent.java`
- `src/main/resources/data/player_npc/builds/*.json`

## Loader

`PlayerNpcBuildLayoutLoader` is a `SimpleJsonResourceReloadListener` rooted at:

```text
builds
```

It is registered from `NpcGearLoadEvent` alongside `EquipmentDataLoader`.

Each loaded JSON becomes a `PlayerNpcBuildLayout` with:

- id from the resource location,
- width, height, and depth from `size`,
- shape string,
- relative blocks from `blocks[]`,
- footprint cells inferred from blocks at `y = 0` or roles named `floor`/`foundation`.

## JSON Format

Example block entry:

```json
{
  "pos": [0, 1, 0],
  "role": "wall"
}
```

The loader currently needs:

- `size.width`
- `size.height`
- `size.depth`
- `blocks[].pos`
- `blocks[].role`

Other metadata, such as markers, can exist in the file for future use and is ignored by the current loader.

## Generated Data

The repository contains 520 generated build layouts:

```text
src/main/resources/data/player_npc/builds/generated_000.json
...
src/main/resources/data/player_npc/builds/generated_519.json
```

The generated library intentionally uses role-based blocks instead of material ids. `BuildHouseGoal` selects a layout by required generic block count, then substitutes any valid building blocks the NPC actually has, such as cobblestone, dirt, sand, logs, planks, or deepslate.

Every generated layout includes a `door` role. Many also include optional roles such as `torch`, `window_fence`, `window_trapdoor`, and `roof_stair`. These roles are optional at build time and can be skipped if the NPC lacks matching materials.

The generated footprints include flat rectangles and L shapes in small starter sizes:

- 3x3
- 3x4
- 4x3
- 4x4
- 4x5
- 5x4
- 5x5

## Placement Rule

`BuildHouseGoal` scans nearby origins before choosing a layout. A valid build site has:

- solid support under every footprint cell,
- clear air from the floor through the layout height,
- no blocked footprint cells except allowed existing home utility blocks when reusing a saved home.

This prevents NPCs from starting houses inside cluttered terrain and lets them prefer flat, buildable areas.
