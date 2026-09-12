# Remote NPC departure

Both SmartNpc and AnnoyingVillagers implement the same unattended-session policy,
without requiring the other mod. COMMON config files smart_npc-server.toml and
annoyingvillagers-server.toml have:

```toml
[remoteNpcDeparture]
enabled = true
minMinutes = 10
maxMinutes = 30
```

RemoteNpcDeparture persists each NPC's sampled limit and elapsed simulation ticks
in Forge entity persistent data. Polling is staggered at 20 ticks. Offline time is
not counted. Returning external chunk coverage resets elapsed time; disabling the
feature or losing a ticket pauses the saved timer, including during restart ticket
restoration. Login/logout does not itself reset or resample the timer. Reversed
bounds are normalized; changing bounds resamples. Config bounds are 1–10080 minutes.

ExternalChunkActivity reads DistanceManager's actual ticket anchors through mapped
accessor mixins. A ticket covers a FULL chunk when level + Chebyshev distance <= 33.
Player view tickets and explicit vanilla/mod loaders count. Both mods' NPC tickets,
transient UNKNOWN/LIGHT tickets, and vanilla START spawn-area tickets do not count.
START is intentionally excluded so initial-spawn NPCs can leave after players move
away. Reads never request/generate chunks or remove tickets to probe availability.
Snapshots are shared per level for 20 ticks; expiration rechecks fresh tickets.

PlayerNpcDepartureEvent only advances the policy for live PlayerNpcEntity instances
with a selected force resource. Existing discard handlers clear tab rows, tickets,
the force registry, and the natural spawn population registry. Existing ChatUtil
handles the leave message and its chat configuration. No replacement is spawned
directly; ordinary natural spawn rules/caps still apply.

AV's PersistentPlayerNpc interface opts Steve, Angry Steve, Alex and Chris into
always-forced sessions. See the AV persistent_player_npcs knowledge note for
singleton ownership, alternate forms and restart restoration.
