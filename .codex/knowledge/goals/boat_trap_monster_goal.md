# BoatTrapMonsterGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/BoatTrapMonsterGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets Player NPCs use stored boats as a combat trick against monsters.

## Behavior

If the NPC is fighting a nearby `Monster`, has a boat in inventory, and the target is not a `Player` or `PlayerNpcEntity`, it consumes one boat, places an oak boat at the monster, and tries to force the monster to ride it. This is intentionally not applied to players or other Player NPCs.

Cooldown uses `PlayerNpcEntity.boatTrapCooldown`.
