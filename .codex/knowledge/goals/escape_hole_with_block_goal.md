# EscapeHoleWithBlockGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/EscapeHoleWithBlockGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets Player NPCs escape simple holes by jumping and placing a block at their feet.

## Behavior

When the NPC is boxed in by at least three two-block-tall collision barriers, has no adjacent walkable open body space, and has a normal placeable block, it jumps first, waits a few ticks until its body is clear of the target block space, then consumes one block, places it below itself, swings, and returns to idle.

The same goal also handles the cave return-home case where the navigation/home target is above the NPC, the route is blocked by a tall wall, and normal pathing is done or stuck. In that mode it first finds a nearby pillar column with clear body space upward to a step-out or sky-visible surface route. It chooses a random 16-32 block escape budget, gathers nearby stone/cobblestone/deepslate only with a usable pickaxe if it has too few blocks, then moves to that pillar column.

One-block obstacles or flat ground with an open adjacent exit must not trigger this goal because Player NPC can jump those normally. Do not re-add a simple "two-block block ahead" trigger unless it is also tied to reliable stuck/path intent.

The delayed placement prevents the block from being placed inside the NPC's bounding box before the jump lifts it clear.

Utility blocks such as crafting tables, chests, furnaces, beds, and torches are not used for this escape.

Emergency escape material mining re-equips a pickaxe before every mining pass, adds cobblestone or cobbled deepslate directly to the NPC inventory after breaking the source block, damages the pickaxe, uses the same crack overlay helper as other gradual mining goals, and plays the source block's hit sound during mining. The final break sound/effect comes from `ServerLevel.destroyBlock`, so it only plays after the source block is actually broken.

While pillaring, the NPC temporarily equips a placeable block in the main hand, looks down at the placement position, places the block underneath after the jump clears the old feet space, and restores the previous hand item when the goal stops. The held block is counted as available escape material, and placement can use a fallback near the top of the jump so the NPC does not repeatedly jump without placing.

Cooldown uses `PlayerNpcEntity.holeEscapeCooldown`, decremented from the entity tick.
