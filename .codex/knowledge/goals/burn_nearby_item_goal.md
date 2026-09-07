# BurnNearbyItemGoal

## Source

- `src/main/java/com/pla/smart_npc/entity/goal/BurnNearbyItemGoal.java`
- Registered from `PlayerNpcEntity.registerGoals()` as a priority-5 routine work goal
  with speed `1.0D` and radius `10.0D`.

## Purpose

Lets idle Player NPCs dispose of unwanted dropped items without directly deleting every item in a loop.

## Activation

- Server side only.
- NPC must be idle with no combat target.
- Uses the normal startup and adaptive worker scheduler through `addWorkGoal`; priority-3
  nearby-item pickup gets the first chance to collect useful drops.
- Chooses one random grounded item in range.
- Can pick up/equip useful dropped items without a burn tool.
- Burns unwanted dropped items only when the NPC has flint and steel or a lava bucket in its inventory.

## Behavior

Walks to the item and first tries to pick it up, equip it, or store it if useful. If the item is unwanted and the NPC has a burn tool, it temporarily equips the tool and places fire or lava in the grounded ItemEntity's exact current block cell. Vanilla fire/lava contact owns damage and destruction; the goal never selects and directly discards an ItemEntity.

Flint and steel is preferred when fire can survive in that exact cell. If fire cannot be placed or the NPC has no flint and steel, the goal can use a lava bucket in the same cell. Lava bucket use consumes the lava bucket and returns an empty bucket, matching `UseLavaBucketGoal`.

Ignition is commit-time revalidated against the still-alive grounded target, loaded chunks, world bounds, the world border, Player NPC home/build footprints, and owned farm/work/entrance blocks. It does not use an above-item or neighboring fallback that could ignite a different position.

The ignition cell cannot overlap the acting NPC or another living entity. Fire-resistant
item stacks are not selected for disposal because vanilla fire/lava damage intentionally
leaves them alive. Lava is reclaimed before its first Nether fluid tick can spread; ordinary
fire retains the existing short burn window.

Temporary fire/lava is removed after the short burn window or any goal interruption so the goal does not leave uncontrolled hazards behind. Burn chat describes the successfully placed ignition attempt and continues through the selector-aware `burn_item` datapack event.
