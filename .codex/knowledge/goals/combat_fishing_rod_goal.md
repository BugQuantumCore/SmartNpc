# CombatFishingRodGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/CombatFishingRodGoal.java`
- Registered only from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`, so it only runs when `epicfight_player_npc` is not loaded.

## Purpose

Adds vanilla-style combat fishing pressure for Player NPCs that have a fishing rod.

## Behavior

When the NPC has a current target at medium range and owns a fishing rod, it can temporarily put the rod in the offhand, swing it, play bobber throw/retrieve sounds, and pull the target toward itself. The rod loses durability when the pull happens. If the rod was taken from inventory, it is restored after the action.

Vanilla `FishingHook` requires a real `Player` owner, so this goal simulates the combat pull directly instead of relying on a bobber entity that would reject `PlayerNpcEntity`.

Inspector state is `ai.player_npc.combat_fishing`.

Cooldown uses `PlayerNpcEntity.combatFishingCooldown`.
