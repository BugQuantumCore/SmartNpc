# ReturnHomeGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/ReturnHomeGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Makes Player NPCs with a saved home occasionally travel back instead of wandering forever.

## Behavior

If the NPC is far from its home, has no combat target, and `PlayerNpcEntity.returnHomeCooldown` allows it, it pathfinds to the home center. It is more likely to run when its custom inventory is more than half full, so `ManageHomeBaseGoal` can later deposit items into the home chest.

Movement speed is `1.0D`.
