# CookFoodGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/CookFoodGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs place/use a home furnace for basic cooking and ore smelting.

## Behavior

If the NPC has a home and no furnace, it can place a furnace from inventory or craft one from eight cobblestone/cobbled deepslate.

If a home furnace exists, the goal can:

- take cooked output from the furnace,
- insert one raw food item,
- insert one smeltable raw ore or ore block,
- insert one valid furnace fuel item such as coal, charcoal, or wood.

The goal is idle-only and does not interrupt combat.

Cooldown uses `PlayerNpcEntity.cookFoodCooldown`.
