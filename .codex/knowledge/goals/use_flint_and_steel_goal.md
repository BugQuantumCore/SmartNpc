# UseFlintAndSteelGoal

Registered at priority 4 with interest gating and MOVE/LOOK controls. Selects a
valid fire placement at its grounded combat target's feet, temporarily equips flint and
steel through the guarded AI hand setter, and revalidates the placement at commit.

Offensive ignition waits while `isClearingCombatObstruction()` reports an actual running
obstruction goal, including its nested tool crafting. Activation, target/start/commit checks,
continuation and tick honor this guard; display strings do not establish ownership.

The target must be within three blocks (squared entity distance <=9), grounded, and at or below
the NPC's actual Y. These guards are checked at selection, start, and delayed ignition. Fire
is placed only in the target's selected feet cell; moving out of that cell or jumping during
the equip delay aborts the attempt. No adjacent-cell fallback ignites behind a moving target.
Failed selection retries use the staggered >=20-tick activation throttle.

Only successful fire placement emits one main-hand use signal, charges durability,
and starts the persisted flint cooldown, uniformly randomized from 1200 through 3600 server ticks
(one to three minutes at 20 TPS). The real flint is equipped before ignition and remains
equipped for a two-server-tick synchronization lead-in before ignition, then remains equipped
for 20 server ticks after the successful commit, using an absolute entity-tick deadline
so reduced-rate goal ticks do not double the hold. MOVE/LOOK ownership remains active for the
hold. `stop()` restores the cached hand exactly once on completion or safety preemption; failed
placement restores immediately. Returning a held stack uses the inventory-mutating overload
and drops only the remainder. The delayed commit revalidates the retained live combat target,
target/placement reach, target proximity to the selected fire cell, held tool, loaded/world-border
bounds, home/build footprint, farm protection, and absence of block entities. It aborts if the
target changed or has already ignited. Candidate reads require loaded chunks, including supporting blocks.

Epic Fight attack/chase yield to the goal controls, cancelling the owned combat
animation before placement; remaining USE_MAINHAND recovery also blocks attacks
after this short goal stops. Generic code calls the guarded `EpicFight` API through the entity
use helper, preserving the vanilla swing fallback when Epic Fight is absent.
