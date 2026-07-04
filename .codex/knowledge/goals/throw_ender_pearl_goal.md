# ThrowEnderPearlGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/ThrowEnderPearlGoal.java`
- Registered from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`.

## Purpose

Lets Player NPCs use ender pearls in vanilla combat to approach distant targets, escape close threats while hurt, or randomly reposition during combat.

## Activation

- Server side only.
- Requires a live combat target.
- Requires ender pearl inventory supply and no pearl cooldown.
- Does not run while healing.

## Behavior

Consumes one ender pearl, temporarily renders it in the offhand, swings the offhand, throws a `ThrownEnderpearl`, starts cooldown, and restores the previous offhand item.

