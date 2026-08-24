# Player NPC Knowledge

## Source Scope

- `src/main/java/com/pla/annoyingvillagers/entity/PlayerNpcEntity.java`
- `src/main/java/com/pla/annoyingvillagers/clazz/FakePlayer.java`
- `src/main/java/com/pla/annoyingvillagers/client/renderer/FakePlayerRenderer.java`
- `src/main/java/com/pla/annoyingvillagers/client/renderer/FakePlayerTextureUtils.java`
- `src/main/java/com/pla/annoyingvillagers/client/renderer/FakePlayerCapeLayer.java`
- `src/main/java/com/pla/annoyingvillagers/event/NpcGearLoadEvent.java`
- `src/main/java/com/pla/annoyingvillagers/util/EquipmentDataLoader.java`
- `src/main/java/com/pla/annoyingvillagers/util/InventoryUtils.java`
- `src/main/java/com/pla/annoyingvillagers/util/BowFunction.java`
- `src/main/java/com/pla/annoyingvillagers/util/CombatBehaviour.java`
- `src/main/java/com/pla/annoyingvillagers/combatbehaviour/CombatCommon.java`
- `src/main/java/com/pla/annoyingvillagers/entity/goal/FillWaterBucketGoal.java`
- `src/main/java/com/pla/annoyingvillagers/event/AnnoyingVillagersCommandEvent.java`
- `src/main/resources/data/annoyingvillagers/mobs_equipment/*.json`

## Entity Shape

`PlayerNpcEntity` extends `FakePlayer` and implements `RangedAttackMob`.

`FakePlayer` extends `PathfinderMob`, so Player NPC uses normal pathfinder mob spawning and navigation behavior instead of zombie-specific surface-spawn behavior.

`PlayerNpcEntity` persists custom state such as inventory, cooldowns, per-NPC raw-log/wood/cobble reserve targets, main/offhand weapon snapshots, bow usage, owned home chest position, block projectile chance, and disarmed state.

AI cooldowns are explicit integer fields on `PlayerNpcEntity`, saved with the entity and decremented every server tick. Player NPC goals should not use ad hoc persistent-data timestamp tags for `PlayerNpc...Cooldown` values.

## Spawn Command

`AnnoyingVillagersCommandEvent` registers:

```mcfunction
/annoyingvillagers spawn_player <name>
```

The command requires permission level 2. It creates `PLAYER_NPC`, moves it to the command source position/rotation, calls `entity.setUsername(name)`, then calls `finalizeSpawn` with `MobSpawnType.COMMAND`, adds the entity to the level, and reports the spawned name.

Because the username is set before `finalizeSpawn`, the command-provided name drives the Minecraft profile, skin, and cape lookup.

## Fake Player Name System

`FakePlayer` owns the synced username field:

```java
private static final EntityDataAccessor<String> NAME
```

If no username exists during `FakePlayer.finalizeSpawn`, it assigns a hardcoded random name.

Names are represented by `FakePlayer.FakePlayerName`.

`FakePlayerName` accepts either:

- `skinName`
- `skinName:displayName`

`skinName` is used for Minecraft profile lookup. `displayName` is used for entity display if present. If no display name is present, display falls back to skin name.

The hardcoded name list is:

`Gory_Moon`, `Darkosto`, `Darkere`, `Darkhax`, `Emberwalker`, `Gigabit101`, `Kamefrede`, `KnightMiner_`, `Lat`, `LexManos`, `Mrbysco`, `P3pp3rF1y`, `Ray`, `Ridanis`, `SOTMead`, `ShyNieke`, `SkySom`, `Soaryn`, `ValkyrieofNight`, `XCompWiz`, `DaReal_BingoBear`, `darkphan`, `direwolf20`, `dmodoomsirius`, `dmodoomsirius`, `malte0811`, `nekosune`, `neptunepink`, `vadis365`, `wyld`, `paulsoaresjr`, `Mhykol`, `Vswe`, `TurkeyDev`, `Gen_Deathrow`, `Sevadus`.

Random-name algorithm:

