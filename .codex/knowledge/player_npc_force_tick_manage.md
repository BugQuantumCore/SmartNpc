# Player NPC Force Tick Manage

Feature flag:
- `SmartNpcConfig.forceTickManage` is a common config value and defaults to `true`.
- When disabled, Player NPC force-tick tickets are released, NPC tab-list rows are removed, `/smart_npc tp` is hidden, and inspectator left/right falls back to the old nearby-client scan.

Core owner:
- `src/main/java/com/pla/smart_npc/util/PlayerNpcForceTickManager.java`
- It is a Forge event subscriber and owns the tracked loaded Player NPC registry, force-tick chunk tickets, tab-list fake players, command lookup, and tracked inspectator cycling.

Chunk tickets:
- Each tracked alive `PlayerNpcEntity` force-ticks the 3x3 chunk area around its current chunk.
- Tickets use `ServerChunkCache.addRegionTicket(..., forceTicks=true)` and are removed with the matching `removeRegionTicket(..., forceTicks=true)`.
- The ticket type is `smart_npc:player_npc_force_tick`; ticket values include the NPC UUID and chunk long so overlapping NPCs release independently.
- Old chunks are released whenever the NPC changes chunk, dies, leaves the level, the server stops, or the config is turned off.

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
