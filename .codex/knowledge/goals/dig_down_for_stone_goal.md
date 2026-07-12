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
- Requires a saved home/base and no actionable `TerraformBuildSiteGoal` prep work.
- Uses `GatherStoneGoal.isStoneSupplyPhaseActive(...)`, so it runs when the prepared base still needs cobblestone/cobbled deepslate supply or the current build has a stone-family need. It should not be blocked by a one-log dip or by pending stone smelting.
- Skips if the prepared stone phase is already closed.
- Uses `gatherCooldown` as its retry cooldown.

## Behavior

The goal chooses a random surface dig site around the saved base center, at least 18 blocks away from the home center and outside the protected home area. It requires a reachable stand position and refuses protected home-area blocks, so it should not dig into the NPC's own house or turn the build footprint into the stone mine.

The point of choosing the base before digging is to give pathing and return-home behavior a stable destination. After stone supply is met, the NPC should return toward the saved base instead of trying to discover a base from inside the mine.

At the dig site it carves a simple downward stair in one horizontal direction. For each step it clears the front head block and the lower front feet block, then moves down to the opened step. This creates a walkable route back instead of a straight vertical grief shaft. It uses exact `PathNavigationAi` routing after the initial surface approach, with a bounded safe-drop fallback for lower stair steps, and runs `ClearBlockAi` only against physical body/head/path blockers toward the current dig cell. Route clearing should ignore non-colliding grass, flowers, and plant clutter; actual stair digging can still mine physical dirt/grass blocks.

Dig-down is only the opener for stone access. It must stop as soon as `GatherStoneGoal.hasNearbyStoneTarget(...)` sees actionable exposed stone, and it must not apply the normal gather cooldown in that handoff case. `GatherStoneGoal` then owns clearing remaining dirt around the stone stand and mining the connected stone cluster.

Dig-site mining and dig-path clearing go through `BreakingBlockAi`, so vanilla-style hardness/tool-speed timing, tool selection, block crack progress, mining hit sounds, main-hand attack animation, durability loss, and mining sneak match the rest of the building-interest resource goals. Failed walk-to-site attempts use a short cooldown, so the NPC can reselect work instead of spending the full dig cooldown standing at an unreachable stair step.

Mining uses vanilla-style hardness/tool timing, block crack progress, mining hit sounds, and main-hand attack animation. It stops after enough stone is collected, after a bounded number of stair steps, or after a time limit.

If a future change switches this to vertical shaft digging, it must include a matching pillar-up escape that places blocks at the feet and clears head blockers first. The current implementation avoids that need by digging stairs.
