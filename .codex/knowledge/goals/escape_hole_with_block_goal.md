# EscapeHoleWithBlockGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/EscapeHoleWithBlockGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets Player NPCs escape simple holes by jumping and placing a block at their feet.

## Behavior

When the NPC is boxed in on at least three horizontal sides and has a normal placeable block, it jumps first, waits a few ticks until its body is clear of the target block space, then consumes one block, places it below itself, swings, and returns to idle.

The delayed placement prevents the block from being placed inside the NPC's bounding box before the jump lifts it clear.

Utility blocks such as crafting tables, chests, furnaces, beds, and torches are not used for this escape.

Cooldown uses `PlayerNpcEntity.holeEscapeCooldown`, decremented from the entity tick.
