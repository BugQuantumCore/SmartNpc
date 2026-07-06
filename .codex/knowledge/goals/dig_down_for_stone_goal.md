# DigDownForStoneGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/DigDownForStoneGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Gives early Player NPCs a direct stone progression path after they have enough wood/planks and a pickaxe, without relying only on visible surface stone or cave ore targets.

## Activation

- Server side only.
- NPC must be idle with no combat target and not healing.
- Requires a pickaxe in hand or inventory.
- Requires at least 16 plank-equivalent wood supply.
- Skips if the NPC already has at least 16 cobblestone/cobbled deepslate.
- Uses `gatherCooldown` as its retry cooldown.

## Behavior

The goal chooses a surface dig site 10-24 blocks away from the NPC and at least 18 blocks away from the saved home center. It requires a reachable stand position and refuses protected home-area blocks, so it should not dig into the NPC's own house.

At the dig site it carves a simple downward stair in one horizontal direction. For each step it clears the front head block and the lower front feet block, then moves down to the opened step. This creates a walkable route back instead of a straight vertical grief shaft. It mines dirt/gravel/sand with a shovel when available and otherwise uses the pickaxe; stone-like blocks require a pickaxe.

Mining uses vanilla-style hardness/tool timing, block crack progress, mining hit sounds, and main-hand attack animation. It stops after enough stone is collected, after a bounded number of stair steps, or after a time limit.

If a future change switches this to vertical shaft digging, it must include a matching pillar-up escape that places blocks at the feet and clears head blockers first. The current implementation avoids that need by digging stairs.
