# CallForHelpGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/CallForHelpGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets a Player NPC call for help when another mob is targeting it and the NPC does not want to fight that threat.

## Activation

- Server side only.
- NPC must have no current target.
- A nearby mob must currently target this Player NPC.
- The NPC must be low enough health or outmatched by the threat.
- Uses `PlayerNpcEntity.helpAlertCooldown`; do not re-add `PlayerNpcHelpAlertCooldown` persistent-data tags.

## Behavior

Broadcasts a randomized help message through `ChatUtil`, raises a short-lived alert through `PlayerNpcAlertManager`, and marks AI state as `ai.player_npc.calling_for_help`.

This goal does not move the NPC. Alert avoidance movement is handled by `RespondToNpcAlertGoal`, which uses speed `1.0D`.
