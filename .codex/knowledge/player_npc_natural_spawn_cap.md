# Player NPC Natural Spawn Cap

## Sources

- `src/main/java/com/pla/smart_npc/util/PlayerNpcNaturalSpawnCap.java`
- `src/main/java/com/pla/smart_npc/util/PlayerNpcPopulationData.java`
- `PlayerNpcEntity.canSpawn(...)` and `PlayerNpcEntity.finalizeSpawn(...)`

## Configuration Semantics

`maxNaturalPlayerNpcs` controls only admission of new non-egg/non-command/non-structure Player NPC spawns:

- `0`: disable natural Player NPC spawning.
- positive: fixed living-population cap.
- `-1`: conservative automatic cap; this is the default.

The cap counts Player NPCs created by every source once they join, but command, spawn-egg, and structure creation bypasses admission as before. If those sources put the population above the current cap, no NPC is removed; later natural spawns remain blocked until the living count falls below the cap.

This population policy is independent of `PlayerNpcAiWorkBudget`. The routine worker limit controls how many loaded/ticking NPCs may run jobs concurrently; it is not a population limit. The spawn cap is also independent of `forceTickManage`: disabling force tickets lets distant NPC chunks unload but does not forget those persistent NPC identities or disable natural-spawn admission.

The `SmartNpcConfig` comment is the authoritative generated-TOML explanation for these semantics. An already existing `smart_npc-server.toml` may retain its old comments until Forge rewrites the file or the file is recreated; that does not change the value's runtime meaning, and migration must not add new tuning keys solely to refresh comments.

## Automatic Policy

Automatic mode starts at no more than four NPCs, further limited by the CPU/heap exploration ceiling. A one-processor/1-GiB JVM therefore still starts at two. Four is a bounded compromise for the two-worker scheduler: it lets an ordinary device begin with enough lightweight idle/characteristic NPCs to be useful, while synchronized reservations prevent the former dozens-of-NPC fresh-chunk burst. This startup-safe value remains indefinitely while the performance monitor is disabled, and remains through the initial rolling-window warmup. After a stable policy has been learned, a temporary sample warmup caused by a player/NPC join holds that learned cap instead of oscillating back to four; it still cannot grow until stable samples return.

CPU and maximum JVM heap provide only a deterministic **exploration ceiling**, not an assertion that the device can safely run that many NPCs. It is the smaller of twice the available processor count and four times maximum heap GiB, clamped to `1..16`. The previous `processors / 2` and `heapGiB * 2` formula capped an 8-thread/2-GiB server at four, so live evidence could never let a device already known to run eight NPCs progress beyond four. The wider exploration ceiling permits that server to probe to eight; measured load feedback, occupied-cap checks, join warmup, and one-NPC increments remain the safety controls.

Once a stable rolling sample exists, the server-thread policy also calculates an advisory linear forecast:

- non-NPC MSPT as `max(0, averageMspt - averageNpcMs)`, which includes chunk/world/other-mod pressure and therefore includes force-ticket side effects,
- marginal NPC MSPT as `max(2.5, averageNpcMs / loadedNpcCount)`,
- the forecast as `floor((45 - nonNpcMs) / marginalNpcMs)`, clamped to `1..explorationCeiling`.

The forecast is diagnostic only and never gates growth or directly selects the effective cap. Total measured NPC tick cost does not scale linearly with population because routine job work is capped by `PlayerNpcAiWorkBudget`; idle, stroll, and characteristic behavior remain per-NPC, while only a bounded number of routine workers execute. The screenshot case `population=3`, `loaded=3`, `averageMspt=39.8`, `linear forecast=2` is therefore eligible evidence for probing NPC four, not a reason to freeze at three.

Population feedback uses a robust rolling baseline: sort the valid rolling tick samples and omit only the highest five percent before averaging. This is not permission to ignore recurring load; sustained pressure remains in the other 95%. It prevents one bounded-but-pathological job/path tick from inflating the five-second arithmetic average and being misclassified as the cost of every passive NPC. The raw average, advisory forecast, and baseline remain visible together in diagnostics.

