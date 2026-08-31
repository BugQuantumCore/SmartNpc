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
- A FARMING daily job enters stone support only after its farm plan reaches `GATHER_STONE`, and gathers the exact two-stone bootstrap needed for the hoe. It must not fall through into the generic full cobblestone target after that requirement closes.
- READY-farm planting, harvesting, or bone-meal work suppresses and preempts farming-support stone gathering even while `FarmCropGoal` is in its short completed-action cooldown. The initial `GATHER_STONE` bootstrap is unaffected because strict READY crop work is not yet actionable.
- Farm-lighting furnace stone is not requested while the NPC already has a valid tracked temporary furnace in the world. Consuming a carried furnace to place/load it must not make stone gathering restart while its charcoal is still smelting.

## Behavior

The goal selects nearby exposed stone or coal, chooses a same-Y adjacent stand when possible, walks to that stand, and mines with `BreakingBlockAi` so block hardness, tool speed, crack progress, sounds, main-hand animation, and durability damage are consistent with other mining work.

If the chosen stand cannot be reached because a local block obstructs the route, it starts `ClearBlockAi` with detail `clearing stone path @ x y z`, then resumes the original stone or coal target after the obstruction is removed.

Stone obstruction selection uses one total stand-path attempt per admitted pass across all clear candidates. Partial passes rotate to the next bounded candidate and remain queued rather than counting as failed stone access, while a successful pass reuses its selected approach path. Running clear recovery likewise advances through bounded stand candidates one admitted path per 20-tick retry without expanding into a fresh multi-path batch.

Path clearing should show break progress quickly once the NPC can actually hit the obstruction. Adjacent body-space blocks use multiple visible face/lower ray samples, not just the block center, so a worker in a tight shaft can start breaking the side block in front of its face. If the NPC is already at a centered break stand but the live eye ray still cannot hit the clear target, or if navigation remains idle while approach is supposedly running, `ClearBlockAi` treats that candidate as failed instead of waiting for the full clear timeout with no `N/Nt` progress. The clear detail includes `approaching N/160t` while the AI is positioning but has not yet started `BreakingBlockAi`. Access clearing must not choose the support block under the NPC's current feet or under the intended mining stand; those candidates make the worker try to dig out its own floor. It also should not clear the head block above an intended stand before the stand's foot/body block is open, because that can leave a shaft worker pressing its face into a block and failing every activation. Failed clear blocks are temporarily remembered across normal gather-goal restarts. If a clear target fails and no alternate clear candidate exists, the stone or coal target is marked temporarily blocked so the next activation does not choose the same impossible access route again.

The goal protects saved home/build volumes and the owned farm work volume, and tracks skipped access-clear blocks so one bad obstruction candidate does not keep being chosen for the same target. Farm protection includes the gate/entrance work area and a two-block buffered X/Z column from farm ground down to world minimum. If the worker is inside that area, it uses the existing bounded safe-egress relocation before selecting or clearing stone. Both the requested access blocker and any blocker retargeted by `ClearBlockAi` are revalidated, so a route cannot excavate farm support, irrigation, crops, fences, or the gate.

Auxiliary route clearing may break an obstruction already reachable from the NPC, but it does not construct a navigation path to a separate clear stand. The 13:40 runtime sampler still measured one such retained path at 118.8 ms in `GatherStoneGoal.tick` after lowering its node multiplier. If the clear target needs path work, GatherStone quarantines that obstruction and reselects a normal stone target/stand; this preserves ordinary mining and nearby clearing without allowing optional access recovery to consume a server tick.

For the remaining current-stand clear case, GatherStone sorts/deduplicates the geometrically gathered positions, evaluates expensive protection/shape/raycast checks for only the nearest 16, and starts the first valid obstruction directly. It does not filter/raycast the entire forced radius-4/y-range candidate volume or feed that collection back through `startNearest` stand/path selection. The 15:16 deployed trace still measured 49.2 ms in `GatherStoneGoal.tick` at `clearing stone path ... 0/29`; the bounded direct startup removes both redundant full-volume validation and accessible-stand selection while retaining the exact reach/protection checks for the selected block.
