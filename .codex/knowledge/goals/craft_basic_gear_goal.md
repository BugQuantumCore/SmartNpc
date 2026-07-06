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
- Registered above material gathering priority so it can run before or interrupt gather when a critical starter craft is ready.

## Behavior

Crafting is progression-based. If the NPC is close to its saved home, it does not place a temporary crafting table; it lets return-home/home management bring it back to the home table or create one there. Away from home, it reuses a carried crafting table when possible and only crafts a new temporary table when it is not already carrying one, does not already have a valid temporary table recorded, and still has enough remaining materials to craft a tool after the table cost.

The goal is a ticking crafting sequence, not a one-tick inventory mutation. It records a concrete crafting table position, moves to a reachable adjacent stand position, faces the table, then performs visible recipe steps with a short delay and swing/sound feedback. Do not reintroduce direct tool creation from `start()` or allow crafting from five blocks away just because a table is in scan radius.

After a table is nearby, including one it just placed, it crafts one useful item per activation. Axe and pickaxe are critical starter tools and must gate early progression: if either is missing but cannot be crafted, the goal does not downgrade into crafting shovel, sword, or fishing rod instead. If another critical starter tool can still be crafted after one activation, the cooldown is only a few ticks so the NPC can quickly progress from table/axe to wooden pickaxe before attempting stone or cobblestone work. Once axe and pickaxe exist, it upgrades wooden pickaxe/axe/sword/shovel to stone when cobblestone or cobbled deepslate and sticks are available, then fills missing wooden shovel, sword, or fishing rod.

Recipes go through explicit `PlayerNpcCraftingUtil` steps so logs are converted into planks first, planks are converted into sticks when needed, and crafting tables consume four planks instead of four logs. While at the table, the current implementation converts carried logs to planks only as needed before producing the tool item, so the inventory transition resembles vanilla recipes instead of instantly creating gear from raw logs. Stone versions are preferred when cobblestone/cobbled deepslate is available; otherwise wooden tools are crafted from planks and sticks. Crafted gear is passed through `PlayerNpcEntity.equipBetterGearFromInventory()` so a new stone tool can replace a wooden main-hand tool immediately when it scores better.
