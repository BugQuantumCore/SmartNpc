# Player NPC Item Durability

## Source

- `src/main/java/com/pla/player_npc/entity/PlayerNpcEntity.java`

## Rule

Player NPCs should damage durability on items they actively use.

Current helper methods:

- `hurtMainHandItem(int amount)`
- `hurtItemInHand(InteractionHand hand, int amount)`
- `hurtHeldOrInventoryItem(Predicate<ItemStack> matcher, int amount)`

## Current Uses

- Melee hits damage the main-hand item by 1 after a successful `doHurtTarget`.
- Bow shots damage the bow by 1 after firing.
- Material gathering damages the main-hand mining tool by 1 after a block is destroyed.
- Cave ore exploration damages the main-hand pickaxe by 1 after an ore block is destroyed.
- Temporary crafting-table recovery damages the main-hand tool by 1 after the table is destroyed.
- Farming damages a held or inventory hoe by 1 when tilling/planting.
- Fishing damages the active fishing rod by 1 when retrieving loot.
