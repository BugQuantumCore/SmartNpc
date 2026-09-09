# Combat tool crafting during obstruction recovery

`CombatToolCraftAi` is owned by `BreakTargetObstructionGoal`; the goal keeps MOVE/LOOK and its
combat lock throughout crafting. This is a short sub-action before mining, available with vanilla
or Epic Fight combat, rather than admitting routine job gathering during a fight.

When the selected obstruction lacks a preferred pickaxe, axe or shovel, the helper may make one
from carried ingredients. It considers diamond, iron, stone, then wood through real crafting
recipes. Missing sticks and planks may be produced from carried wood. All recipe attempts run
on inventory copies with capped log conversions, shared admission and a 30–40 second retry
deadline retained across goal stops. No missing ingredient search or travel is started.
Shared-budget denial is pending work: wait 20–25 ticks without starting mining, retry at most
three times, then fall back to slow mining. It must not be mistaken for missing materials after
the owning goal just used the current tick's path budget.

It uses a visible crafting table within immediate reach (27 loaded cells checked), or places a
carried/craftable table in one of four supported adjacent cells outside the forward approach.
Placement checks loaded chunks, bounds, farm/build protection and entity collision again at
commit. Existing temporary-table ownership is not overwritten while its table remains valid.
A placed table uses the standard CraftBasicGearGoal ownership coordinates for later cleanup.

The entire table-plus-tool material plan must succeed on a copy before starting. Table and tool
commits replan against the current inventory; failed speculation never consumes real materials.
The table is visibly equipped for placement using ToolAi and restored; the tool craft follows
after a 12-server-tick delay and the miner then selects the resulting tool normally. Existing-table
crafting also waits 12 ticks and rechecks visibility/reach. Completion emits one use signal.
Preemption stops the short action and restores hands; an already placed table remains owned.
If materials, space or table access are unavailable, obstruction mining proceeds at the normal
slower speed without the preferred tool. The helper does not create free tools or free drops.
