# ThrowEnderPearlGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/ThrowEnderPearlGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Lets Player NPCs use ender pearls in vanilla combat to approach distant targets, escape close threats while hurt, or randomly reposition during combat.

## Activation

- Server side only.
- Requires a live combat target.
- Requires ender pearl inventory supply and no pearl cooldown.
- Does not run while healing.
- Waits while `isClearingCombatObstruction()` reports the actual running obstruction goal,
  including nested tool crafting. Activation, continuation, equip/start and throw commit all
  respect this guard, so random reposition/chase/retreat pearls cannot abandon a breach.
  Dedicated water-pearl escape, healing and fleeing safety goals remain interruptible owners.

## Behavior

Consumes one ender pearl, temporarily renders it in the offhand, swings the offhand, throws a `ThrownEnderpearl`, starts cooldown, and restores the previous offhand item.
