# Cautious Avoid Threat Goal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/CautiousAvoidThreatGoal.java`
- `src/main/java/com/pla/smart_npc/entity/ai/CautiousThreatAi.java`
- Registered at priority 1 from `PlayerNpcEntity.registerGoals()` behind the `CAUTIOUS` interest.

## Behavior

The goal makes a cautious NPC avoid survival/adventure players, hostile mobs, other Player NPCs,
and configured compatibility threats. Creative and spectator players are ignored. It owns
MOVE/LOOK, clears an attack target, sprints along one path selected away from the nearest valid
threat, and stops once the navigation finishes, the threat becomes invalid, or the threat is more
than 20 blocks away. Low-health flee, healing, and normal combat are separate goals.

`CautiousThreatAi` is the authoritative predicate shared by avoidance, scared hiding, and rare
sneaking. A valid threat is a survival/adventure player, another Player NPC, a hostile/monster
entity, a mob currently targeting the NPC, or a configured compatibility threat. Creative and
spectator players are always ignored.

`RareSneakGoal` and `ScaredHideGoal` activate only after a throttled scan selects a valid cached
threat. While active they hold crouch and face that entity without rescanning; they stop when it
becomes invalid, leaves the goal's configured range, or combat begins.

## Performance Contract

Threat discovery and escape-path creation are activation-only decisions. `CanUseThrottle` gives
each NPC a randomized 20-30 tick activation cadence, and one activation creates at most one path,
matching vanilla `AvoidEntityGoal`'s bounded shape. `canContinueToUse()` contains only current
entity/navigation/distance guards. The active per-tick path may update look direction and vanilla's
near/far speed modifier, but it must never scan entities, call `DefaultRandomPos`, or create/restart
a path.

Do not repath merely because navigation is done from `tick()`. That keeps an unreachable partial
path alive indefinitely and can turn one cautious NPC into repeated entity scans plus pathfinding.
Let continuation stop the episode, then allow a new randomized activation check after the throttle.
