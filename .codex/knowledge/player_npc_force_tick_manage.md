# Player NPC Force Tick Manage

Feature flag:
- `SmartNpcConfig.forceTickManage` is a common config value and defaults to `true`.
- When disabled, Player NPC force-tick tickets are released, NPC tab-list rows are removed, `/smart_npc tp` is hidden, and inspectator left/right falls back to the old nearby-client scan.

Core owner:
- `src/main/java/com/pla/smart_npc/util/PlayerNpcForceTickManager.java`
- It is a Forge event subscriber and owns the tracked loaded Player NPC registry, force-tick chunk tickets, tab-list fake players, command lookup, and tracked inspectator cycling.

Chunk tickets:
- Each tracked alive `PlayerNpcEntity` always force-ticks its current chunk. Within the surrounding 3x3 area, the manager adds tickets only to neighbors which `ServerLevel.hasChunk(...)` reports are already loaded. It retains those chunks afterward, but never loads or generates an unloaded neighbor just to expand the NPC's force area.
- Tickets use `ServerChunkCache.addRegionTicket(..., forceTicks=true)` and are removed with the matching `removeRegionTicket(..., forceTicks=true)`.
- The ticket type is `smart_npc:player_npc_force_tick`; ticket values include the NPC UUID and chunk long so overlapping NPCs release independently.
- Old chunks are released whenever the NPC changes chunk, dies, leaves the level, the server stops, or the config is turned off.

Restart persistence:
- Runtime chunk tickets do not survive a server stop. `PlayerNpcForceTickData` persists each tracked NPC UUID, dimension, and last center chunk in overworld `SavedData`.
- On server start/login/feature enable, `PlayerNpcForceTickManager` restores the saved center ticket so the existing NPC chunk can resolve. Neighbor tickets are added later only when another loader has already loaded those chunks.
- `EntityLeaveLevelEvent` only releases runtime tickets/tab rows; it does not delete the saved registry entry because chunk unloads and server stop also fire leave events.
- `LivingDeathEvent` removes both runtime tracking and the saved registry entry. If a restored entry cannot resolve an entity after the center chunk has loaded and the grace window expires, it is treated as stale and pruned.
- Tracking is immediate on entity join. Ongoing ticket-center, tab-profile, and saved-data reconciliation runs once per 20 server ticks per NPC, with a deterministic UUID phase so distant NPCs do not create one synchronized housekeeping burst. A moving NPC's center ticket can therefore follow a chunk crossing by up to one second; the new center was already part of the retained loaded area during ordinary movement.
- Restored unresolved entries count that refresh interval toward the 30-second entity-load grace, so throttling housekeeping does not accidentally extend stale tickets to ten minutes.

Tab list:
- Tracked NPCs are added to the multiplayer tab list only while `forceTickManage` is enabled.
- The manager sends 1.20.1 `ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(...)` for a Forge fake player whose UUID matches the NPC entity UUID.
- `PlayerEvent.TabListNameFormat` gives managed fake players a `[NPC] ` prefix before the NPC display name.
- Fake tab players are put in spectator mode and use a late-sorting synthetic profile name so vanilla tab sorting keeps normal real players above NPC rows.
- Rows are removed with `ClientboundPlayerInfoRemovePacket` when the NPC is no longer tracked.

Teleport command:
- `/smart_npc tp <name>` is registered under the existing command event and is gated by `forceTickManage`.
- Suggestions come from currently tracked alive NPC display names.
- Name matching strips an optional `[NPC] ` prefix and compares case-insensitively.
- If multiple tracked NPCs share the same name, the command chooses a random match.

Inspectator cycling:
- Client left/right in inspectator mode sends `PlayerNpcInspectatorCyclePacket`.
- If the server feature is enabled, the server picks the next tracked alive NPC, teleports the viewer to that NPC if needed, starts riding it in spectator mode, and sends both `PlayerNpcInspectatorCycleResultPacket` and a fresh `PlayerNpcInspectorPacket`.
- If the server feature is disabled, the result is marked unhandled and the client runs the previous 64-block nearby cycle scan.
- The client overlay keeps a short pending-target window so a remote target does not immediately clear while chunks/entities are arriving.

Verification:
- `./gradlew.bat compileJava` passed after the implementation.
