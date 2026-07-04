# JukeboxDanceGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/JukeboxDanceGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Adds non-combat social behavior around jukeboxes and music discs.

## Behavior

Hard mode spawn inventory has a rare chance to include a jukebox and a random music disc. If an idle NPC with a home has a jukebox and disc, it can place the jukebox in its house and insert the disc. Nearby idle Player NPCs can join an active jukebox dance.

While dancing, `PlayerNpcEntity.isDancing()` is synced, the NPC moves near the jukebox, repeatedly toggles sneak/crouch, jumps, and takes small steps around the jukebox. Another idle Player NPC can very rarely disturb the dance by targeting a dancing NPC.

Cooldown uses `PlayerNpcEntity.jukeboxDanceCooldown`.
