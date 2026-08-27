# DigDownForStoneGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/DigDownForStoneGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Gives Player NPCs a direct stone progression path after log supply is met, without relying only on visible surface stone or cave ore targets. Building NPCs use it after a base has been selected and terraformed; pure mining NPCs use it as their no-home stone opener.

## Activation

- Server side only.
- NPC must be idle with no combat target and not healing.
- Requires a carried pickaxe, including held, inventory, offhand, or reserved weapon/tool slots.
- Uses `GatherStoneGoal.isStoneSupplyPhaseActive(...)`, so it only runs when log supply is met and stone supply still needs cobblestone/cobbled deepslate.
- Pure mining jobs do not require or create a saved home/base. Building-driven stone work still requires a saved base and no actionable `TerraformBuildSiteGoal` prep work.
- Skips if the prepared stone phase is already closed.
- Uses `gatherCooldown` as its retry cooldown.

## Behavior

The goal chooses a random surface dig site around the saved base center for building NPCs, or around the NPC's current work area for pure miners. Building sites stay at least 18 blocks away from the home center and outside the protected home area. Farm-support origins also stay outside the farm work/entrance footprint. Pure miners do not call `PlayerNpcHomeUtil.getOrCreateHome(...)` and do not persist a home as part of mining setup.

Activation is split across shared expensive-work slices. A cacheable nearby-stone check completes first; dig-origin discovery runs on a short retry afterward and checks at most one navigation path. Starting the goal does not immediately create another path. Initial routing and later repaths run from `tick()` only after a fresh scheduler admission, and a healthy live path to the same dig origin is reused instead of being rebuilt every repath interval. This prevents the nearby scan, origin paths, and route commit from becoming one server-tick pathfinding burst.

For building NPCs, choosing the base before digging gives pathing and return-home behavior a stable destination. After building stone supply is met, the NPC should return toward the saved base instead of trying to discover a base from inside the mine. Pure miners intentionally skip this base-selection step.

At the dig site it carves a simple downward stair in a cardinal or diagonal horizontal direction. For each step it clears the front head block and the lower front feet block, then moves down to the opened step. This creates a walkable route back instead of a straight vertical grief shaft. It uses exact `PathNavigationAi` routing after the initial surface approach, with a bounded safe-drop fallback for lower stair steps, and runs `ClearBlockAi` only against physical body/head/path blockers toward the current dig cell. Route clearing should ignore non-colliding grass, flowers, and plant clutter; actual stair digging can still mine physical dirt/grass blocks.

Dig-down is only the opener for stone access. It must stop as soon as `GatherStoneGoal.hasNearbyStoneTarget(...)` sees actionable exposed stone, and it must not apply the normal gather cooldown in that handoff case. `GatherStoneGoal` then owns clearing remaining dirt around the stone stand and mining the connected stone cluster.

Dig-site mining and dig-path clearing go through `BreakingBlockAi`, so vanilla-style hardness/tool-speed timing, tool selection, block crack progress, mining hit sounds, main-hand attack animation, durability loss, and mining sneak match the rest of the building-interest resource goals. Failed walk-to-site attempts use a short cooldown, so the NPC can reselect work instead of spending the full dig cooldown standing at an unreachable stair step.

Every dig target and route-clear target is checked against home ownership plus `FarmAi`'s full buffered underground farm column. The check runs both before and after `ClearBlockAi.tick(...)`, because line-of-sight resolution may retarget a different blocker. A protected retarget is stopped and the local prospect route is recovered/reselected rather than mining beneath the farm.

Mining uses vanilla-style hardness/tool timing, block crack progress, mining hit sounds, and main-hand attack animation. It stops after enough stone is collected, after a bounded number of stair steps, or after a time limit.

Pure miners continue from log supply to stone supply to ore search; do not insert the building house-selection step into this flow. If a future change switches this to vertical shaft digging, it must include a matching pillar-up escape that places blocks at the feet and clears head blockers first. The current implementation avoids that need by digging stairs.
