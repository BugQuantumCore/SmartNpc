# FarmCropGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/FarmCropGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Adds farming behavior for Player NPCs when `FARMING` is the selected daily job.

## Behavior

The goal can create and maintain an owned farm area near the NPC home. For NPCs that also have `BUILDING`, farming waits until a base/home layout has been chosen, but it no longer requires the whole house to be finished.

- Farm area is persisted in the NPC persistent data.
- The farm is placed outside the saved home footprint.
- The farm perimeter uses fences with one open fence gate facing toward the home.
- The NPC can till dirt/grass into farmland if it has a hoe.
- The NPC can plant wheat, carrots, potatoes, and beetroot from inventory.
- Mature crop harvesting first checks the owned farm, then nearby mature crops around the NPC, including village-style crop patches. Harvesting waits briefly, swings, and sends vanilla block crack progress through `PlayerNpcEntity.showBlockBreakProgress` before destroying the crop.
- The NPC can craft bone meal from bones and use bone meal on farm crops.
- `CraftCropFoodGoal` handles bread crafting from wheat.
- Potato cooking is handled by the general `CookFoodGoal`, which treats raw potatoes as cookable food.

The NPC moves into range before placing fences/gates, harvesting, bone-mealing, or planting. The goal is idle-only and uses `PlayerNpcEntity.farmCooldown`.

## Not Implemented

The current farming goal does not have a full MineColonies-style field assignment/request system and does not intentionally route to distant village farms. It only harvests external mature crops already near the NPC.