1. `NAME_POOL` is a queue.
2. If the queue is empty, copy the hardcoded list.
3. Shuffle it with `Collections.shuffle(shuffled, new java.util.Random(random.nextLong()))`.
4. Add all shuffled names to the queue.
5. Poll one name.
6. If polling somehow returns null, fallback to `Steve`.
7. When a name is explicitly used, `useName` removes it from the queue to reduce duplicate immediate reuse.

Username/profile data is saved to NBT:

- `Username`
- complete `Profile`, when available

On load, `FakePlayer` restores `Username`, restores `Profile` when present, or chooses a hardcoded name server-side when no username exists.

Hardcoded names also carry Player NPC interests such as `BUILDING`, `FISHING`, `MINING`, `HUNT_MONSTERS`, `HUNT_ANIMALS`, `HUNT_PLAYERS`, `HUNT_VILLAGERS`, `EXPLORING`, `LOOTING`, `FARMING`, and `CAUTIOUS`. Log gathering, baseline stone gathering, basic crafting, and gear upgrading are baseline AI rather than personality interests. See `.codex/knowledge/player_npc_interests.md`.

## Minecraft Profile, Skin, Cape, And Elytra Fetch

`setUsername` resets profile and texture state when the name changes, then calls `getProfile`.

`getProfile` creates:

```java
new GameProfile(null, this.getUsername().getSkinName())
```

Then it queues the entity in `PROFILE_QUEUE`.

The background profile updater runs on daemon thread:

```java
"AnnoyingVillagers FakePlayer Profile Updater"
```

It calls:

```java
SkullBlockEntity.updateGameprofile(currentProfile, target::setProfile)
```

That completes the Minecraft/Mojang profile for the skin name. `setProfile` stores the completed profile and clears cached texture state.

Client texture lookup is in `FakePlayerTextureUtils`.

Skin type:

- `getPlayerSkinType(profile)` uses `Minecraft.getInstance().getSkinManager().getInsecureSkinInformation(profile)`.
- It checks the skin texture metadata `model`.
- `model=slim` uses slim player model.
- Otherwise it uses default player model.
- If no skin texture exists, it falls back to `DefaultPlayerSkin.getSkinModelName(id)`.

Skin texture:

- `getPlayerSkin(entity)` tries the entity cached SKIN texture first.
- If profile is complete, fallback is `DefaultPlayerSkin.getDefaultSkin(profile.getId())`.
- If no complete profile exists, fallback is vanilla default skin.

Cape texture:

- `getPlayerCape(entity)` fetches CAPE texture through the same helper.
- `FakePlayerCapeLayer` renders the cape only if the entity is visible, cape texture exists, and the chest slot is not an elytra.

Texture registration:

```java
MinecraftProfileTexture profileTexture =
    minecraft.getSkinManager().getInsecureSkinInformation(profile).get(type);
ResourceLocation location = minecraft.getSkinManager().registerTexture(profileTexture, type);
entity.setTexture(type, location);
```

`FakePlayer` caches SKIN, CAPE, and ELYTRA `ResourceLocation`s and availability booleans.

## Renderer

`FakePlayerRenderer` uses a `PlayerModel`.

It keeps both default and slim player models. Each render call checks the skin type, swaps the model, and swaps matching armor layer variants.

It sets hand pose from the mainhand item:

- crossbow charging/holding pose for crossbow
- bow pose for aggressive bow use
- item pose for other held items

The renderer scales the entity by `0.9375F`.

Main texture location is `FakePlayerTextureUtils.getPlayerSkin(entity)`.

## Gear Reloading

`AnnoyingVillagers` registers `NpcGearLoadEvent` on the Forge event bus.

`NpcGearLoadEvent.onAddReloadListeners` adds:

```java
new EquipmentDataLoader()
```

`EquipmentDataLoader` is a `SimpleJsonResourceReloadListener` rooted at:

```text
mobs_equipment
```

It loads JSON files from:

```text
src/main/resources/data/annoyingvillagers/mobs_equipment/*.json
```

The JSON file path becomes the default namespace. For example:

