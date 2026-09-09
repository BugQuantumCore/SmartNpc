# EatHealingFoodGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/EatHealingFoodGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Lets Player NPCs swap healing food into the main hand, play eating behavior, heal or regenerate, then restore their previous held item.

## Activation

- Server side only.
- NPC health must be below 70%.
- NPC must not already be healing.
- `PlayerNpcEntity.getGapCooldown()` must be `0`.
- Requires a valid healing food item in the Player NPC inventory.

## Behavior

Consumes one food item from the inventory, shows it in the main hand, plays eating sounds and item use, applies the food result after 32 server ticks, then restores the old main-hand item through the guarded AI setter. Epic Fight uses one looping EAT_MAINHAND composite animation, stopped on completion or abort; repeated attack swings are not sent. The goal is not interruptible during its short eat timer. While this goal is active, `PlayerNpcEntity.equipBetterGearFromInventory()` does not auto-swap the main hand, so food is not replaced by a weapon or tool before the heal is applied. Vanilla `completeUsingItem` defers consumption while healing so the goal remains the sole owner of food consumption and effects. Missing/replaced food, no-AI, riding, or death aborts; stop is idempotent and drops only inventory remainders.

The Epic Fight attack/chase goals yield their LOOK/MOVE controls to eating and cancel the
interrupted combat animation. Patch guard/attack eligibility also rejects healing/item use.
`start()` synchronizes the separately registered `EpicFightCloneAnimations.EAT_MAINHAND`
StaticAnimation and its own `smart_npc:biped/living/eat_mainhand` asset and composite metadata.
Do not use native BIPED_EAT or MirrorAnimation for this mob. The animation loops with fixed-head
rotation and an upper-body mask. Healing is synchronized entity data, owned by the eating goal;
client motion uses that state and the held edible item rather than depending on vanilla use-item
packet timing. Server upkeep and `EpicFight.updateClientEatingAnimation` inspect the actual
standalone animation player and restart only a missing/interrupted loop. The client repairs the
composite EAT binding after equipment living-motion resets, in which ClientAnimator's
separate composite map is not included in `getLivingAnimations()`. Item-use end stops the actual
standalone animation locally; a healthy loop or its link transition is never restarted each tick.
Calls go directly to `compat.epicfight.EpicFight` behind an
`epicfight` `ModList` guard. Without Epic Fight, vanilla item-use rendering remains active.

Regular food directly sets health to current health plus exactly 4 HP, capped at max health, and does not add absorption. Golden apples still use their stronger direct heal/effect behavior.

While eating, the NPC keeps moving but does not sprint. If it has a combat target, it either backs away or keeps chasing:

- It backs away from close or stronger threats.
- It keeps chasing when the target is weaker and already moving away or outside close melee distance.
- Combat eating movement uses a slower `0.65D` speed.
- Non-combat eating wander uses `0.55D`.
- CAUTIOUS NPCs select a nearby threat only on the existing movement planning cadence and always
  retreat while eating; they never select the eating chase branch.

Initial eating movement creates one scoped `0.15F` path, then recalculates no more often than every 20 server ticks. Only the timer, sounds, and immediate safety checks run every tick. Starting initializes the path cadence after the initial path, avoiding a duplicate path on the first active tick. Healing remains an unwrapped safety goal.

Stopping after food was equipped calls `PlayerNpcEntity.setGapCooldown()`, which uses the entity's tick-down `gapCooldown` API. `PlayerNpcEntity.tick()` decrements that field every server tick.
