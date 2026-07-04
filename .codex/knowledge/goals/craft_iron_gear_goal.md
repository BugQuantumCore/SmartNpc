# CraftIronGearGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/CraftIronGearGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets Player NPCs turn smelted iron ingots into better tools and armor.

## Behavior

Requires a nearby crafting table, no combat target, and enough iron/stick materials. The goal crafts one item per activation, preferring iron pickaxe, axe, sword, shovel, then iron armor upgrades. Armor is equipped directly if it improves the slot; replaced armor is moved into inventory or dropped if inventory is full.

Uses `PlayerNpcCraftingUtil` for plank-to-stick handling and iron ingot consumption.

Cooldown uses `PlayerNpcEntity.ironGearCooldown`.