- `minecraft.json` turns `"diamond_sword"` into `minecraft:diamond_sword`
- `epicfight.json` turns `"diamond_greatsword"` into `epicfight:diamond_greatsword`
- `annoyingvillagers.json` turns `"knife"` into `annoyingvillagers:knife`

If an item id already contains `namespace:path`, it is used as-is.

The loader skips a whole file when `ModList.get().isLoaded(modId)` is false.

Supported slots:

- `MAINHAND`
- `OFFHAND`
- `HEAD`
- `CHEST`
- `LEGS`
- `FEET`

Gear entries can be strings or objects.

String entry:

```json
"stone_sword"
```

This defaults to minimum difficulty EASY.

Object entry:

```json
{ "id": "diamond_sword", "min_difficulty": "HARD" }
```

Invalid object entries are skipped. Unknown `min_difficulty` logs a warning and defaults to EASY.

All candidate item ids are validated against `ForgeRegistries.ITEMS`.

## Player NPC Gear Application

`PlayerNpcEntity.finalizeSpawn` calls:

```java
EquipmentDataLoader.getEquipCommands(0.85f, this)
```

The returned commands are executed as the entity with suppressed output and permission 4:

```java
item replace entity @s <slot> with <item>{Damage:<damage>}
```

Slot mapping:

- `MAINHAND` -> `weapon.mainhand`
- `OFFHAND` -> `weapon.offhand`
- `HEAD` -> `armor.head`
- `CHEST` -> `armor.chest`
- `LEGS` -> `armor.legs`
- `FEET` -> `armor.feet`

Damageable gear receives random damage between 1/3 and 3/4 of max durability.

After commands execute, Player NPC snapshots current mainhand and offhand into:

- `mainWeaponItem`
- `offWeaponItem`

`PlayerNpcEntity.finalizeSpawn` also calls `PlayerNpcEntity.seedInventory()`.

`PlayerNpcEntity.seedInventory()` owns the spawn inventory seed directly. It first checks that the 27 slot container is empty, then rolls progression-scaled food, arrows, pearls, bucket access, flint and steel access, block stacks, and material loot inside `PlayerNpcEntity` itself. `InventoryUtils` is only used for low-level inventory operations such as adding, checking, consuming, and dropping items.

## Inventory Backed Combat Supplies

Player NPC has a 27 slot `SimpleContainer` inventory that saves to and loads from NBT.

The entity exposes two inventory APIs:

- `hasInventoryItem(...)`
- `consumeInventoryItem(...)`

Both have predicate and exact-item overloads and delegate to `InventoryUtils`.

On spawn, an empty Player NPC inventory receives a difficulty-scaled hardcoded random utility loadout:

- EASY: 0-5 golden apples, no enchanted golden apples, two regular food stacks of 12-20 each, no arrows, no ender pearls, no water bucket, and 2-4 random block stacks of 8-16 each.
- MEDIUM: 8-16 golden apples, no enchanted golden apples, two regular food stacks of 20-32 each, 12-32 arrows, 0-12 ender pearls, one water bucket, a 30% flint and steel chance, and 2-4 random block stacks of 16-32 each.
- HARD: 16-32 golden apples, 0-6 enchanted golden apples, two regular food stacks of 32-64 each, 48-96 arrows, 16-32 ender pearls, one water bucket, a 50% flint and steel chance, and 2-4 random block stacks of 32-96 each.

The same seeding pass builds a progression-scaled material candidate pool, then randomly adds zero to two material types total:

- EASY: often no material loot; otherwise small coal and/or iron ingot counts.
- MEDIUM: coal and iron, with chances for gold ingots and redstone.
- HARD: larger coal, iron, gold, and redstone counts, plus lapis lazuli and chances for diamonds and emeralds.

Hard mode inventory also has rare utility rolls:

- spyglass, for `UseSpyglassGoal`,
- jukebox plus one random music disc, for `JukeboxDanceGoal`.

Player NPC continues to pick up nearby items into this same container. Food, arrows, ender pearls, buckets, and block items can refill combat supplies after spawn. World item pickup uses a one-block expansion on every axis around the NPC bounding box (the surrounding 3x3x3 block neighborhood, including above and below), honors the item's pickup delay, and sends the vanilla take-item packet so clients render the pickup flight and sound.

