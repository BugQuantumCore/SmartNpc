# Player NPC Alerts And Movement

## Death Alerts

`ChatUtil.warnDeath` uses wildcard/default entries only for player-like killers:

- `net.minecraft.world.entity.player.Player`
- `PlayerNpcEntity`

Other entity types only receive custom `warn_death` or `death_reaction` chat when a
datapack entry explicitly targets that entity's namespaced registry ID. This supports
modded threats without making wildcard/default griefing lines fire for ordinary monster
kills. With no explicit match, the NPC keeps the vanilla death summary.

Death event chat is emitted through `ChatUtil.reportDeath`; `PlayerNpcAlertManager.raiseDeathAlert`
owns only the nearby AI alert. `PlayerNpcEntity` still gates the AI death alert to
player-like killers, so an explicitly configured modded-mob chat reaction does not
silently change nearby NPC combat/avoidance behavior.

## Movement Speed Cap

Use movement speed `1.0D` as the maximum requested path/navigation speed for Player NPC goals.

Known examples:

- `RecoverWeaponInCombatGoal` is registered on Player NPC at `1.0D`.
- vanilla `MeleeAttackGoal` is registered on Player NPC at `1.0D`.
- `PlayerNpcRangedBowAttackGoal` is registered on Player NPC at `1.0D`.
- `LowHealthFleeGoal.RUN_SPEED = 1.0D`
- `RespondToNpcAlertGoal.AVOID_SPEED = 1.0D`

Do not raise Player NPC run, flee, avoid, chase, recover, or combat navigation to `1.25D+`.

Eating is separate: `EatHealingFoodGoal` must not sprint while eating, and uses slower player-like movement while backing away or chasing.

Player NPC jump helpers should stay player-like. `PlayerNpcEntity.jump()` and `shortPillarJump()` use vanilla-style `0.42D` vertical lift; do not raise this back to high values that make the NPC look like it can jump around two blocks.

## Cooldown Style

Player NPC AI goals should use explicit tick-down fields on `PlayerNpcEntity` for per-NPC cooldowns. Do not add new `getPersistentData().putLong("PlayerNpc...Cooldown", gameTime + ticks)` cooldown tags for Player NPC goals.

Existing AI cooldown fields are saved on the entity and decremented from `PlayerNpcEntity.tick()` through `tickAiCooldowns()`.
