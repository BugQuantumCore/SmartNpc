# Player NPC Force Tick Manage

Natural-spawn population cap:
- `maxNaturalPlayerNpcs` defaults to `8`. `0` disables natural Player NPC spawning and `-1` is an explicit opt-in to an unlimited numeric population. Never use `-1` as the generated default: Player NPCs are persistent (`removeWhenFarAway(...)` is false), and a force-loaded unlimited population accumulates instead of despawning.
- The spawn predicate uses `PlayerNpcForceTickManager.livingNpcCount(...)` across tracked dimensions and rejects non-egg/non-command/non-structure spawning at the cap. It currently applies only while `forceTickManage=true`, because this manager is authoritative across dimensions only in that mode. Decoupling it requires a separate lifecycle-maintained population registry; do not scan every level's entities from the frequently called spawn predicate.
- Runtime evidence from the 2026-08-27 CurseForge report: Forge corrected a missing key to the regressed default `maxNaturalPlayerNpcs=-1` at 23:38:46, and the performance warning reported `totalPlayerNpcs=41` at 23:42:29. Twelve NPC finalizations ran on `Worker-Main` threads during 1-99% spawn-area preparation before the player joined; another 32 worker-thread finalizations followed while the fresh world loaded a 16-chunk view/12-chunk simulation area. This distinguishes fresh-chunk generation from the already-generated dev-world observation. With `forceTickManage=true`, counting worked for registered entities; the unlimited value and asynchronous chunk-generation path prevented a useful bound.

Feature flag:
- `SmartNpcConfig.forceTickManage` is a common config value and defaults to `true`.
- When disabled, Player NPC force-tick tickets are released, NPC tab-list rows are removed, `/smart_npc tp` is hidden, and inspectator left/right falls back to the old nearby-client scan.

Core owner:
- `src/main/java/com/pla/smart_npc/util/PlayerNpcForceTickManager.java`
- It is a Forge event subscriber and owns the tracked loaded Player NPC registry, force-tick chunk tickets, tab-list fake players, command lookup, and tracked inspectator cycling.

Chunk tickets:
- Each tracked alive `PlayerNpcEntity` has one moving distance-2 region-ticket anchor at its current chunk. The ticket's normal distance propagation supplies a loaded fringe around the center; do not add a 3x3 grid of independent force-tick anchors. Nine anchors per NPC multiply entity-ticking chunks and scale poorly when many NPCs are spread across the world.
- Tickets use `ServerChunkCache.addRegionTicket(..., forceTicks=false)` and are removed with the matching `removeRegionTicket(..., forceTicks=false)`. The boolean is part of Forge ticket identity, so add/remove values must always match.
- Distance 2 creates ticket level 31 (`ENTITY_TICKING`) at the NPC center. This keeps the Player NPC entity AI alive; block entities and scheduled block/fluid ticks follow the ordinary block-ticking status. Forge's separate `forceTicks=true` registry bypasses the real-player proximity gate in `ServerChunkCache.tickChunks`, adding natural spawning and `tickChunk` random ticks at every remote center. Do not enable it for Player NPC anchors. Consequently, crops and other random-tick blocks do not advance solely from an NPC ticket, matching vanilla `/forceload`; they still advance when a real player's simulation range covers the chunk.
- The ticket type is `smart_npc:player_npc_force_tick`; ticket values include the NPC UUID and chunk long so overlapping NPCs release independently.
- Old chunks are released whenever the NPC changes chunk, dies, leaves the level, the server stops, or the config is turned off.

Restart persistence:
- Runtime chunk tickets do not survive a server stop. `PlayerNpcForceTickData` persists each tracked NPC UUID, dimension, and last center chunk in overworld `SavedData`.
- On server start or feature enable, `PlayerNpcForceTickManager` restores the saved center ticket so the existing NPC chunk can resolve. Player login uses that idempotent initialization state and does not repeat a level-wide reconciliation.
- `EntityLeaveLevelEvent` only releases runtime tickets/tab rows; it does not delete the saved registry entry because chunk unloads and server stop also fire leave events.
- `LivingDeathEvent` removes both runtime tracking and the saved registry entry. If a restored entry cannot resolve an entity after the center chunk has loaded and the grace window expires, it is treated as stale and pruned.
- Tracking is immediate on entity join. Ongoing movement refresh runs once per 20 server ticks per NPC, with a deterministic UUID phase so distant NPCs do not create one synchronized housekeeping burst. Unchanged centers and saved registry values do no ticket-set or dirty-data work; display/profile metadata uses a slower stagger and unchanged metadata does not recopy the fake profile. A moving NPC's center ticket can therefore follow a chunk crossing by up to one second; the distance-2 center ticket keeps the adjacent transition fringe loaded during ordinary movement.
- Restored unresolved entries count that refresh interval toward the 30-second entity-load grace, so throttling housekeeping does not accidentally extend stale tickets to ten minutes.

Tab list:
- Tracked NPCs are added to the multiplayer tab list only while `forceTickManage` is enabled.
- The manager sends 1.20.1 `ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(...)` for a Forge fake player whose UUID matches the NPC entity UUID.
- A joining viewer receives all currently resolved managed NPC rows in one initializing packet rather than one packet and one reconciliation pass per NPC.
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
