# GatherMaterialsGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/GatherMaterialsGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Gives idle Player NPCs a basic worker-style material gathering job so they do not only combat.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Inventory must not be mostly full.
- Cooldown after each attempt uses `PlayerNpcEntity.gatherCooldown`.

## Behavior

Searches nearby blocks for logs, stone, cobblestone, coal ore, dirt, or grass blocks. The NPC pathfinds to one, looks at it, swings periodically, sends vanilla block crack progress through `PlayerNpcEntity.showBlockBreakProgress`, plays the target block's hit sound while mining, then breaks the block and lets normal item drops/pickup handle materials. The final break sound/effect is left to `ServerLevel.destroyBlock`, so full break audio only plays after the block is actually removed.

The saved `PlayerNpcHomeUtil` home area is protected. Material scanning, same-tree log continuation, and connected-log discovery skip blocks inside that home volume so the NPC does not mine its own house logs, cobblestone, furnace, or other home blocks for resources.

Material choice is progression-aware:

- If the NPC still lacks starter tools/weapons, it prioritizes nearby logs until it has enough wood to place a crafting table or craft starter gear.
- If the NPC has low build supply and no logs are nearby, `ExploreBiomeForLogsGoal` can move it farther across the biome until logs enter scan range; then this goal takes over harvesting.
- Once it has a pickaxe and still needs stone gear, it prioritizes stone/cobblestone/deepslate/cobbled deepslate.
- If it has raw food, has a pickaxe, and lacks furnace fuel, it can prioritize coal ore.
- Stone, cobblestone, deepslate, cobbled deepslate, and coal ore targets require a pickaxe before target selection and again before mining starts. If the NPC only has an axe or other non-pickaxe tool, it must not mine stone-like blocks.
- It pauses gathering only when the gear crafting goal can actually make progress, using `PlayerNpcEntity.craftGearCooldown` to avoid fighting the craft goal. Early progression must not pause log gathering merely because shovel or sword is theoretically craftable; missing axe and pickaxe are critical, so gathering continues until the next critical starter tool can be crafted after any crafting-table cost. Without a nearby crafting table, it accounts for the four-plank table cost before deferring; otherwise low-wood NPCs can deadlock with too little wood to craft after placing the table.
- It records the target block id and coordinates in `PlayerNpcEntity`'s synced AI detail field so the inspector can show what block it is trying to mine.
- When raw logs drop below 4, the goal rolls a temporary raw-log reserve target from 4 to 12 and keeps prioritizing logs until that reserve is met.
- While refilling raw logs, the goal scans for reachable logs before dirt so fresh NPCs do not mine random grass blocks instead of trees. If no reachable, pillarable, or leaf-clearable log target can be selected and the NPC is below its dirt pillar reserve, it switches to dirt/grass collection. Dirt or grass blocks are only support materials for high-log pillaring; logs are never used as pillar blocks.
- Missing axe or pickaxe keeps material selection in the log/crafting phase. The goal must not drift into general stone/cobble work before a pickaxe exists.
- When gathering logs, the direct target scan uses a 48 block horizontal radius and only scans 2 blocks below the NPC. Non-log material scanning remains at the smaller 8 block radius. The 48-block log scan must collect and sort candidate log positions first, then path-check only a capped nearest subset. Do not call `findStandPos`/`Path.canReach()` for every log found in the wide scan, because that can freeze server TPS when several NPCs scan at once.
- During log gathering, it remembers the connected log cluster for the active tree, mines remaining logs bottom-up, and switches directly to the next reachable log until the reachable cluster is exhausted. If connected logs remain but no normal standing position can reach them, the goal can move beside the trunk, jump, place dirt at its feet, re-equip the axe, and continue mining upward. Dirt placement checks for normal jump clearance first, but forces the placement after a short wait so a missed timing window does not leave the NPC jumping forever.
- If leaves block a log target or a pillar route, log gathering can temporarily target nearby leaves around the same tree, clear up to 24 leaves in that run, then return to the connected log cluster. Leaf clearing first chooses leaves the NPC can break from its current position, sorted by direct distance, then uses only a bounded nearby fallback. It avoids high buried canopy leaves so the NPC does not stare upward and spin instead of clearing the obstruction in front of it. Pillar logic only runs for real log targets, not leaf-clear targets.

When the NPC has a matching axe, pickaxe, or shovel in its custom inventory, the goal temporarily equips it while mining and restores the previous main-hand item afterward. Axe selection uses vanilla axe-mineable block tags plus explicit crafting table/log handling; pickaxe selection uses pickaxe-mineable tags plus stone/furnace handling; shovel selection uses shovel-mineable tags plus dirt/grass. If a temporary swap stored the required tool in `previousMainHand` while clearing leaves or changing support items, the goal must restore that stashed tool before mining the next matching block and clear the temporary stash so durability is not reset later. If no axe is available for log mining, it temporarily clears the main hand so the NPC does not mine logs with a sword or unrelated item. Mining now triggers the Player NPC renderer's main-hand attack animation timer, not just vanilla `swing`, so block breaking is visible on the model.

Mining duration uses a vanilla-style block hardness and held-tool speed calculation instead of a fixed 24 tick delay. Empty-hand log mining is slow, axe log mining is faster, and wrong-tool mining receives the slower incorrect-tool penalty.

When a block is successfully destroyed, the equipped main-hand tool loses durability through `PlayerNpcEntity.hurtMainHandItem(1)`.

The goal clears crack progress when the NPC leaves breaking range, switches target, completes mining, or stops.

The inspector task detail includes `walking` while pathing and `current/required ticks` while mining, for example `minecraft:oak_log @ 12 64 -8 18/60t`.

The goal now finds a nearby valid stand position for the target block and pathfinds there, instead of trying to path into the solid target block. Stand positions must either be directly close, have a completed `Path.canReach()` path, or have a very close local obstruction that this goal can mine; a partial path object is not enough. If movement to the stand position is blocked by a nearby collision block, the goal keeps the original material target, temporarily mines the local obstruction with the matching tool rules, shows `clearing path ...` in inspector detail, then resumes pathing to the original target. It skips protected home blocks, unbreakable blocks, fluids, and block entities while clearing path obstructions. Log targets use a taller upward scan, player-like reach, lower side stand positions, and a longer 60 second gather window so the NPC can keep mining upper trunk logs from the ground when reachable. After mining logs it uses a very short cooldown so other same-priority worker goals can run instead of waiting on the normal material cooldown. General material cooldown is intentionally short so the NPC does not sit idle for long between worker tasks. It also repaths periodically and gives up after a short failed-path timeout so the NPC does not remain stuck in `Gathering materials` forever.
