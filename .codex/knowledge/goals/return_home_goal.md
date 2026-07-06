# ReturnHomeGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/ReturnHomeGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Makes Player NPCs with a saved home occasionally travel back instead of wandering forever.

## Behavior

If the NPC is far from its home, has no combat target, and `PlayerNpcEntity.returnHomeCooldown` allows it, it pathfinds to the home center. It is more likely to run when its custom inventory is more than half full, so `ManageHomeBaseGoal` can later deposit items into the home chest.

Nearby home utility work bypasses the ordinary return cooldown. Within 48 blocks of the saved home center, the NPC returns home when it needs home storage, home crafting table work, cooking/smelting, or sleep. This keeps crafting/cooking/storage goals from acting remotely while still allowing temporary field crafting when the NPC is far from home.

Build work can also bypass the ordinary return cooldown when an unfinished saved home has a valid next placement and the NPC is outside the build work area. However, return-home must not start a build return while the NPC is still below its wood/log or cobblestone reserve and the inventory is not more than half full. Otherwise `BuildHouseGoal.hasReadyHomeBuildWork(...)` can preempt an active material trip, making the NPC walk toward a tree or stone target, turn back home, fail again for missing materials, then repeat.

Movement speed is `1.0D`.
