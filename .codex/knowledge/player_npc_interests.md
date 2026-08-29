# Player NPC Interests

## Source

- `src/main/java/com/pla/smart_npc/clazz/PlayerNpcInterest.java`
- `src/main/java/com/pla/smart_npc/clazz/FakePlayer.java`
- `src/main/java/com/pla/smart_npc/entity/goal/InterestGatedGoal.java`
- `src/main/java/com/pla/smart_npc/entity/PlayerNpcEntity.java`
- `src/main/java/com/pla/smart_npc/client/gui/PlayerNpcInspectorOverlay.java`

## Purpose

Hardcoded fake-player names carry a small interest list. Interests are split into daily jobs and opportunistic characteristics.

Only one job interest is active for a Player NPC during a Minecraft day. Characteristics remain available as side behavior and do not become the whole-day job.

Job interests:

- `BUILDING`
- `MINING`
- `FARMING`
- `FISHING`
- `EXPLORING`

Characteristic interests:

- `HUNT_MONSTERS`
- `HUNT_ANIMALS`
- `HUNT_PLAYERS`
- `HUNT_VILLAGERS`
- `TROLL_HIT`
- `LOOTING`
- `CAUTIOUS`

Default interests for custom or unknown names are `BUILDING`.

## Daily Job Selection

`PlayerNpcEntity` stores `selectedDailyJobInterest` and `selectedDailyJobDay`.

- At day time `1`, the NPC rolls one defined job interest for that day.
- If the NPC loads after tick `1`, it may make one fallback roll during daytime so the job is not missing for the entire day.
- If no job interests are defined, the selected job is `none` and only characteristic/baseline goals can run.
- If the NPC has `BUILDING` but no saved home layout id, BUILDING is forced as the selected job. This is the base-selection lock: other job interests cannot run until the base/layout has been chosen.
- During that no-home/no-layout base-selection phase, nighttime uses the shared bounded camp activity (local walking, looking, and occasional sneaking) rather than leaving the builder idle. This passive builder mode never crafts or places a furnace or mutates the camp, and it yields immediately at daybreak or when a home/layout is selected.
- At night/thunder, a NPC with `BUILDING` and a saved home treats BUILDING home-duty goals as active even if the daytime selected job was something else. This lets return-home/shelter logic run and suppresses normal Mining, Fishing, or Farming outdoor camps. Recovery of an already owned temporary camp furnace is still allowed before the camp gate.

## Goal Gating

`PlayerNpcEntity.registerGoals()` wraps major work goals with `InterestGatedGoal`.

`InterestGatedGoal` calls `PlayerNpcEntity.isInterestGateActive(...)`:

- Job interests pass only when they match the selected daily job, with the building base-selection and night home-duty exceptions above.
- Characteristic interests pass whenever the NPC has that characteristic.
- Mixed gates, such as `MINING + HUNT_MONSTERS`, pass if either the selected daily job matches the job interest or the NPC has one of the listed characteristics.

Examples:

- `BUILDING`: choose/save base, gather build logs/stone/materials, terraform, build house, manage home, return home, sleep at home.
- `FISHING`: fishing and boat stockpiling.
- `MINING`: mining supply progression, cave-adjacent ore mining, cave path clearing, torch placement in caves, and temporary furnace support for ore smelting. A pure miner gathers logs first, then stone, then searches coal/iron/gold/copper ore; it must not choose or create a house.
- `FARMING`: staged owned-farm setup (logs, site, stone, irrigation, fence/gate, hoe/tilling), crop/seed acquisition, planting, harvesting, crop food crafting, passive local night camping when no valid farm plan exists, pure-farmer camping around a prepared farm's owned gate/path, charcoal furnace support, and bounded fence lighting. Night camp accepts FARMING when it is the current-day selection or when no current-day job was selected (for example an NPC loaded after the daytime roll window); a current-day different job remains authoritative. A combined BUILDING + FARMING NPC with a saved home/layout keeps home duty; one with neither a home/layout nor a farm plan may use the passive temporary camp instead of idling.
- `HUNT_MONSTERS`: smart target selection against monsters and combat gear prep.
- `HUNT_ANIMALS`: food-limited animal hunting, sheep hunting for beds, and cooking support.
- `HUNT_PLAYERS`: rare smart target selection against players and other PlayerNpc entities, plus combat gear prep.
- `HUNT_VILLAGERS`: very rare smart target selection against villagers, plus combat gear prep.
- `EXPLORING`: no-target roaming exploration for the day, plus explorer utility behavior such as spyglass/boat support.
- `LOOTING`: chest looting.
- `CAUTIOUS`: avoids nearby survival/adventure players, hostile mobs, other Player NPCs, and compatibility threats. Rare sneak and scared hide use the same threat rules, hold crouch, and watch their cached threat; creative/spectator players and unrelated passive entities are ignored.

