# ExploreCaveOreGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/ExploreCaveOreGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs seek cave-adjacent ores instead of only gathering surface logs/stone/dirt.

## Behavior

Requires a usable pickaxe in hand or inventory for the specific ore being targeted. The NPC searches nearby underground/cave-exposed blocks for iron, coal, and copper ore variants, pathfinds to a valid stand position, equips the matching pickaxe temporarily if needed, re-checks the pickaxe before every breaking pass, animates block breaking with vanilla crack progress through `PlayerNpcEntity.showBlockBreakProgress`, plays the ore block's hit sound while mining, destroys the ore with vanilla drops, damages the pickaxe, and restores the previous main-hand item only when the ore goal stops. The final block break sound/effect is left to `ServerLevel.destroyBlock` and only plays after the ore is actually removed.

The ore search is deliberately limited to 16 blocks horizontally, 8 blocks below, and 6 blocks above the NPC. Do not expand the downward scan casually: deep/downhill ore targets caused repeated `mine coal ore -> return home -> managing home` loops when the target stand was physically valid but unreachable.

Ore stand positions must be directly close, have a completed `Path.canReach()` path, be reachable through this goal's bounded safe-drop helper, or have a very close local obstruction that can be mined. The wide ore scan collects ore positions first and only path-checks a capped nearest/priority subset; do not path-check every ore found in the volume. `moveToTarget()` also uses a concrete `Path` and rejects partial paths. Failed ore targets are remembered in a skipped set so the same unreachable coal block is not immediately reselected on the next ore attempt.

After mining one ore, the goal keeps the active vein as a cluster, remembers same-family ore positions connected to the starting ore, scans neighboring positions around known/mined vein blocks, and continues mining the nearest reachable block in that cluster before looking for a different ore deposit. This makes exposed and newly exposed coal/iron/copper veins behave more like tree log farming instead of mining one block and wandering away. Ore cooldown is short after a successful cluster pass and longer after a failed attempt; no cooldown is applied while switching between blocks in the same cluster.

When the selected ore stand position is below the NPC and normal navigation is done or stuck, the goal can nudge the NPC into a nearby safe drop with open body space and a floor within a few blocks. This is scoped to cave ore exploration so rough cave descents do not globally change every navigation goal.

If the NPC is blocked while moving to an ore stand, the goal can temporarily mine a nearby path obstruction with the current/appropriate pickaxe rules, shows `clearing ore path ...` in inspector detail, then resumes the original ore target. It skips protected home blocks, fluids, unbreakable blocks, and block entities while clearing path obstructions.

While exploring between ore blocks and not mid-break, the goal can randomly craft torches from one coal/charcoal plus one stick into four torches through `PlayerNpcCraftingUtil.tryCraftTorches`, then place a torch in a low block-light cave spot if no torch is already nearby.

The goal clears crack progress when the NPC leaves breaking range, gives up, completes mining, or stops.

Inspector detail shows the ore block id, coordinates, and mining progress.

Cooldown uses `PlayerNpcEntity.oreMiningCooldown`.
