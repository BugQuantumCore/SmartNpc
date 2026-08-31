# CraftBasicGearGoal

Crafting-table travel has a distance-based liveness watchdog. Repeatedly submitting a path or direct movement request is not progress: if distance to the selected stand does not improve by at least 0.25 blocks for 80 ticks, the attempt counts as a route failure. Three failures use the existing safe climb/reposition recovery or finish into the normal retry cooldown. This is especially important for fishing workers caught in water, where `FloatGoal` and water bobbing can coexist with `navigation=done,path=none`; crafting must never retain MOVE indefinitely in that state.

Crafting-table movement must not report a horizontal direct step as successful when the selected stand is more than one block above/below the NPC. After three failed bounded route attempts, the goal first gives shared `ClearBlockAi` one bounded local obstruction candidate at a time. It may clear a physical, breakable route block only when that block is loaded, has no block entity, is not the table/support/standing floor, is not a temporary pillar, and is outside protected farm and saved build footprints. Non-colliding decoration such as the torch reported by the trace is not mistaken for a route wall.

If no safe route block can be cleared and the destination is higher, crafting releases its MOVE/LOOK flags and requests a valid local forced-climb waypoint two blocks above the NPC. The local waypoint matters: using the remote table stand at only `feetY + 1` does not meet the shared escape goal's forced-climb contract and caused a request/discard/reacquire loop. Crafting cannot reacquire while its retained handoff exists, and the handoff is not charged as a failed craft attempt. Wet NPCs do not receive a land-pillar request (the entity correctly clears those); they fall through to `PathStuckFallbackAi`. A same-level or wet failure uses `PathStuckFallbackAi` to move to a safe stand outside protected build/farm space and recomputes the crafting stand. Protected, unbreakable, or block-entity obstructions therefore fall through to safe repositioning rather than being destroyed or making the goal oscillate forever. These recovery paths are part of the admitted worker's full routine; they do not add a secondary worker restriction.

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/CraftBasicGearGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs convert gathered wood, cobblestone, sticks, and string into basic tools and weapons.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Requires enough inventory materials and missing useful gear.
- Cooldown uses `PlayerNpcEntity.craftGearCooldown`.
- Registered above material gathering priority so a starter axe/pickaxe craft can run before the initial resource route. Once a log-gathering tree/pillar episode is active, non-emergency crafting defers until that episode finishes. A genuine underground/upward-escape pickaxe craft remains allowed to interrupt.
- If an underground/upward escape or dig-down state is active and the NPC has no carried pickaxe, the goal can enter emergency pickaxe crafting instead of staying blocked by the escape state.

## Behavior

Crafting is progression-based. If the NPC is close to its saved home, it does not place a temporary crafting table; it lets return-home/home management bring it back to the home table or create one there. Away from home, it reuses a carried crafting table when possible and only crafts a new temporary table when it is not already carrying one, does not already have a reusable temporary table recorded, and still has enough remaining materials to craft a tool after the table cost.

The goal is a ticking crafting sequence, not a one-tick inventory mutation. It records a concrete crafting table position, moves to a reachable adjacent stand position, faces the table, swings once for the initial table interaction, then performs recipe steps with short delays and sound feedback without repeating the hand swing for every inventory conversion. It can reuse its persisted temporary crafting table outside the normal five-block scan if the table is within the reuse distance and has a reachable stand; if the recorded table is gone, too far away, or has no reachable stand, the goal clears the stale tag so a new temporary table can be placed. New temporary table placement scans a small nearby area, requires a reachable stand, walks to that stand before placing, and rejects placements that would clip the NPC. Do not reintroduce direct tool creation from `start()` or allow crafting from five blocks away just because a table is in scan radius.

After a table is nearby, including one it just placed, it crafts one useful item per activation. Axe and pickaxe are critical starter tools and must gate early progression: if either is missing but cannot be crafted, the goal does not downgrade into crafting shovel, sword, or fishing rod instead. For a pure mining workday after the log supply target is met, a missing pickaxe is preferred before shovel work so the miner can enter the stone phase. If another critical starter tool can still be crafted after one activation, the cooldown is only a few ticks so the NPC can quickly progress from table/axe to wooden pickaxe before attempting stone or cobblestone work. Once axe and pickaxe exist, it upgrades wooden pickaxe/axe/sword/shovel to stone when cobblestone or cobbled deepslate and sticks are available, then fills missing wooden shovel, sword, or fishing rod.

Farming support has its own narrow ordering. During farm bootstrap the NPC crafts the pickaxe needed for its two-stone target, skips the normally useful shovel so those two stones cannot be consumed, then crafts a hoe before optional gear. While the persisted farm plan is incomplete, optional upgrades remain suppressed after the critical tools and hoe are satisfied. A READY farming workday also suppresses optional gear: it may request a hoe only when an exposed crop cell actually needs tilling, or an axe/pickaxe for a concrete farm log/stone support demand. A healthy fully planted farm therefore yields to crop idle/stroll behavior if its old hoe is absent. Emergency escape pickaxe crafting remains allowed.

Emergency pickaxe crafting is intentionally narrow. When the NPC is trying to escape upward, waiting on hole escape cooldown, marked as `ai.player_npc.digging_down_for_stone`, or marked as `ai.player_npc.pillaring_up` outside an active log-gathering episode, and no carried pickaxe exists, the next critical starter recipe prefers a pickaxe first and may use logs without preserving the normal reserve. This prevents broken-pickaxe mining or escape pillar-up states from deadlocking the NPC underground without treating an ordinary tree pillar as an emergency.

Recipes go through explicit `PlayerNpcCraftingUtil` steps so logs are converted into planks first, planks are converted into sticks when needed, and crafting tables consume four planks instead of four logs. While at the table, the current implementation converts carried logs to planks only as needed before producing the tool item, so the inventory transition resembles vanilla recipes instead of instantly creating gear from raw logs. Stone versions are preferred when cobblestone/cobbled deepslate is available; otherwise wooden tools are crafted from planks and sticks. Crafted gear is passed through `PlayerNpcEntity.equipBetterGearFromInventory()` so a new stone tool can replace a wooden main-hand tool immediately when it scores better.

Crafting-table preparation uses the same raw-log reserve exception as the selected recipe, including a legitimately required farm hoe. If a preflighted craft still finishes without producing an item because its station or materials changed, a separate bounded failure retry gate prevents critical-tool cooldown bypass from immediately restarting the same table-preparation episode.
