# CraftBasicGearGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/CraftBasicGearGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs convert gathered wood, cobblestone, sticks, and string into basic tools and weapons.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Requires enough inventory materials and missing useful gear.
- Cooldown uses `PlayerNpcEntity.craftGearCooldown`.

## Behavior

Crafting is now progression-based. If the NPC needs basic gear and has enough planks but no nearby crafting table, it places a crafting table first. After a table is nearby, it crafts one useful item per activation, preferring pickaxe, axe, shovel, sword, then fishing rod.

Recipes go through `PlayerNpcCraftingUtil` so logs are converted into planks first, planks are converted into sticks when needed, and crafting tables consume four planks instead of four logs. Stone versions are preferred when cobblestone/cobbled deepslate is available; otherwise wooden tools are crafted from planks and sticks. The cooldown is short so an undergeared NPC can progress from logs to planks to table to wooden pickaxe to stone gear without waiting a long time between steps.