Inventory-backed actions:

- bow behavior requires arrows in inventory,
- each bow shot consumes one arrow-like item from inventory,
- ender pearl throws require and consume one ender pearl,
- eating requires one available food item and consumes it only after the eating animation completes,
- item burning requires flint and steel or a lava bucket in inventory,
- chest looting skips the NPC's owned home chest, requires an adjacent standing position, opens/closes the chest, and moves acceptable world-chest items sequentially into the custom inventory,
- cave ore exploration is a `MINING` interest behavior that can mine cave-adjacent iron/coal/copper ore with a pickaxe, clear local path obstructions, craft torches from coal/charcoal and sticks, and randomly place torches in dark cave spots while not mid-break,
- cooking is baseline AI; it can place/craft a home furnace, place/recover a `cooking`-kind temporary furnace when away from home, insert raw food, smeltable ore, cobblestone/cobbled deepslate, and fuel, and collect cooked output. Its placement detail reports the pending smelting reason, and night-camp recovery ignores cooking-kind temporary furnaces,
- home supply checking is baseline AI; once per Minecraft day near home, the NPC checks its chest first and furnace second for needed food, wood, fuel, arrows, blocks, utility supplies, and furnace output before falling back to gathering/exploring,
- gear upgrading can turn cobblestone/cobbled deepslate into stone tools, iron ingots into iron tools/armor, and diamonds into diamond tools/armor near a crafting table,
- farming can harvest mature crops and plant a small wheat patch near the home,
- sapling planting can place carried saplings on valid ground,
- biome log exploration can move a low-supply or raw-log-starved NPC in one chosen direction when no logs are within 48 blocks, then yield immediately once logs enter scan range so material gathering can harvest a connected tree in 4-8 log batches, refill a 4-12 raw-log reserve, and use dirt-only pillaring for high connected logs,
- boat collector NPCs can stock 2-3 boats near a crafting table,
- boat trap combat can place a boat on monster targets only, never players or Player NPCs,
- jukebox dancing can place a home jukebox, insert a disc, sneak/jump dance near active jukeboxes, and rarely disturb another dancing NPC,
- troll hit can rarely hit any valid nearby living non-allied target except creative players, clear the target, then run away instead of trying to kill them,
- combat fishing can temporarily put a fishing rod in the offhand during vanilla replacement combat and pull the current target,
- shield crafting can consume six planks and one iron ingot near a crafting table to craft a shield,
- shield guard can temporarily put a shield in the offhand during vanilla replacement combat, slow movement, block incoming damage, and consume shield durability,
- regular food directly restores exactly 4 HP, capped at max health, and applies no absorption,
- golden apples keep their special absorption/regeneration behavior,
- water bucket self-extinguish consumes a water bucket and returns a water bucket only when the placed source is recovered, otherwise it returns an empty bucket,
- empty buckets can be refilled by `FillWaterBucketGoal` from nearby source water when Player NPC has no active target,
- block escape/parry placement requires block items and consumes one block per placed block.

Player NPC death loot is inventory-backed. `PlayerNpcEntity.dropCustomDeathLoot` drops only remaining container contents, and the old delayed `PlayerNpcDeadEvent` generated item drops are skipped for Player NPC.

`PlayerNpcEntity.equipBetterGearFromInventory()` upgrades armor and main-hand gear from the custom inventory. It is called after nearby item pickup, after chest loot transfers, and after gear crafting produces a better tool, so looted or crafted chestplates, swords, tools, bows, and similar gear can be equipped when better than current gear. Main-hand auto-upgrades are paused while `PlayerNpcEntity.isHealing()` is true or while temporary main-hand goals such as material gathering, cave ore mining, hole escape, and home management are active, so food/tools/placement blocks are not replaced before the goal finishes. High-priority weapon recovery is also blocked while healing.

