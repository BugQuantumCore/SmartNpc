# Player NPC AI Cooldowns

## Rule

Player NPC goals should use explicit integer cooldown fields on `PlayerNpcEntity`, saved with the entity and decremented every server tick by `PlayerNpcEntity.tickAiCooldowns()`.

Do not add new persistent-data timestamp cooldown tags such as:

```java
playerNpc.getPersistentData().putLong("PlayerNpcSomeCooldown", serverLevel.getGameTime() + ticks);
```

## Current Fields

- `gapCooldown`: eating/healing food.
- `bucketCooldown`: water and lava bucket use.
- `enderPearlCooldown`: combat and water escape pearl throws.
- `swapToBowCooldown`: ranged bow temporary swap.
- `placeBlockParryCooldown`: projectile block parry.
- `helpAlertCooldown`: `CallForHelpGoal`.
- `holeEscapeCooldown`: `EscapeHoleWithBlockGoal`.
- `rareSneakCooldown`: `RareSneakGoal`.
- `scaredHideCooldown`: `ScaredHideGoal`.
- `buildHouseCooldown`: `BuildHouseGoal`.
- `cookFoodCooldown`: `CookFoodGoal`.
- `craftGearCooldown`: `CraftBasicGearGoal`; `GatherMaterialsGoal` checks this before deferring to crafting.
- `farmCooldown`: `FarmCropGoal`.
- `gatherCooldown`: `GatherMaterialsGoal`.
- `huntSheepCooldown`: `HuntSheepForBedGoal`.
- `ironGolemTrollCooldown`: `IronGolemTrollGoal`.
- `lootChestCooldown`: `LootNearbyChestGoal`.
- `manageHomeCooldown`: `ManageHomeBaseGoal`.
- `fishingCooldown`: `PlayerNpcFishingGoal`.
- `returnHomeCooldown`: `ReturnHomeGoal`.
- `sleepCooldown`: `SleepAtHomeGoal`.
- `craftCooldown`: `UtilityCraftingGoal`.
- `oreMiningCooldown`: `ExploreCaveOreGoal`.
- `ironGearCooldown`: `CraftIronGearGoal`.
- `spyglassCooldown`: `UseSpyglassGoal`.
- `saplingPlantCooldown`: `PlantSaplingGoal`.
- `boatStockCooldown`: `BoatStockpileGoal`.
- `boatTrapCooldown`: `BoatTrapMonsterGoal`.
- `jukeboxDanceCooldown`: `JukeboxDanceGoal`.
- `trollHitCooldown`: `TrollHitGoal`.
- `combatFishingCooldown`: `CombatFishingRodGoal`.
- `shieldCraftCooldown`: `CraftShieldGoal`.
- `shieldGuardCooldown`: `ShieldGuardGoal`.

Old non-PlayerNpc helper code can still have its own legacy timer tags, but Player NPC AI goals should use the entity fields above.

Daily state markers are allowed when they are not tick cooldowns. `CheckHomeSuppliesGoal` uses persistent day markers (`PlayerNpcLastHomeChestCheckDay` and `PlayerNpcLastHomeFurnaceCheckDay`) so a home chest/furnace is checked at most once per Minecraft day and naturally becomes eligible again when `serverLevel.getDayTime() / 24000L` changes.
