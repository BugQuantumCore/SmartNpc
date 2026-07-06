# Player NPC Alerts And Movement

## Death Alerts

`ChatUtil.warnDeath` is only for player-like killers:

- `net.minecraft.world.entity.player.Player`
- `PlayerNpcEntity`

Do not show the "everyone be careful, <name> is griefing" style messages for zombies, creepers, or other monster kills.

Death warning chat is emitted from `PlayerNpcAlertManager.raiseDeathAlert`, not directly from `PlayerNpcEntity.handlePlayerNpcDeathChat`, so the warning and the AI alert stay paired. `PlayerNpcDeadEvent` also gates `PlayerNpcAlertManager.raiseDeathAlert` with the same player-like check, so nearby Player NPCs only choose to avoid/attack the death-alert threat when the killer is a player or Player NPC.

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