`PickupNearbyItemGoal` actively pathfinds to useful dropped items in a short 8 block radius when the NPC is idle and not healing or fighting. This covers nearby loot without letting item collection starve material gathering. Useful dropped supplies are defined by `InventoryUtils.isInventoryBackedSupplyDrop(...)`, which includes food, placeable blocks, utility materials, saplings, and common animal drops. When an animal target dies, `PlayerNpcEntity` stores a short post-kill loot priority at the death position, clears the animal target, and lets pickup briefly search a wider local area around the kill site until drops spawn. `PlayerNpcSmartTargetGoal` and `HuntSheepForBedGoal` yield new animal targets while collectable supply drops are nearby, and `BurnNearbyItemGoal` reserves those supply drops from burning when they cannot be picked up.

Post-kill loot priority must not block resource AI for long if no drop entity appears. `PickupNearbyItemGoal` releases an empty animal-drop wait quickly so gather/log exploration can resume.

Fresh or low-supply Player NPCs prioritize tree logs before passive animal hunting. `PlayerNpcEntity.shouldPrioritizeLogGathering()` stays true while the NPC has fewer than four raw logs, or while low wood supply and missing starter tools make logs urgent. Smart animal targeting and sheep-for-bed hunting both yield while this is true.

Gradual Player NPC block-breaking goals should call `PlayerNpcEntity.showBlockBreakProgress(pos, elapsedTicks, requiredTicks)` while mining and `clearBlockBreakProgress(pos)` when leaving range, switching targets, finishing, failing, or stopping. This uses vanilla client crack overlays like player block breaking.

## Inspector Overlay

`PlayerNpcEntity` syncs both a broad AI state and a free-form AI detail string to clients. The inspector overlay renders the state as `AI playing:` and the detail as `Task:`.

The inspector also shows name interests and build status:

- `Interests:` comes from the hardcoded fake-player name definition.
- `Build:` comes from `PlayerNpcBuildStatusUtil` through the inspector snapshot packet.

`GatherMaterialsGoal` uses the detail field to show the block id and coordinates it is trying to mine, for example `minecraft:oak_log @ 12 64 -8`.

`PlayerNpcInspectorOverlay` renders from `RenderGuiEvent.Post` once per HUD frame and caches formatted display text for 500 ms, similar to Minecraft's F3-style throttled debug text updates. Network refreshes still use the 10 tick server request interval.

The inspector item, inventory snapshot packets, inspectator camera mode, and Shift/dismount patch are documented in `.codex/knowledge/player_npc_inspector.md`.

## Home And Build Data

`PlayerNpcHomeUtil` stores a per-NPC home origin plus width/depth in persistent entity data. `BuildHouseGoal` chooses a clear, flat layout footprint before saving the home area, and `ManageHomeBaseGoal` uses that home for crafting table, bed, chest, and storage behavior. The saved home area is also a protected resource volume, so material gathering and biome log scanning skip house blocks instead of harvesting the NPC's own shelter. Local resource goals also stay within a 96 block home radius when the NPC has a home and does not have `EXPLORING`; NPCs without a home, or NPCs with `EXPLORING`, can range freely. `ReturnHomeGoal` can bring a nearby NPC back to that area for storage, crafting, cooking, or sleeping instead of letting those utility goals operate from a distance.

For first-build bootstrap, Player NPCs roll raw-log, wood, and cobblestone targets from 12, 16, 20, 24, or 32. A BUILDING NPC should gather raw logs to its target, select and save a `.blueprint` base area, craft a wooden shovel if terraform clearing needs one, terraform the footprint, then gather/dig cobble near but outside that base before returning to build.

The `workers-1.20.1-2.0.3_decompiled` reference uses compressed NBT scan files through `StructureManager` (`scanStructure`, `saveStructureToFile`, `loadScanNbt`, and default `structures/*.nbt` resources). Player NPC building now uses Structurize `.blueprint` resources under `src/main/resources/data/player_npc/builds/` or datapack `data/<namespace>/builds/`.

