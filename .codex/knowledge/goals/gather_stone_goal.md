# GatherStoneGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/GatherStoneGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Collects stone-family blocks for building/mining/fishing supply work and opportunistic coal for fuel/torch reserve.

## Activation

- Server side only.
- NPC must be alive, not healing, not a passenger, not no-AI, and have no combat target.
- Requires a carried pickaxe.
- Uses `GatherStoneGoal.isStoneSupplyPhaseActive(...)`.
- Pure `MINING` and `FISHING` daily jobs can gather stone without a saved home/base. Building-driven stone work still waits for the prepared-base path.
- While a FISHING daily job is in this active stone-support phase, `CraftBasicGearGoal` must only interrupt for a missing pickaxe. All non-pickaxe crafting waits until the support stone target is met.

## Behavior

The goal selects nearby exposed stone or coal, chooses a same-Y adjacent stand when possible, walks to that stand, and mines with `BreakingBlockAi` so block hardness, tool speed, crack progress, sounds, main-hand animation, and durability damage are consistent with other mining work.

If the chosen stand cannot be reached because a local block obstructs the route, it starts `ClearBlockAi` with detail `clearing stone path @ x y z`, then resumes the original stone or coal target after the obstruction is removed.

Path clearing should show break progress quickly once the NPC can actually hit the obstruction. Adjacent body-space blocks use multiple visible face/lower ray samples, not just the block center, so a worker in a tight shaft can start breaking the side block in front of its face. If the NPC is already at a centered break stand but the live eye ray still cannot hit the clear target, or if navigation remains idle while approach is supposedly running, `ClearBlockAi` treats that candidate as failed instead of waiting for the full clear timeout with no `N/Nt` progress. The clear detail includes `approaching N/160t` while the AI is positioning but has not yet started `BreakingBlockAi`. Access clearing must not choose the support block under the NPC's current feet or under the intended mining stand; those candidates make the worker try to dig out its own floor. It also should not clear the head block above an intended stand before the stand's foot/body block is open, because that can leave a shaft worker pressing its face into a block and failing every activation. Failed clear blocks are temporarily remembered across normal gather-goal restarts. If a clear target fails and no alternate clear candidate exists, the stone or coal target is marked temporarily blocked so the next activation does not choose the same impossible access route again.

The goal protects saved home/build volumes and tracks skipped access-clear blocks so one bad obstruction candidate does not keep being chosen for the same target.