Emergency and survival behaviors such as floating, hole escape, help calls, obstruction breaking, pickup, cooking, and basic gear crafting remain outside daily-job gating where needed. Water travel itself is destination-aware and owned by the active movement goal; there is no global MOVE-owning water-escape goal.

Daily-interest gating and server-startup/routine-worker gating are separate. `StartupWorkGatedGoal` wraps job and baseline routine work registrations such as pickup, cooking/crafting, home work, gathering, farming, fishing, mining, building, and exploration. No routine work delegate can start during the first 60 server ticks; a stable UUID-derived offset spreads each NPC's release across the following 60 ticks. This gate is not persisted and therefore applies only to server startup, not to an NPC spawned later in a running world. Its continuation check also prevents a restored/running work lifecycle from bypassing the grace period. Combat targeting and attack/defense goals, low-health flee/heal, floating, cautious avoidance/hiding, help calls, hole/high-column escape, combat weapon recovery, obstruction breaking, doors, LOOTING chest behavior, and TROLL_HIT dance/hit/golem behavior are deliberately registered without the routine-worker wrapper. The low-frequency, bounded `AiBudgetWaitingStrollGoal` also remains outside it and is specifically available while routine work is queued; it reuses vanilla water-avoiding stroll target selection but does not acquire or bypass a routine worker turn. Crafting/gear preparation or path-based job patrol remains routine support even when a characteristic interest makes it eligible, so it may still use the worker scheduler; the characteristic's core targeting/safety/personality behavior must not.

Home radius rule:

- If the NPC has a saved home and does not have `EXPLORING`, local resource goals stay within a 96 block radius around the home center.
- This applies to generic log/stone/dirt gathering, biome log searching, dig-down stone sites, and cave ore targets.
- If the NPC has no home, the resource goals can range freely.
- If the NPC has `EXPLORING`, the radius limit is bypassed so that `BUILDING + EXPLORING` personalities can still travel farther.

## Hunting

`PlayerNpcSmartTargetGoal` is gated by the four hunt interests and scans once per second while the NPC has no valid current target.

- `HUNT_MONSTERS` targets monsters and illagers when the NPC is not badly outmatched.
- `HUNT_ANIMALS` targets passive animals only when the NPC needs food, is not already waiting on animal drops, is not urgently gathering starter logs, and no nearby supply drop can fill the need.
- `HUNT_PLAYERS` targets players and other PlayerNpc entities only when the target is not clearly stronger, then passes an 8% random chance per scan.
- `HUNT_VILLAGERS` targets villagers only after a 2% random chance per scan.

Troll-hit behavior is separate from hunting. `TrollHitGoal` can rarely hit any valid nearby living non-allied target, except creative players, then clears the target and runs away with a long cooldown. `IronGolemTrollGoal` is also separate and remains a rare special mischief behavior.

## Inspector

The inspector overlay shows:

```text
Interests: ...
```

This comes from the hardcoded name definition through `FakePlayerName`.

## Behavior Rule

NPCs without `BUILDING` should not run the house-building/home-shelter pipeline. They act more like travelers or job workers: exploring, looting, fishing, mining, farming, hunting, or cautious hiding depending on their interests. Pure mining NPCs should not call `PlayerNpcHomeUtil.getOrCreateHome(...)`, choose a home area, set a house, or return home at night; when underground, they only start surface log resupply during daytime. Job-specific anchors such as a farming area may still be persisted by that job's own goal.
