# Player NPC Interests

## Source

- `src/main/java/com/pla/player_npc/clazz/PlayerNpcInterest.java`
- `src/main/java/com/pla/player_npc/clazz/FakePlayer.java`
- `src/main/java/com/pla/player_npc/entity/goal/InterestGatedGoal.java`
- `src/main/java/com/pla/player_npc/entity/PlayerNpcEntity.java`
- `src/main/java/com/pla/player_npc/client/gui/PlayerNpcInspectorOverlay.java`

## Purpose

Hardcoded fake-player names now carry a small personality list. The list decides which long-running activity goals can start.

Interests include:

- `BUILDING`
- `MINING`
- `FARMING`
- `FISHING`
- `HUNT_MONSTERS`
- `HUNT_ANIMALS`
- `HUNT_PLAYERS`
- `HUNT_VILLAGERS`
- `EXPLORING`
- `LOOTING`
- `CAUTIOUS`

Log gathering, stone gathering, crafting, and gear upgrading are baseline AI, not personality interests. Every PlayerNpc can run starter log gathering, biome log searching, dig-down stone gathering, sapling planting, basic gear crafting, stone tool upgrades, iron/diamond tool and armor upgrades, and utility crafting when those goals' own resource/cooldown checks pass.

Default interests for custom or unknown names are `EXPLORING` and `LOOTING`.

## Goal Gating

`PlayerNpcEntity.registerGoals()` wraps major work goals with `InterestGatedGoal`.

Examples:

- `BUILDING`: build house, manage home, return home, sleep at home.
- `FISHING`: fishing and boat stockpiling.
- `MINING`: cave-adjacent ore mining, cave path clearing, and torch placement in caves. Iron/diamond gear upgrading is baseline once materials and a crafting table are available.
- `FARMING`: crop farming and crop food crafting.
- `HUNT_MONSTERS`: smart target selection against monsters and combat gear prep.
- `HUNT_ANIMALS`: food-limited animal hunting, sheep hunting for beds, and cooking support.
- `HUNT_PLAYERS`: rare smart target selection against players and other PlayerNpc entities, plus combat gear prep.
- `HUNT_VILLAGERS`: very rare smart target selection against villagers, plus combat gear prep.
- `EXPLORING`/`LOOTING`: chest looting, long-range travel behavior, boat stockpiling, and spyglass use.
- `CAUTIOUS`: rare sneak and scared hide behavior.

Emergency and survival goals such as water escape, hole escape, help calls, obstruction breaking, pickup, first-day log gathering, sapling replanting, and basic crafting remain outside personality gating where needed.

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

NPCs without `BUILDING` should not create homes. They act more like travelers: exploring, looting, fishing, mining, farming, hunting, or cautious hiding depending on their interests.