`PlayerNpcBuildLayoutLoader` loads `.blueprint` files through `PlayerNpcBlueprintLayoutReader`, which unpacks Structurize v1 compressed-NBT `palette` and `blocks` data into canonical `PlayerNpcBuildLayout.RelativeBlock` entries. The old 1000 generated role-based JSON resources and the hand-authored JSON example were removed. The JSON parser remains only as a compatibility path for old local packs.

`BuildHouseGoal` compares target block states against the world through `PlayerNpcBuildMaterialUtil`, so exact blocks and accepted material-family substitutes both count as built. It clears safe conflicts including blueprint air volume, consumes or crafts target block items or accepted substitutes, applies scanned block entity NBT after placement, and stops early on missing required materials. Multi-block items such as bed heads and upper door halves do not require a second item and derive their material from the first half when possible. `FarmCropGoal` treats the house as finished when all required layout blocks are satisfied by exact states or accepted substitutes.

Player NPC now clears stale combat AI state when its target is gone, so a dead or removed target should not leave the inspector stuck on melee/engaging forever.

Player NPC also clears stale live combat targets when no hit progress has happened for several seconds and the target is too far away or blocked behind failed navigation. This prevents a live but unreachable target from blocking every utility goal through `getTarget() != null`.

Do not use a fallback movement goal for "staying busy". `PlayerNpcStayBusyGoal` was removed because it could own MOVE/LOOK, write navigation targets, and wake resource cooldowns immediately before `ReturnHomeGoal` or `GatherMaterialsGoal` took over, causing direction flips and spin loops. `PlayerNpcEntity` keeps only the taskless idle watchdog: if synced AI state remains idle for about one second while the NPC is not fighting, healing, sleeping, or riding, it wakes real worker cooldowns but does not claim an AI state. Real goals such as gather, return-home, farm, cave exploration, and build/home management must be the only systems that move the NPC toward work.

## Gear Difficulty Filtering

Equipment entries are stored as:

```java
EquipmentEntry(String itemId, Difficulty minDifficulty)
```

An item is available when:

```java
currentDifficulty.ordinal() >= minDifficulty.ordinal()
```

This means higher difficulty can still roll lower-difficulty gear.

Current equip chances:

- EASY mainhand: 15%
- EASY offhand: 3%
- EASY armor slot: 8%
- MEDIUM mainhand: 65%
- MEDIUM offhand: 35%
- MEDIUM armor slot: 45%
- HARD mainhand: 95%
- HARD offhand: 100%
- HARD armor slot: caller base chance, currently 85%

This makes EASY Player NPC spawns very likely to have empty armor and empty hands.

## Armor Set Matching

Armor slots can loosely match previous armor pieces.

The loader detects armor prefixes by suffix:

- head: `helmet`
- chest: `chestplate`
- legs: `leggings`, `legging`
- feet: `boots`, `boot`

When a previous armor item exists, there is a random 30-50% chance to prefer an item with the same prefix in the next armor slot.

## Offhand Generation

The loader can generate offhand items from the selected mainhand.

Main sources:

- bound offhand weapon map for specific custom weapons
- shield if EpicFight capability says the weapon can use shield
- mirrored or related weapon if the weapon can two-hand
- optional dual axe / dual greatsword support when those mods are loaded

Generated offhand pools are filtered by inferred minimum difficulty.

Vanilla and shield-like offhand spawns are now gated to MEDIUM difficulty only, with a 30% shield roll. HARD no longer receives generated shield offhand equipment from the loader.

The inferred difficulty uses item id path keywords:

- HARD: `netherite`, `diamond`, `unlight`, `ruby`, `exterminator`, `blackscratcher`, `laevateinn`, `moon_blade`, `armblade`
- MEDIUM: `iron`, `gold`, `chainmail`, `turtle`, `jade`, `red_axe`
- EASY: everything else

Some weapons are blacklisted from random offhand generation, including moon blades, armblade, claw, cleaver, sabre, blackscratcher, warblade, and laevateinn variants.

## Spawn Rules

`PlayerNpcEntity.canSpawn` rejects spawning at night:

```java
if (serverLevel.isNight()) {
    return false;
}
```

Otherwise it delegates to:

```java
PathfinderMob.checkMobSpawnRules(...)
```
