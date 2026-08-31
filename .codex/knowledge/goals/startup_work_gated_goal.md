# Startup Work Gated Goal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/StartupWorkGatedGoal.java`
- `src/main/java/com/pla/smart_npc/entity/PlayerNpcEntity.java`

## Contract

`StartupWorkGatedGoal` is the central server-startup wrapper for routine Player NPC work. It checks
the server tick before calling the delegate's `canUse()` or `canContinueToUse()`. The minimum grace
is 60 ticks, followed by a stable 0-60 tick UUID-derived per-NPC offset.

Worker ownership does not authorize evaluating the entire idle routine catalog in one selector
pass. Registrations are assigned round-robin to four predicate slices. Each NPC advances its slice
exactly once on an actual resource-owned GoalSelector opportunity, so every repeated probe turn
eventually covers every registration while only about one quarter of the catalog is eligible per
pass. Do not derive the slice from absolute `tickCount`: GoalSelector's three-tick new-goal cadence
and the multi-NPC scheduler queue can grant one NPC only a repeating subset of modulo phases, which
previously starved Gigabit101's GatherLogs check while it visibly received probe turns. Interest-
gated goals consume their slice before the cheap interest rejection, preventing inactive early
registrations from biasing later jobs. Running delegates bypass activation slicing and continue
normally.

The gate is based on the current server process tick and is never saved to entity NBT. NPCs spawned
after startup therefore work immediately. Goal running state is not persisted by vanilla, and the
continuation guard defensively stops any work lifecycle that could otherwise exist during the grace.

Only routine work is wrapped: pickup/supply, home/build, crafting/cooking, gathering, mining,
farming, fishing, exploration, and other non-emergency job support. Target
selection, melee/ranged combat and combat utilities, low-health flee/heal, floating, cautious
avoidance/hiding, calls for help, hole/high-column escape, combat weapon recovery, obstruction
breaking, LOOTING chest behavior, TROLL_HIT behavior, and door traversal remain registered directly
and can react from the first server tick. Expensive unwrapped characteristic scans and paths must
carry their own cursor/node bounds; they must not be put back behind routine worker ownership.

Diagnostics must recursively unwrap both `StartupWorkGatedGoal` and `InterestGatedGoal`, so traces
and TPS snapshots continue to show the actual delegate class rather than wrapper names. Performance
warnings report both the hottest wrapped phase and aggregate `wrappedGoalTotalMs`/`calls`, allowing
live verification that selector slicing reduced the catalog-wide cost.
Routine worker ownership is an NPC-level Minecraft-day shift shared by every wrapped job goal, not the lifetime of one delegate. A started worker retains the resource across Terraform, clearing, material gathering, BuildHouse, and `runningGoals==0` transition gaps. At overworld time 1000 (about 07:00, one in-game hour after dawn/sleep wake-up), the current roster is appended behind existing waiters and new eligible holders are admitted in round-robin order. If no other NPC is eligible, an old holder may reacquire instead of leaving capacity unused. A fresh server scheduler gives its initial roster a full shift before its first rotation; backwards `/time` changes establish a new future boundary, while forward sleep/time jumps rotate once at the next safe morning. Death/removal and a disabled or reduced worker limit release immediately. There is intentionally no short idle timeout: a wrapper returning false may mean a temporary prerequisite/cooldown, not reliable completion of the overall job. Combat, fleeing, healing, cautious avoidance, and other unwrapped safety/characteristic goals remain independent and can preempt normally.

Holding a worker resource grants normal access to the complete routine goal selector; it does not make the currently running delegate exclusive. Other wrapped routine predicates remain available and Minecraft's priorities/flags arbitrate GatherLogs, ExploreAround, Terraform, BuildHouse, farming, and other transitions normally. Their per-goal scan/path budgets and cadences bound heavy operations. Unwrapped combat and safety selectors remain independent.

The scheduler state model is explicit: an active worker has full normal bounded job AI, while a non-holder has no routine job AI and may use the cheap waiting stroll. The secondary one-batch queue applies only to probe owners that are trying to start/earn a worker; it must not deny `tryAcquire` to an active worker or let one routine predicate steal permission from another. Both holders and non-holders retain all unwrapped characteristic targeting (mobs, players, villagers), cautious/avoid/flee behavior, combat, and safety goals.