The policy reevaluates every five seconds and has two evidence tiers. Growth always requires the effective cap to be occupied by living NPCs, at least that many currently loaded, and room below the exploration ceiling:

- Strong headroom: baseline <=40 MSPT for two consecutive evaluations grows by two, capped at effective max 10 for this fast tier.
- Normal headroom: baseline <=45 MSPT for three consecutive evaluations grows by one. At or above 10, all further growth uses this slower tier.

Thus 10 is not a fixed or guaranteed population. It is only the boundary above which two-at-a-time recovery stops. Synchronized reservations limit a fast promotion to its two new slots, and the first resulting join starts the normal monitor warmup. The learned cap is held, growth progress resets, and another promotion cannot occur until stable samples measure the new population. If a cap is not occupied or fully loaded, the controller waits instead of raising an untested limit.

This explains the observed inspector change from two to four under the earlier automatic controller: it was not a multi-NPC hardware jump. That controller completed the three healthy five-second windows for `2 -> 3`, then later completed the same evidence cycle for `3 -> 4`; a one-second inspector refresh or time away from the panel could miss the intermediate value. The refined occupied-cap controller retains those one-step promotions but additionally requires NPC three to join and be measured before `3 -> 4`. In the fastest steady case each promotion needs 15 seconds of eligible samples, plus any natural-spawn wait and join/sample warmup.

There is asymmetric hysteresis around Minecraft's 50 ms / 20 TPS deadline. At more than 45 but below 50 baseline MSPT, an occupied/tested cap is held but cannot grow. If a probe slot has not been populated when headroom enters this band, that untested capacity is retracted. At 50+ baseline MSPT, future admission targets one below the currently living-and-loaded overloaded level. Once the effective cap is already below the loaded population, repeated evaluations hold it instead of ratcheting toward one while the same NPCs remain alive; another reduction waits for natural attrition to test the lower population. Lowering the cap never despawns, damages, or unloads an NPC.

`PlayerNpcPopulationData` persists the highest population actually observed under a <=45 baseline as `LearnedAutoCap`. A restart restores at most `min(learned safe, living count, exploration ceiling)`, plus the ordinary startup allowance. It never turns an old learned number into multiple empty spawn slots after population loss or a hardware/environment change. Overload reductions lower the persisted learned value. Older saves without the tag migrate as zero and use startup learning normally.

## Concurrent Spawn Admission

Fresh-chunk spawn predicates may run concurrently on `Worker-Main` threads before entity-join events update any manager. Automatic-cap calculation never runs there: workers read a synchronized cached policy only after name, day, and vanilla spawn rules pass.

A cheap synchronized preflight rejects immediately when the cached cap is already full (including fixed zero), but it does not claim capacity. Every predicate that passes all ordinary spawn rules then takes a short-lived authoritative reservation. Finalization binds one reservation to the new UUID, and the entity-join event converts it to a living identity. Living IDs, unbound reservations, and finalized-but-not-yet-joined IDs all count against the cap, so 32 concurrent candidates cannot all observe the same pre-join count and overshoot the startup cap of at most four. Failed/cancelled reservations expire after ten server seconds.

## Persistence And Diagnostics

`PlayerNpcPopulationData` stores living UUIDs independently of force-ticket data. On its first load in an existing world, it seeds from legacy `PlayerNpcForceTickData` UUIDs so remote persistent NPCs are not temporarily undercounted; stale entries later pruned by the force manager are also removed from the population registry. Chunk unload removes only the loaded marker; death or permanent discard removes the living identity. The registry is flushed from the server tick, never from an asynchronous spawn worker.

`/smart_npc resources` reports configured/automatic mode, effective cap, living/loaded/pending counts, exploration ceiling, advisory forecast, raw average/NPC MSPT, trimmed baseline MSPT, persisted learned-safe max, probe mode (`fast_plus_2`, `normal_plus_1`, hold/overload/warming), dynamic progress (`n/2` or `n/3`), and policy reason. The overall inspector presents the same state on separate population, feedback, and limits lines. These diagnostics do not mutate admission state.
