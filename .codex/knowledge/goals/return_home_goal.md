# ReturnHomeGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/ReturnHomeGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Makes Player NPCs with a saved home occasionally travel back instead of wandering forever.

## Behavior

If the NPC is far from its home, has no combat target, and `PlayerNpcEntity.returnHomeCooldown` allows it, it pathfinds to the home center. It is more likely to run when its custom inventory is more than half full, so `ManageHomeBaseGoal` can later deposit items into the home chest.

Nearby home utility work bypasses the ordinary return cooldown. Within 48 blocks of the saved home center, the NPC returns home when it needs home storage, home crafting table work, cooking/smelting, or sleep. This keeps crafting/cooking/storage goals from acting remotely while still allowing temporary field crafting when the NPC is far from home.

Movement speed is `1.0D`.
