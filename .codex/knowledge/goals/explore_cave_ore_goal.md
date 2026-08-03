# ExploreCaveOreGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/ExploreCaveOreGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets mining Player NPCs seek cave-adjacent ores after the mining support supplies are ready.

## Activation

- Server side only.
- Requires the daily `MINING` job to be active.
- Requires log supply and stone supply to be met first. The mining-only progression is logs -> stone -> ore; do not insert house selection into this flow.
- Requires a carried usable pickaxe for the target ore, including held, inventory, offhand, or reserved weapon/tool slots.
- Skips while healing, in combat, inventory is nearly full, or `oreMiningCooldown` is active.
- A pure miner can request upward escape only when logs fall below the supply goal, it is daytime, and the NPC is underground. Building miners rely on the building home-duty/night-return path.

## Behavior

The NPC searches nearby underground/cave-exposed blocks for coal, iron, gold, and copper ore variants, pathfinds to a valid stand position, equips the matching pickaxe temporarily if needed, and re-checks the pickaxe before every breaking pass. Ore mining goes through `BreakingBlockAi`, so vanilla-style hardness/tool-speed timing, block crack progress, mining hit sounds, main-hand attack animation, EpicFight digging state, durability loss, and final block drops stay consistent with other block-breaking goals.

The ore search is deliberately limited to 16 blocks horizontally, 8 blocks below, and 6 blocks above the NPC. Do not expand the downward scan casually: deep/downhill ore targets caused repeated `mine coal ore -> return home -> managing home` loops when the target stand was physically valid but unreachable.

Ore stand positions must be directly close, have a completed `Path.canReach()` path, be reachable through this goal's bounded safe-drop helper, or have a very close local obstruction that can be cleared. The stand must either have a clear mining ray to the ore or a clearable blocker on that ray. Do not let the NPC mine ore through a wall or through cover; clear the blocker first. The wide ore scan collects ore positions first and only path-checks a capped nearest/priority subset; do not path-check every ore found in the volume. `moveToTarget()` also uses a concrete `Path` and rejects partial paths. Failed ore targets are remembered in a skipped set so the same unreachable ore block is not immediately reselected on the next ore attempt.

After mining one ore, the goal keeps the active vein as a cluster, remembers same-family ore positions connected to the starting ore, scans neighboring positions around known/mined vein blocks, and continues mining the nearest reachable block in that cluster before looking for a different ore deposit. This makes exposed and newly exposed coal/iron/gold/copper veins behave more like tree log farming instead of mining one block and wandering away. Ore cooldown is short after a successful cluster pass and longer after a failed attempt; no cooldown is applied while switching between blocks in the same cluster.

When the selected ore stand position is below the NPC and normal navigation is done or stuck, the goal can nudge the NPC into a nearby safe drop with open body space and a floor within a few blocks. This is scoped to cave ore exploration so rough cave descents do not globally change every navigation goal.

If the NPC is blocked while moving to an ore stand, or the ore is covered from the current mining ray, the goal starts `ClearBlockAi` for the blocker and then resumes the original ore target. `ClearBlockAi` delegates breaking to `BreakingBlockAi`, so clearing a head block, path blocker, or ore cover still shows the mining swing and break progress. It skips protected home blocks, temporary crafting tables, fluids, unbreakable blocks, and block entities while clearing path obstructions.

While exploring between ore blocks and not mid-break/clear, the goal can randomly craft torches from one coal/charcoal plus one stick into four torches through `PlayerNpcCraftingUtil.tryCraftTorches`, then place a torch in a low block-light cave spot if no torch is already nearby. Pure miners may spend log reserve for this utility; building miners preserve the building raw-log reserve.

The goal clears crack progress when the NPC leaves breaking range, gives up, completes mining, or stops.

Inspector detail shows the ore block id, coordinates, and mining progress.

Cooldown uses `PlayerNpcEntity.oreMiningCooldown`.
