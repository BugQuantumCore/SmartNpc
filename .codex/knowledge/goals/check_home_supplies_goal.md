# CheckHomeSuppliesGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/CheckHomeSuppliesGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs check their own home chest and furnace once per Minecraft day before falling back to gathering/mining/exploring.

## Behavior

The goal is baseline AI and only runs when the NPC has a saved home, is near that home, has no combat target, and needs supplies such as food, wood, fuel, arrows, building blocks, or has furnace output waiting.

It checks the home chest first, then the home furnace. Each check is marked in persistent entity data by Minecraft day:

- `PlayerNpcLastHomeChestCheckDay`
- `PlayerNpcLastHomeFurnaceCheckDay`

These are daily check markers, not tick cooldowns. They reset naturally when `serverLevel.getDayTime() / 24000L` changes.

Chest behavior:

- walks to a usable adjacent stand position,
- opens/closes the chest,
- withdraws limited useful stacks such as food, logs/planks/sticks, coal/charcoal/fuel, arrows, building blocks, torches, utility blocks, buckets, and ore/ingot supplies,
- marks the chest checked even if it is missing or unreachable so resource AI does not loop on the same empty check.

Furnace behavior:

- walks to a usable adjacent stand position,
- takes output from the home furnace if present,
- marks the furnace checked even if it is missing, empty, or unreachable.

The goal does not replace `CookFoodGoal`; cooking still owns loading furnace input/fuel and temporary furnace placement/recovery.
