# EscapeHoleWithBlockGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/EscapeHoleWithBlockGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets Player NPCs escape simple holes by jumping and placing a block at their feet.

## Behavior

When the NPC is boxed in by at least three two-block-tall collision barriers, has no adjacent walkable open body space, and has a normal placeable block, it jumps first, waits a few ticks until its body is clear of the target block space, then consumes one block, places it below itself, swings, and returns to idle.

The same goal also handles the cave return-home case where the navigation/home target is above the NPC, the route is blocked by a tall wall, and normal pathing is done or stuck. In that mode it first finds a nearby pillar column with clear body space upward to a step-out or sky-visible surface route. It chooses a random 16-32 block escape budget, gathers nearby stone/cobblestone/deepslate only with a usable pickaxe if it has too few blocks, then moves to that pillar column.

One-block obstacles or flat ground with an open adjacent exit must not trigger this goal because Player NPC can jump those normally. Do not re-add a simple "two-block block ahead" trigger unless it is also tied to reliable stuck/path intent.

The ordinary activation predicate must reject any open horizontal side before running `hasLocalWalkingEscape`. Being genuinely trapped ultimately requires all four lower horizontal sides to have collision; the older equivalent ordering ran the bounded 9x9x4 walking flood first even on open terrain. Since this safety goal is deliberately unwrapped and checked without a routine worker resource, that ordering multiplied collision reads across every idle NPC selector pass. Keep explicit upward/farm escape requests immediate and keep the goal outside routine scheduling; the cheap-side-first ordering changes only evaluation cost, not the trapped result.

The delayed placement prevents the block from being placed inside the NPC's bounding box before the jump lifts it clear.

Utility blocks such as crafting tables, chests, furnaces, beds, and torches are not used as pillar material for this escape.

A bed that physically occupies the active pillar/body column is a narrow clearance exception. The escape goal may break it even when it lies inside the home/build footprint, because otherwise the bed collision is invisible to pillar recovery and the NPC loops while shifting the pillar base. This exception does not extend to any other build block, and owned-farm destruction protection still applies to the target and its matching head/foot half. Occupied beds are not cleared. Bed breaking allows the bed block entity only for this validated target, uses normal `destroyBlock` neighbor updates so the paired half is removed, and emits loot from the struck half once; a no-drop cleanup removes a matching half only if it survives that vanilla update.

Emergency escape material mining re-equips a pickaxe before every mining pass, adds cobblestone or cobbled deepslate directly to the NPC inventory after breaking the source block, damages the pickaxe, uses the same crack overlay helper as other gradual mining goals, and plays the source block's hit sound during mining. The final break sound/effect comes from `ServerLevel.destroyBlock`, so it only plays after the source block is actually broken.

Escape-material stand selection must use the same physical reach geometry as runtime breaking: entity feet at the centre of the stand block to the target block centre must be within the squared break distance. Do not use integer `BlockPos` distance with extra slack; that can accept the NPC's current block as a completed path even though the target remains outside breaking reach.

If navigation still cannot enter reach, `PathStuckFallbackAi` watches the unchanged stand for five seconds and performs at most three physical step-off attempts. Each started recovery stops block breaking, restores the work hand, invalidates the stale material target/stand, and reacquires from the new feet position; it does not teleport or remove remembered temporary pillar supports. Its bounded landing search only reads already-loaded chunks. A 30-second total episode deadline also covers the case where no safe step-off exists. Exhaustion releases the upward request (or uses the exploration failed-climb handoff), applies a 30-second hole cooldown, and returns ownership to the interrupted mining/exploration goal. Per-tick `canContinueToUse()` must not rescan the escape-material volume; target acquisition stays in the running tick after the stale target is cleared.

While pillaring, the NPC temporarily equips a placeable block in the main hand, looks down at the placement position, uses a player-like pre-placement delay, places the block underneath after the jump clears the old feet space, and restores the previous hand item when the goal stops. The held block is counted as available escape material, and placement can use a fallback near the top of the jump so the NPC does not repeatedly jump without placing.

Pillar ascent must remain physical and visibly paced. Placement is allowed only when the live NPC bounding box is clear; pillar code must not approve a hypothetical collision-free position and then snap/teleport the NPC onto it. After placement, the NPC must fall onto that exact solid support. The dedicated emergency escape goal remains grounded there for 12 ticks before starting the next jump, while shared `PillarUpAi` uses two consecutive grounded ticks so routine resource, pickup, and return pillars do not pause excessively after every block. The active support column and the remembered temporary-support link down to natural ground are revalidated throughout either settlement window. If any support becomes replaceable, loses collision, or leaves a gap, ascent stops and retries through the bounded escape cooldown instead of continuing on a floating column.

Airborne placement readiness must be derived from the proposed block's real collision boxes against the live NPC bounding box. Do not use an early height threshold such as 0.65 or 0.95 blocks and then treat the expected body overlap as a terminal clipping failure: a normal 0.42 jump is still inside a full support cube at those heights. `EscapeHoleWithBlockGoal` waits only two post-jump ticks before checking the live clearance each tick; shared `PillarUpAi` performs its visible action delay before jumping, then checks clearance on every airborne tick. Expected overlap waits for the apex window, while actual ceiling obstruction still follows bounded blocker recovery.

Exploration may request this pillar mode only toward a genuine local surface stand. `ExploreAroundGoal` rejects stands supported by logs/leaves and rejects relaxed elevated candidates without at least one terrain-supported walk-off, preventing a tree trunk or isolated canopy column from being treated as a surface route. Missing-string fishing strolls never request this mode; a confirmed leaf collision in their navigation corridor belongs to shared foliage clearing instead.

Completed exploration supports remain marked temporarily for placement safety, but that memory must not delay `DescendHighColumnGoal`. Once the upward request has ended, the hole cooldown has elapsed, and the existing narrow-column, lower-terrain, home, farm, fluid, block-entity, and breakability checks pass, descent may remove the remembered support immediately instead of waiting for the 45-second support-memory expiry.

Cooldown uses `PlayerNpcEntity.holeEscapeCooldown`, decremented from the entity tick.

Route discovery and reachability checks are speculative safety diagnostics, not normal movement paths. They use a scoped 0.01 visited-node multiplier, test at most two route candidates per pass, and test at most two monotonic farm-egress intermediates. A live 209.5 ms NPC tick while this unwrapped goal was actively mining/pillaring showed that its former batches of raw `createPath` calls could bypass routine-worker bounds. Keep emergency escape available without a routine resource; bound its atomic path work instead.
