# BreakTargetObstructionGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/BreakTargetObstructionGoal.java`
- Registered directly at priority 5 from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets a combatant break a local block obstructing its current living target. This is combat recovery, not routine job work, and must remain available without `StartupWorkGatedGoal` ownership.

## Performance Contract

Activation is throttled to a randomized 10-15 tick cadence. Line-of-sight and high-target reachability share one scoped `0.15F` target-path probe; do not call two identical target paths for the normal reach test and pillar decision. The obstruction raycast is one direct eye-to-target clip followed by a fixed local set of at most nine blocks, not a volume scan.

Approach stand discovery creates at most one scoped path per 12-tick repath. A failed stand advances a retained cursor through the fixed candidate set on later intervals. A successful path is passed directly to navigation; do not discard it and invoke coordinate `moveTo`, which would create another synchronous path. A current usable stand uses `MoveControl` centering without pathfinding.
