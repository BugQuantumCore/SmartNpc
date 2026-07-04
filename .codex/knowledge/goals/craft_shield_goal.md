# CraftShieldGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/CraftShieldGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets resource-oriented Player NPCs decide to craft a shield.

## Behavior

When idle near a crafting table, without already having a shield, the NPC can consume six plank equivalents and one iron ingot to create a vanilla shield. Log-to-plank conversion is delegated to `PlayerNpcCraftingUtil.tryConsumePlanks`, so logs become planks before the shield recipe consumes them.

Inspector state is `ai.player_npc.crafting_shield`.

Cooldown uses `PlayerNpcEntity.shieldCraftCooldown`.
