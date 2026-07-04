# UseSpyglassGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/UseSpyglassGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.
- Render support is in `src/main/java/com/pla/player_npc/client/renderer/FakePlayerRenderer.java`.

## Purpose

Lets idle Player NPCs use a spyglass when they have one.

## Behavior

Hard mode spawn inventory has a rare chance to include a spyglass. Any NPC that has a spyglass can temporarily equip it, call `startUsingItem(InteractionHand.MAIN_HAND)`, look at a distant living entity or scan the horizon, then restore the previous main-hand item.

The renderer maps active spyglass use to `HumanoidModel.ArmPose.SPYGLASS`, so the model uses the player-like spyglass pose.

Cooldown uses `PlayerNpcEntity.spyglassCooldown`.
