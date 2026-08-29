# EatHealingFoodGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/EatHealingFoodGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Lets Player NPCs swap healing food into the main hand, play eating/swing behavior, heal or regenerate, then restore their previous held item.

## Activation

- Server side only.
- NPC health must be below 70%.
- NPC must not already be healing.
- `PlayerNpcEntity.getGapCooldown()` must be `0`.
- Requires a valid healing food item in the Player NPC inventory.

## Behavior

Consumes one food item from the inventory, shows it in the main hand, plays eating sounds and hand swing, applies the food result after the eating timer completes, then restores the old main-hand item. The goal is not interruptible during its short eat timer. While this goal is active, `PlayerNpcEntity.equipBetterGearFromInventory()` does not auto-swap the main hand, so food is not replaced by a weapon or tool before the heal is applied.

Regular food directly sets health to current health plus exactly 4 HP, capped at max health, and does not add absorption. Golden apples still use their stronger direct heal/effect behavior.

While eating, the NPC keeps moving but does not sprint. If it has a combat target, it either backs away or keeps chasing:

- It backs away from close or stronger threats.
- It keeps chasing when the target is weaker and already moving away or outside close melee distance.
- Combat eating movement uses a slower `0.65D` speed.
- Non-combat eating wander uses `0.55D`.

Initial eating movement creates one scoped `0.15F` path, then recalculates on the existing eight-tick cadence. Starting initializes that cadence after the initial path, avoiding a duplicate path on the first active tick. Healing remains an unwrapped safety goal.

Stopping after food was equipped calls `PlayerNpcEntity.setGapCooldown()`, which uses the entity's tick-down `gapCooldown` API. `PlayerNpcEntity.tick()` decrements that field every server tick.
