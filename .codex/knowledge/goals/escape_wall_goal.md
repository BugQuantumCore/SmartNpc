# EscapeWallGoal

`src/main/java/com/pla/smart_npc/entity/goal/EscapeWallGoal.java` frees a Player NPC whose live
body intersects a solid block, such as after an ender pearl teleport into a wall. Register it
directly as emergency safety work at priority 0, outside the routine worker/startup wrapper.
It owns MOVE, LOOK, and JUMP, so work and attack goals yield while it breaks the obstruction.
The attack target remains intact throughout recovery.

Activation uses a randomized >=20-tick probe and the shared optional navigation admission slice
without taking a routine worker lease. A cheap actual-overlap test runs before admission so
unobstructed NPCs cannot consume the path slot; selected-block safety/ownership validation then
shares one admitted slice. It inspects at most 20 cells covering the NPC's body,
including one cell below for tall fence shapes. The eye block is preferred. Real collision
shapes must intersect the body deflated by 0.02 blocks; ordinary floor/wall contact and the
empty portion of a partial block do not qualify. No distant blocker search, navigation, or
chunk loading occurs.

Every mining tick revalidates the exact selected state, current body collision, loaded/world
border bounds, hardness, block-entity exclusion, home/build footprint, farm protection, and
temporary crafting table ownership. Crops are excluded. Fluid-containing targets, adjacent
fluids/unloaded neighbor chunks, and falling blocks immediately above are unsafe and rejected.
These protection rules also apply when combat or suffocation prompted the escape.

`BreakingBlockAi` owns normal tool-dependent timing, crack progress, sound, digging animation,
durability and drops. It already supports mining an exact selected block without a separate
eye-to-outline visibility ray, so an eye inside the target requires no generic helper bypass.
`ToolAi` restores the previous main hand on completion, failure, or preemption. Death, no-AI,
passenger state, target replacement, or loss of body overlap aborts the current break.

One activation clears at most one block and has a 31-second timeout, covering the shared
helper's maximum 30-second break duration. Another overlapping block requires a fresh admitted
selection after 20-30 ticks. The full-rate running path never scans for a replacement block or
creates a route. Inspector state uses the existing `breaking_target_obstruction` translation
with `freeing occupied body space` detail.
