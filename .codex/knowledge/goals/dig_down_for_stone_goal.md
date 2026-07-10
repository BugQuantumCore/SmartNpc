# DigDownForStoneGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/DigDownForStoneGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Gives building-interest Player NPCs a direct stone progression path after a base has been selected and terraformed, without relying only on visible surface stone or cave ore targets.

## Activation

- Server side only.
- NPC must be idle with no combat target and not healing.
- Requires a pickaxe in hand or inventory.
- Requires the NPC's raw-log reserve to be met, but does not require extra plank-equivalent wood beyond that reserve.
- Requires a saved home/base and no actionable `TerraformBuildSiteGoal` prep work.
- Skips if the NPC already has at least its per-NPC cobblestone/cobbled deepslate target.
- Uses `gatherCooldown` as its retry cooldown.

## Behavior

The goal chooses a random surface dig site around the saved base center, at least 18 blocks away from the home center and outside the protected home area. It requires a reachable stand position and refuses protected home-area blocks, so it should not dig into the NPC's own house or turn the build footprint into the stone mine.

The point of choosing the base before digging is to give pathing and return-home behavior a stable destination. After stone supply is met, the NPC should return toward the saved base instead of trying to discover a base from inside the mine.

At the dig site it carves a simple downward stair in one horizontal direction. For each step it clears the front head block and the lower front feet block, then moves down to the opened step. This creates a walkable route back instead of a straight vertical grief shaft. It uses `PathNavigationAi` with a bounded safe-drop fallback for lower stair steps, and runs `ClearBlockAi` against body/head/path blockers if the NPC cannot enter the next dig cell.

It mines dirt/gravel/sand with a shovel when available and otherwise uses the pickaxe; stone-like blocks require a pickaxe. Failed walk-to-site attempts use a short cooldown, so the NPC can reselect work instead of spending the full dig cooldown standing at an unreachable stair step.

Mining uses vanilla-style hardness/tool timing, block crack progress, mining hit sounds, and main-hand attack animation. It stops after enough stone is collected, after a bounded number of stair steps, or after a time limit.

If a future change switches this to vertical shaft digging, it must include a matching pillar-up escape that places blocks at the feet and clears head blockers first. The current implementation avoids that need by digging stairs.
