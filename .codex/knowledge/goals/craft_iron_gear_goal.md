# CraftIronGearGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/CraftIronGearGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets Player NPCs turn smelted iron ingots and diamonds into better tools and armor.

## Behavior

Requires a nearby crafting table, no combat target, and enough material/stick ingredients. This is baseline AI for every Player NPC, not gated by mining or hunting interests.

The goal crafts one item per activation. It tries diamond pickaxe, sword, axe, and shovel first, then diamond armor, then iron pickaxe, axe, sword, shovel, and iron armor. Tool crafting uses `PlayerNpcGearUtil` tier checks, so it only crafts a tool when that target tier is better than the best matching tool the NPC already owns. This prevents crafting an iron pickaxe after the NPC already owns a diamond pickaxe.

Armor is equipped directly if it improves the slot; replaced armor is moved into inventory or dropped if inventory is full. Tools are placed into the custom inventory and then passed through `PlayerNpcEntity.equipBetterGearFromInventory()` so the best main-hand gear can be equipped immediately.

Uses `PlayerNpcCraftingUtil` for plank-to-stick handling, recipe matching, and material consumption.

Cooldown uses `PlayerNpcEntity.ironGearCooldown`.
