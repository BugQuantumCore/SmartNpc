# LowHealthFleeGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/LowHealthFleeGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()` at priority `1`.

## Purpose

Lets low-health Player NPCs quit combat and sprint away instead of always standing and fighting.

## Activation

- Health ratio must be at or below 40%.
- Requires a living target.
- Does not run in water/lava or while mounted.
- If healing food is available, it sometimes declines so `EatHealingFoodGoal` can run at the same priority.

## Behavior

Clears the target, sets sprinting, pathfinds away from the threat, randomly jumps while fleeing, and marks AI state as `ai.player_npc.fleeing_low_health`.

Flee movement uses speed `1.0D`. Keep flee/avoid speeds at `1.0D`; do not raise them to `1.25D+`.

The initial escape target follows vanilla `PanicGoal`/random-away shape. Unlike vanilla's single retained path, this custom goal may adjust away from the moving threat on its controlled cadence, so every initial/recalculated route uses `PathNavigationAi.createBoundedPath(..., 0.15F)`. Starting the goal initializes the repath timer after the first route; it must not immediately create a duplicate path on the following tick.
