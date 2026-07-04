# EatHealingFoodGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/EatHealingFoodGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Lets Player NPCs swap healing food into the main hand, play eating/swing behavior, heal, then restore their previous held item.

## Activation

- Server side only.
- NPC health must be below 70%.
- NPC must not already be healing.
- `PlayerNpcEntity.getGapCooldown()` must be `0`.
- Requires a valid healing food item in the Player NPC inventory.

## Behavior

Consumes one food item from the inventory, shows it in the main hand, plays eating sounds and hand swing, heals after the eating timer completes, then restores the old main-hand item.

While eating, the NPC keeps moving but does not sprint. If it has a combat target, it either backs away or keeps chasing:

- It backs away from close or stronger threats.
- It keeps chasing when the target is weaker and already moving away or outside close melee distance.
- Combat eating movement uses a slower `0.65D` speed.
- Non-combat eating wander uses `0.55D`.

Stopping after food was equipped calls `PlayerNpcEntity.setGapCooldown()`, which uses the entity's tick-down `gapCooldown` API. `PlayerNpcEntity.tick()` decrements that field every server tick.
