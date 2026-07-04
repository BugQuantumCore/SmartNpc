# BoatStockpileGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/BoatStockpileGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets only some Player NPCs act like boat collectors.

## Behavior

`PlayerNpcEntity` has a persisted `boatCollector` trait and a desired boat count of 2-3. If that trait is true, the NPC is idle, near a crafting table, below its desired boat count, and has enough plank-equivalent wood, it consumes five planks and stores an oak boat in its inventory.

The existing water utility crafting remains separate for pass-water behavior.

Cooldown uses `PlayerNpcEntity.boatStockCooldown`.
