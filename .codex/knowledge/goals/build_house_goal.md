# BuildHouseGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/BuildHouseGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()` through `InterestGatedGoal`.

## Purpose

Builds structures loaded from Structurize `.blueprint` datapack resources:

```text
data/<namespace>/builds/*.blueprint
```

The old randomly generated role-based shelter system and its generated JSON files were removed. New custom builds should be scanned/exported as Structurize blueprints.

## Activation

- Server side only.
- NPC must have the `BUILDING` interest.
- NPC must be alive, idle, not healing, not riding, and not in combat.
- First home/base selection requires the NPC's raw-log reserve only. It uses wood/plank-equivalent blocks with raw-log reserve `0` for the base-selection readiness check.
- Actual blueprint block placement waits until both daily log and cobblestone/cobbled-deepslate supply targets are met. This keeps the flow as logs -> select base -> terraform -> gather/dig stone near base -> return/build.
- Requires at least 16 available build/plank-equivalent blocks before selecting a new home.
- Existing unfinished homes can resume from the saved `PlayerNpcHomeUtil` home area and layout id.
- Cooldown uses `PlayerNpcEntity.buildHouseCooldown`.

## Layout Loading

`PlayerNpcBuildLayoutLoader` loads `.blueprint` files and converts their compressed-NBT palette/block arrays into `PlayerNpcBuildLayout.RelativeBlock` entries. The reader is implemented in `PlayerNpcBlueprintLayoutReader` and is based on Structurize GPL v1 blueprint serialization logic.

The JSON parser still exists only as a compatibility path for older local packs. Do not add new JSON build resources.

## Behavior

The goal scans around the NPC for a supported footprint and saves the home area through `PlayerNpcHomeUtil` as soon as logs are ready. If terraform work remains, or if either primary supply target is still low, `canUse()` returns false after saving the base so higher-priority prep/resource goals can run. Once both supply targets are ready, the goal places a build-site crafting table just outside the selected footprint when possible, then walks the exact block list bottom-up.

For each target block:

1. If the world state already equals the target state, or matches an accepted material-family substitute, skip it.
2. If a conflicting block exists and can be safely cleared, mine it first.
3. If the target state is air, keep the space clear.
4. If the required item can be crafted but is not already carried, walk to the build-site crafting table and craft the material into inventory first.
5. If the NPC lacks the required item and cannot craft it, stop early with a short material retry cooldown.
6. Otherwise consume the required item or an accepted substitute, preview it in the main hand, place the target/substitute state, apply block entity NBT if present, play the block place sound, and continue.

`PlayerNpcBuildMaterialUtil` keeps the blueprint target as the canonical design while allowing biome-local substitutions. It preserves shared state properties on replacement blocks, such as stair facing/half/shape, door half/facing/hinge/open state, slab type, log axis, and bed facing/part. Multiblock second halves such as bed heads and upper door halves do not consume a second item; they derive their material from the already placed first half when possible.

Building material crafting uses raw-log reserve `0` after the home build is committed. The reserve is a bootstrap readiness threshold, not a protected material pool during construction.

The goal updates AI detail with layout name, placement/clearing state, and progress. The inspector also shows a build-status line from `PlayerNpcBuildStatusUtil`.

## Current Limits

- No full Structurize hologram/build-tool UI yet.
- Rotation/mirror is not implemented; scans should already face the direction the NPC should build.
- The PlayerNpc home origin is the blueprint minimum corner, not the Structurize anchor block.
- `.schematic`, `.schem`, and vanilla structure `.nbt` are not loaded by this goal.

## Reference Direction

MineColonies delegates low-level placement to Structurize `StructurePlacer` and a colony-specific `BuildingStructureHandler`. PlayerNpc ports the compatible file-reading part and keeps the placement AI native, because MineColonies' handler depends on colony, citizen, work-order, and request systems that PlayerNpc does not have.
