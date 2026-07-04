# PlayerNpcProjectileBlockGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/PlayerNpcProjectileBlockGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Lets Player NPCs use solid blocks from their inventory to build a small random wall when an incoming projectile is near them.

## Activation

- Server side only.
- Player NPC must be alive, on ground, not a passenger, not healing, and not on place-block parry cooldown.
- Requires a solid defensive `BlockItem` in the Player NPC inventory.
- A nearby projectile must be incoming or already very close.
- Uses `PlayerNpcEntity.getPlaceBlockToParryChance()` for the random chance roll.

## Cooldown

Uses `PlayerNpcEntity.placeBlockParryCooldown`, not a `COOLDOWN_TAG`.

`PlayerNpcEntity.setPlaceBlockParryCooldown()` sets a 60 tick cooldown, and `PlayerNpcEntity.tick()` decrements it every server tick.

## Behavior

The goal sets AI state `ai.player_npc.blocking_projectile`, stops navigation, looks at the projectile, and places one defensive block every few ticks until the generated pattern is finished or blocks run out.

The wall is anchored on the incoming projectile's X/Z position, starts at the motion-blocking heightmap surface, and builds upward toward the projectile height. It randomly selects one of 11 legacy-style wall patterns and a random rotation before placing blocks.

It consumes blocks from the Player NPC inventory and swings the main hand when placing.
