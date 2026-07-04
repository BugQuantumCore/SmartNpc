# ShieldGuardGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/ShieldGuardGoal.java`
- Registered only from `PlayerNpcEntity.registerVanillaCombatReplacementGoals()`, so it only runs when `epicfight_player_npc` is not loaded.
- Damage blocking support is in `PlayerNpcEntity.tryBlockDamageWithShield`.
- Render support is in `FakePlayerRenderer`.

## Purpose

Lets Player NPCs use shields defensively during vanilla replacement combat.

## Behavior

If the NPC has a shield in the offhand or inventory, it can guard against the current target. Ranged threats using bows, crossbows, or projectile weapons are prioritized. The shield is temporarily placed in the offhand when needed, `startUsingItem(InteractionHand.OFF_HAND)` is called for the guarding pose, and movement is slowed to `0.45D`.

Incoming damage is blocked while the NPC is actively using an offhand shield and the damage source is in front of the NPC. Blocked damage plays the shield block sound, cancels the hit, and damages the shield durability.

Inspector state is `ai.player_npc.shield_guarding`.

Cooldown uses `PlayerNpcEntity.shieldGuardCooldown`.
