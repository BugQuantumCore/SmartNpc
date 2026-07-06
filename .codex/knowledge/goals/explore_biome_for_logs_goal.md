# ExploreBiomeForLogsGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/ExploreBiomeForLogsGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets a Player NPC search farther across the biome when no valid logs are nearby, especially when it needs build materials/raw logs or would otherwise stand idle with inventory room.

## Activation

- Server side only.
- NPC must be alive, idle, not healing, not a passenger, and have no combat target.
- `PlayerNpcEntity.getBiomeExploreCooldown()` must be `0`.
- No accessible log block can already be found in the 48 block nearby log scan.
- Inventory must have more than two free slots so the NPC does not wander away while full.

## Behavior

The goal chooses one random horizontal travel direction, finds a walkable surface position far along that direction, and pathfinds toward it at player-like speed. Travel targets must have a completed `Path.canReach()` path before the goal accepts them, so the NPC does not start biome exploration toward a partial/unreachable far target. Keep the number of travel-target path attempts bounded; pathfinding from wide scans can hurt server TPS. It keeps the same direction while periodically repathing farther ahead.

While running, it scans nearby blocks for logs every few ticks. The nearby log scan is 48 blocks horizontally, 2 blocks down, and 12 blocks up; do not increase the downward scan unless the reachable-path check is also preserved. Home-area logs are ignored so a wooden house does not satisfy the search or cause material gathering to mine home blocks. The wide scan first collects and sorts log candidates, then performs expensive `Path.canReach()` checks only for a small nearest-candidate cap. Do not call pathfinding for every block in the 48-block scan. A log only satisfies the nearby scan when a nearby stand position is actually reachable through `Path.canReach()` or the NPC is already close enough, so buried/downhill partial paths do not trap the NPC in local idle. When a valid log appears within 48 blocks, it stops immediately, clears the material gather cooldown, and lets `GatherMaterialsGoal` take the next task so the NPC can harvest that tree normally and fill its 4-12 raw-log reserve.

Cooldown uses `PlayerNpcEntity.biomeExploreCooldown`. The cooldown is short so this goal can act as a productive fallback instead of leaving the NPC idle for long gaps when local gather targets are unavailable.
