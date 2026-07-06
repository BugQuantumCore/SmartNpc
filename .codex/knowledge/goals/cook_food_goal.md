# CookFoodGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/CookFoodGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs use furnaces for basic cooking, ore smelting, and cobblestone-to-stone processing.

## Behavior

`CookFoodGoal` is baseline AI, not interest-gated.

If the NPC has a home and no furnace, it can place a furnace from inventory or craft one from eight cobblestone/cobbled deepslate when it has food or smeltables plus fuel.

If the NPC has no nearby home furnace, it can place a temporary furnace near itself, use it, and later recover it after the furnace input/fuel/output slots are empty. Temporary recovery walks to the furnace, equips a pickaxe when available, shows vanilla block break progress, then returns a furnace item to the custom inventory.

If a furnace exists, the goal walks to an adjacent usable stand position before interacting. It can:

- take cooked output from the furnace,
- insert one raw food item,
- insert one smeltable raw ore, ore block, cobblestone, or cobbled deepslate,
- insert one valid furnace fuel item such as coal, charcoal, or wood.

The goal is idle-only and does not interrupt combat.

Cooldown uses `PlayerNpcEntity.cookFoodCooldown`.
