# PlayerNpcSmartTargetGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/PlayerNpcSmartTargetGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Replaces hard-locked spawn personalities with a scoring target selector.

## Target Pool

- Players
- Player NPCs
- Monsters and illagers
- Villagers
- Animals

## Decision Inputs

- Current random personality bias.
- Personality bias is selected uniformly; old server config target-weight keys are no longer used.
- NPC health ratio.
- Candidate distance.
- Candidate type.
- Player/Player NPC gear, armor, enchantment, attack damage, and health.
- Whether the NPC needs food.

## Behavior

Selects a target only when the score is positive and the NPC is not clearly outmatched. Players and Player NPCs are treated as the same class of target for gear-risk checks.

If the current target is dead, removed, out of range, or no longer attackable, the goal clears it immediately before the 20 tick scan interval check so idle worker goals such as gathering, biome log search, building, and mining can run on the next goal pass. Failed scans no longer write `target scan: none` to the inspector detail. When a target is selected, the detail shows the chosen target name.

Animals are only selected when the NPC lacks healing food. Villagers require villager/hostile personality bias and still use a very low attack chance, so NPCs should not commonly destroy villages.
