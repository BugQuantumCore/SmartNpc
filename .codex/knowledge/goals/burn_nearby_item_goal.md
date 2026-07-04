# BurnNearbyItemGoal

## Source

- `src/main/java/com/pla/player_npc/entity/goal/BurnNearbyItemGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()`.

## Purpose

Lets idle Player NPCs dispose of unwanted dropped items without directly deleting every item in a loop.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Chooses one random grounded item in range.
- Can pick up/equip useful dropped items without a burn tool.
- Burns unwanted dropped items only when the NPC has flint and steel or a lava bucket in its inventory.

## Behavior

Walks to the item and first tries to pick it up, equip it, or store it if useful. If the item is unwanted and the NPC has a burn tool, it temporarily equips the tool in its main hand, swings, burns one grounded item, then restores the cached main weapon.

Flint and steel is preferred when fire can survive near the item. If fire cannot be placed or the NPC has no flint and steel, the goal can use a lava bucket instead. Lava bucket use consumes the lava bucket and returns an empty bucket, matching `UseLavaBucketGoal`.

Temporary fire/lava is removed after the burn so the goal does not leave uncontrolled hazards behind.
