package com.pla.player_npc.entity;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.pla.player_npc.clazz.Difficulty;
import com.pla.player_npc.clazz.FakePlayer;
import com.pla.player_npc.clazz.PlayerNpcTarget;
import com.pla.player_npc.entity.goal.BuildHouseGoal;
import com.pla.player_npc.entity.goal.BurnNearbyItemGoal;
import com.pla.player_npc.entity.goal.BoatStockpileGoal;
import com.pla.player_npc.entity.goal.BoatTrapMonsterGoal;
import com.pla.player_npc.entity.goal.CallForHelpGoal;
import com.pla.player_npc.entity.goal.CombatFishingRodGoal;
import com.pla.player_npc.entity.goal.CookFoodGoal;
import com.pla.player_npc.entity.goal.CraftBasicGearGoal;
import com.pla.player_npc.entity.goal.CraftIronGearGoal;
import com.pla.player_npc.entity.goal.CraftShieldGoal;
import com.pla.player_npc.entity.goal.EatHealingFoodGoal;
import com.pla.player_npc.entity.goal.EscapeHoleWithBlockGoal;
import com.pla.player_npc.entity.goal.ExploreCaveOreGoal;
import com.pla.player_npc.entity.goal.FillWaterBucketGoal;
import com.pla.player_npc.entity.goal.FarmCropGoal;
import com.pla.player_npc.entity.goal.GatherMaterialsGoal;
import com.pla.player_npc.entity.goal.HuntSheepForBedGoal;
import com.pla.player_npc.entity.goal.IronGolemTrollGoal;
import com.pla.player_npc.entity.goal.JukeboxDanceGoal;
import com.pla.player_npc.entity.goal.LootNearbyChestGoal;
import com.pla.player_npc.entity.goal.LowHealthFleeGoal;
import com.pla.player_npc.entity.goal.ManageHomeBaseGoal;
import com.pla.player_npc.entity.goal.PlayerNpcFishingGoal;
import com.pla.player_npc.entity.goal.PlayerNpcProjectileBlockGoal;
import com.pla.player_npc.entity.goal.PlayerNpcRangedBowAttackGoal;
import com.pla.player_npc.entity.goal.PlayerNpcSmartTargetGoal;
import com.pla.player_npc.entity.goal.RandomCombatJumpGoal;
import com.pla.player_npc.entity.goal.RareSneakGoal;
import com.pla.player_npc.entity.goal.RecoverWeaponInCombatGoal;
import com.pla.player_npc.entity.goal.RespondToNpcAlertGoal;
import com.pla.player_npc.entity.goal.ReturnHomeGoal;
import com.pla.player_npc.entity.goal.ScaredHideGoal;
import com.pla.player_npc.entity.goal.ShieldGuardGoal;
import com.pla.player_npc.entity.goal.SleepAtHomeGoal;
import com.pla.player_npc.entity.goal.PlantSaplingGoal;
import com.pla.player_npc.entity.goal.RetargetCloserThreatGoal;
import com.pla.player_npc.entity.goal.ThrowEnderPearlGoal;
import com.pla.player_npc.entity.goal.TrollHitGoal;
import com.pla.player_npc.entity.goal.UtilityCraftingGoal;
import com.pla.player_npc.entity.goal.UseLavaBucketGoal;
import com.pla.player_npc.entity.goal.UseWaterBucketGoal;
import com.pla.player_npc.entity.goal.UseSpyglassGoal;
import com.pla.player_npc.entity.goal.WaterEnderPearlEscapeGoal;
import com.pla.player_npc.init.PlayerNpcModEntities;
import com.pla.player_npc.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PlayMessages;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Predicate;

public class PlayerNpcEntity extends FakePlayer implements RangedAttackMob {
    private static final EntityDataAccessor<Integer> MAIN_HAND_ATTACK_ANIMATION_TICKS = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> AI_STATE = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> AI_DETAIL = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> DANCING = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final int MAIN_HAND_ATTACK_ANIMATION_DURATION = 10;
    private static final int PLACE_BLOCK_PARRY_COOLDOWN_TICKS = 60;
    public static final String AI_IDLE = "ai.player_npc.idle";
    private static final List<ItemLike> REGULAR_FOODS = List.of(
            Items.COOKED_BEEF,
            Items.BREAD,
            Items.COOKED_PORKCHOP,
            Items.COOKED_CHICKEN,
            Items.COOKED_MUTTON,
            Items.COOKED_COD,
            Items.COOKED_SALMON,
            Items.BAKED_POTATO,
            Items.CARROT,
            Items.APPLE
    );
    private static final List<ItemLike> PLACEABLE_BLOCKS = List.of(
            Items.COBBLESTONE,
            Items.MOSSY_COBBLESTONE,
            Items.DIRT,
            Items.OAK_PLANKS,
            Items.DARK_OAK_PLANKS,
            Items.STONE,
            Items.COBBLED_DEEPSLATE,
            Items.DEEPSLATE,
            Items.GRAVEL,
            Items.SAND
    );
    private static final List<ItemLike> MUSIC_DISCS = List.of(
            Items.MUSIC_DISC_13,
            Items.MUSIC_DISC_CAT,
            Items.MUSIC_DISC_BLOCKS,
            Items.MUSIC_DISC_CHIRP,
            Items.MUSIC_DISC_FAR,
            Items.MUSIC_DISC_MALL,
            Items.MUSIC_DISC_MELLOHI,
            Items.MUSIC_DISC_STAL,
            Items.MUSIC_DISC_STRAD,
            Items.MUSIC_DISC_WARD,
            Items.MUSIC_DISC_11,
            Items.MUSIC_DISC_WAIT,
            Items.MUSIC_DISC_OTHERSIDE,
            Items.MUSIC_DISC_RELIC,
            Items.MUSIC_DISC_5,
            Items.MUSIC_DISC_PIGSTEP
    );

    private final SimpleContainer inventory = new SimpleContainer(27);
    private int gapCooldown = 0;
    private int bucketCooldown = 0;
    private int enderPearlCooldown = 0;
    private int swapToBowCooldown = 0;
    private int helpAlertCooldown = 0;
    private int holeEscapeCooldown = 0;
    private int rareSneakCooldown = 0;
    private int scaredHideCooldown = 0;
    private int buildHouseCooldown = 0;
    private int cookFoodCooldown = 0;
    private int craftGearCooldown = 0;
    private int farmCooldown = 0;
    private int gatherCooldown = 0;
    private int huntSheepCooldown = 0;
    private int ironGolemTrollCooldown = 0;
    private int lootChestCooldown = 0;
    private int manageHomeCooldown = 0;
    private int fishingCooldown = 0;
    private int returnHomeCooldown = 0;
    private int sleepCooldown = 0;
    private int craftCooldown = 0;
    private int oreMiningCooldown = 0;
    private int ironGearCooldown = 0;
    private int spyglassCooldown = 0;
    private int saplingPlantCooldown = 0;
    private int boatStockCooldown = 0;
    private int boatTrapCooldown = 0;
    private int jukeboxDanceCooldown = 0;
    private int trollHitCooldown = 0;
    private int combatFishingCooldown = 0;
    private int shieldCraftCooldown = 0;
    private int shieldGuardCooldown = 0;
    private boolean boatCollector = new Random().nextFloat() < 0.35F;
    private int desiredBoatCount = new Random().nextInt(2, 4);
    private PlayerNpcTarget target;
    private ItemStack mainWeaponItem = ItemStack.EMPTY;
    private ItemStack offWeaponItem = ItemStack.EMPTY;
    private boolean healing = false;
    private boolean useBow = true;
    private double placeBlockToParryChance;
    private int placeBlockParryCooldown = 0;
    private int stunEscapeCooldown = 0;
    private int playingIdleCooldown = new Random().nextInt(600, 1200);
    private static final String EPICFIGHT_PLAYER_NPC_MODID = "epicfight_player_npc";

    public int getPlayingIdleCooldown() {
        return playingIdleCooldown;
    }

    public void setPlayingIdleCooldown(int playingIdleCooldown) {
        this.playingIdleCooldown = playingIdleCooldown;
    }

    public double getPlaceBlockToParryChance() {
        return placeBlockToParryChance;
    }

    public boolean hasPlaceBlockParryCooldown() {
        return this.placeBlockParryCooldown > 0;
    }

    public void setPlaceBlockParryCooldown() {
        this.placeBlockParryCooldown = PLACE_BLOCK_PARRY_COOLDOWN_TICKS;
    }

    public boolean isHealing() {
        return healing;
    }

    public void setHealing(boolean healing) {
        this.healing = healing;
    }

    public int getGapCooldown() {
        return gapCooldown;
    }

    public int getBucketCooldown() {
        return bucketCooldown;
    }

    public int getEnderPearlCooldown() {
        return enderPearlCooldown;
    }

    public int getSwapToBowCooldown() {
        return swapToBowCooldown;
    }

    public int getHelpAlertCooldown() {
        return helpAlertCooldown;
    }

    public int getHoleEscapeCooldown() {
        return holeEscapeCooldown;
    }

    public int getRareSneakCooldown() {
        return rareSneakCooldown;
    }

    public int getScaredHideCooldown() {
        return scaredHideCooldown;
    }

    public int getBuildHouseCooldown() {
        return buildHouseCooldown;
    }

    public int getCookFoodCooldown() {
        return cookFoodCooldown;
    }

    public int getCraftGearCooldown() {
        return craftGearCooldown;
    }

    public int getFarmCooldown() {
        return farmCooldown;
    }

    public int getGatherCooldown() {
        return gatherCooldown;
    }

    public int getHuntSheepCooldown() {
        return huntSheepCooldown;
    }

    public int getIronGolemTrollCooldown() {
        return ironGolemTrollCooldown;
    }

    public int getLootChestCooldown() {
        return lootChestCooldown;
    }

    public int getManageHomeCooldown() {
        return manageHomeCooldown;
    }

    public int getFishingCooldown() {
        return fishingCooldown;
    }

    public int getReturnHomeCooldown() {
        return returnHomeCooldown;
    }

    public int getSleepCooldown() {
        return sleepCooldown;
    }

    public int getCraftCooldown() {
        return craftCooldown;
    }

    public int getOreMiningCooldown() {
        return oreMiningCooldown;
    }

    public int getIronGearCooldown() {
        return ironGearCooldown;
    }

    public int getSpyglassCooldown() {
        return spyglassCooldown;
    }

    public int getSaplingPlantCooldown() {
        return saplingPlantCooldown;
    }

    public int getBoatStockCooldown() {
        return boatStockCooldown;
    }

    public int getBoatTrapCooldown() {
        return boatTrapCooldown;
    }

    public int getJukeboxDanceCooldown() {
        return jukeboxDanceCooldown;
    }

    public int getTrollHitCooldown() {
        return trollHitCooldown;
    }

    public int getCombatFishingCooldown() {
        return combatFishingCooldown;
    }

    public int getShieldCraftCooldown() {
        return shieldCraftCooldown;
    }

    public int getShieldGuardCooldown() {
        return shieldGuardCooldown;
    }

    public boolean isBoatCollector() {
        return boatCollector;
    }

    public int getDesiredBoatCount() {
        return desiredBoatCount;
    }

    public void setGapCooldown() {
        this.gapCooldown = random.nextInt(100, 300);
    }

    public void resetGapCooldown() {this.gapCooldown = 0; }

    public void setBucketCooldown() {
        this.bucketCooldown = random.nextInt(120, 240);
    }

    public void resetBucketCooldown() {this.bucketCooldown = 0; }

    public void setEnderPearlCooldown() {
        this.enderPearlCooldown = random.nextInt(100, 300);
    }

    public void setSwapToBowCooldown() {
        this.swapToBowCooldown = random.nextInt(100, 300);
    }

    public void setHelpAlertCooldown(int ticks) {
        this.helpAlertCooldown = normalizeCooldown(ticks);
    }

    public void setHoleEscapeCooldown(int ticks) {
        this.holeEscapeCooldown = normalizeCooldown(ticks);
    }

    public void setRareSneakCooldown(int ticks) {
        this.rareSneakCooldown = normalizeCooldown(ticks);
    }

    public void setScaredHideCooldown(int ticks) {
        this.scaredHideCooldown = normalizeCooldown(ticks);
    }

    public void setBuildHouseCooldown(int ticks) {
        this.buildHouseCooldown = normalizeCooldown(ticks);
    }

    public void setCookFoodCooldown(int ticks) {
        this.cookFoodCooldown = normalizeCooldown(ticks);
    }

    public void setCraftGearCooldown(int ticks) {
        this.craftGearCooldown = normalizeCooldown(ticks);
    }

    public void setFarmCooldown(int ticks) {
        this.farmCooldown = normalizeCooldown(ticks);
    }

    public void setGatherCooldown(int ticks) {
        this.gatherCooldown = normalizeCooldown(ticks);
    }

    public void setHuntSheepCooldown(int ticks) {
        this.huntSheepCooldown = normalizeCooldown(ticks);
    }

    public void setIronGolemTrollCooldown(int ticks) {
        this.ironGolemTrollCooldown = normalizeCooldown(ticks);
    }

    public void setLootChestCooldown(int ticks) {
        this.lootChestCooldown = normalizeCooldown(ticks);
    }

    public void setManageHomeCooldown(int ticks) {
        this.manageHomeCooldown = normalizeCooldown(ticks);
    }

    public void setFishingCooldown(int ticks) {
        this.fishingCooldown = normalizeCooldown(ticks);
    }

    public void setReturnHomeCooldown(int ticks) {
        this.returnHomeCooldown = normalizeCooldown(ticks);
    }

    public void setSleepCooldown(int ticks) {
        this.sleepCooldown = normalizeCooldown(ticks);
    }

    public void setCraftCooldown(int ticks) {
        this.craftCooldown = normalizeCooldown(ticks);
    }

    public void setOreMiningCooldown(int ticks) {
        this.oreMiningCooldown = normalizeCooldown(ticks);
    }

    public void setIronGearCooldown(int ticks) {
        this.ironGearCooldown = normalizeCooldown(ticks);
    }

    public void setSpyglassCooldown(int ticks) {
        this.spyglassCooldown = normalizeCooldown(ticks);
    }

    public void setSaplingPlantCooldown(int ticks) {
        this.saplingPlantCooldown = normalizeCooldown(ticks);
    }

    public void setBoatStockCooldown(int ticks) {
        this.boatStockCooldown = normalizeCooldown(ticks);
    }

    public void setBoatTrapCooldown(int ticks) {
        this.boatTrapCooldown = normalizeCooldown(ticks);
    }

    public void setJukeboxDanceCooldown(int ticks) {
        this.jukeboxDanceCooldown = normalizeCooldown(ticks);
    }

    public void setTrollHitCooldown(int ticks) {
        this.trollHitCooldown = normalizeCooldown(ticks);
    }

    public void setCombatFishingCooldown(int ticks) {
        this.combatFishingCooldown = normalizeCooldown(ticks);
    }

    public void setShieldCraftCooldown(int ticks) {
        this.shieldCraftCooldown = normalizeCooldown(ticks);
    }

    public void setShieldGuardCooldown(int ticks) {
        this.shieldGuardCooldown = normalizeCooldown(ticks);
    }

    private static int normalizeCooldown(int ticks) {
        return Math.max(0, ticks);
    }

    private boolean mainWeaponDisarmed = false;

    public boolean isMainWeaponDisarmed() {
        return mainWeaponDisarmed;
    }

    public void setMainWeaponDisarmed(boolean mainWeaponDisarmed) {
        this.mainWeaponDisarmed = mainWeaponDisarmed;
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    public boolean hasInventoryItem(Predicate<ItemStack> matcher) {
        return InventoryUtils.hasItem(this.inventory, matcher);
    }

    public boolean hasInventoryItem(ItemLike itemLike) {
        return InventoryUtils.hasItem(this.inventory, itemLike);
    }

    public Optional<ItemStack> consumeInventoryItem(Predicate<ItemStack> matcher, int count) {
        return InventoryUtils.consumeItem(this.inventory, matcher, count);
    }

    public Optional<ItemStack> consumeInventoryItem(ItemLike itemLike, int count) {
        return InventoryUtils.consumeItem(this.inventory, itemLike, count);
    }

    public PlayerNpcEntity(PlayMessages.SpawnEntity spawnentity, Level level) {
        this(PlayerNpcModEntities.PLAYER_NPC.get(), level);
    }

    public ItemStack getMainWeaponItem() {
        return mainWeaponItem;
    }

    public void setMainWeaponItem(ItemStack mainWeaponItem) {
        this.mainWeaponItem = mainWeaponItem.copy();

        if (!this.mainWeaponItem.isEmpty()) {
            this.mainWeaponDisarmed = false;
        }
    }

    public ItemStack getOffWeaponItem() { return offWeaponItem; }

    public void setOffWeaponItem(ItemStack offWeaponItem) {
        this.offWeaponItem = offWeaponItem;
    }

    public void setUseBow(boolean useBow) {
        this.useBow = useBow;
    }

    public boolean isUseBow() {
        return useBow;
    }

    public PlayerNpcEntity(EntityType<? extends PlayerNpcEntity> entitytype, Level level) {
        super(entitytype, level);
        this.setMaxUpStep(1.0F);
        this.xpReward = 50;
        this.setNoAi(false);
        this.setCustomNameVisible(true);
        this.setPersistenceRequired();
        this.placeBlockToParryChance = new Random().nextDouble(0.20, 0.40);
        this.setCanPickUpLoot(true);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(MAIN_HAND_ATTACK_ANIMATION_TICKS, 0);
        this.entityData.define(AI_STATE, AI_IDLE);
        this.entityData.define(AI_DETAIL, "");
        this.entityData.define(DANCING, false);
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("Inventory", this.inventory.createTag());
        tag.putInt("GapCooldown", this.gapCooldown);
        tag.putInt("BucketCooldown", this.bucketCooldown);
        tag.putInt("EnderPearlCooldown", this.enderPearlCooldown);
        tag.putInt("SwapToBowCooldown", this.swapToBowCooldown);
        tag.putInt("HelpAlertCooldown", this.helpAlertCooldown);
        tag.putInt("HoleEscapeCooldown", this.holeEscapeCooldown);
        tag.putInt("RareSneakCooldown", this.rareSneakCooldown);
        tag.putInt("ScaredHideCooldown", this.scaredHideCooldown);
        tag.putInt("BuildHouseCooldown", this.buildHouseCooldown);
        tag.putInt("CookFoodCooldown", this.cookFoodCooldown);
        tag.putInt("CraftGearCooldown", this.craftGearCooldown);
        tag.putInt("FarmCooldown", this.farmCooldown);
        tag.putInt("GatherCooldown", this.gatherCooldown);
        tag.putInt("HuntSheepCooldown", this.huntSheepCooldown);
        tag.putInt("IronGolemTrollCooldown", this.ironGolemTrollCooldown);
        tag.putInt("LootChestCooldown", this.lootChestCooldown);
        tag.putInt("ManageHomeCooldown", this.manageHomeCooldown);
        tag.putInt("FishingCooldown", this.fishingCooldown);
        tag.putInt("ReturnHomeCooldown", this.returnHomeCooldown);
        tag.putInt("SleepCooldown", this.sleepCooldown);
        tag.putInt("CraftCooldown", this.craftCooldown);
        tag.putInt("OreMiningCooldown", this.oreMiningCooldown);
        tag.putInt("IronGearCooldown", this.ironGearCooldown);
        tag.putInt("SpyglassCooldown", this.spyglassCooldown);
        tag.putInt("SaplingPlantCooldown", this.saplingPlantCooldown);
        tag.putInt("BoatStockCooldown", this.boatStockCooldown);
        tag.putInt("BoatTrapCooldown", this.boatTrapCooldown);
        tag.putInt("JukeboxDanceCooldown", this.jukeboxDanceCooldown);
        tag.putInt("TrollHitCooldown", this.trollHitCooldown);
        tag.putInt("CombatFishingCooldown", this.combatFishingCooldown);
        tag.putInt("ShieldCraftCooldown", this.shieldCraftCooldown);
        tag.putInt("ShieldGuardCooldown", this.shieldGuardCooldown);
        tag.putBoolean("BoatCollector", this.boatCollector);
        tag.putInt("DesiredBoatCount", this.desiredBoatCount);
        tag.putBoolean("UseBow", this.useBow);
        tag.putDouble("BlockProjectileChance", this.placeBlockToParryChance);
        tag.putInt("BlockParryCooldown", this.placeBlockParryCooldown);
        if (this.target != null) {
            tag.putString("PlayerNpcTarget", this.target.name());
        }
        if (!this.mainWeaponItem.isEmpty()) {
            CompoundTag itemTag = new CompoundTag();
            this.mainWeaponItem.save(itemTag);
            tag.put("MainHandItem", itemTag);
        }
        if (!this.offWeaponItem.isEmpty()) {
            CompoundTag itemTag = new CompoundTag();
            this.offWeaponItem.save(itemTag);
            tag.put("OffHandItem", itemTag);
        }
        tag.putBoolean("MainWeaponDisarmed", this.mainWeaponDisarmed);
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Inventory", Tag.TAG_LIST)) {
            this.inventory.fromTag(tag.getList("Inventory", Tag.TAG_COMPOUND));
        }
        this.gapCooldown = tag.getInt("GapCooldown");
        this.bucketCooldown = tag.getInt("BucketCooldown");
        this.enderPearlCooldown = tag.getInt("EnderPearlCooldown");
        this.swapToBowCooldown = tag.getInt("SwapToBowCooldown");
        this.helpAlertCooldown = tag.getInt("HelpAlertCooldown");
        this.holeEscapeCooldown = tag.getInt("HoleEscapeCooldown");
        this.rareSneakCooldown = tag.getInt("RareSneakCooldown");
        this.scaredHideCooldown = tag.getInt("ScaredHideCooldown");
        this.buildHouseCooldown = tag.getInt("BuildHouseCooldown");
        this.cookFoodCooldown = tag.getInt("CookFoodCooldown");
        this.craftGearCooldown = tag.getInt("CraftGearCooldown");
        this.farmCooldown = tag.getInt("FarmCooldown");
        this.gatherCooldown = tag.getInt("GatherCooldown");
        this.huntSheepCooldown = tag.getInt("HuntSheepCooldown");
        this.ironGolemTrollCooldown = tag.getInt("IronGolemTrollCooldown");
        this.lootChestCooldown = tag.getInt("LootChestCooldown");
        this.manageHomeCooldown = tag.getInt("ManageHomeCooldown");
        this.fishingCooldown = tag.getInt("FishingCooldown");
        this.returnHomeCooldown = tag.getInt("ReturnHomeCooldown");
        this.sleepCooldown = tag.getInt("SleepCooldown");
        this.craftCooldown = tag.getInt("CraftCooldown");
        this.oreMiningCooldown = tag.getInt("OreMiningCooldown");
        this.ironGearCooldown = tag.getInt("IronGearCooldown");
        this.spyglassCooldown = tag.getInt("SpyglassCooldown");
        this.saplingPlantCooldown = tag.getInt("SaplingPlantCooldown");
        this.boatStockCooldown = tag.getInt("BoatStockCooldown");
        this.boatTrapCooldown = tag.getInt("BoatTrapCooldown");
        this.jukeboxDanceCooldown = tag.getInt("JukeboxDanceCooldown");
        this.trollHitCooldown = tag.getInt("TrollHitCooldown");
        this.combatFishingCooldown = tag.getInt("CombatFishingCooldown");
        this.shieldCraftCooldown = tag.getInt("ShieldCraftCooldown");
        this.shieldGuardCooldown = tag.getInt("ShieldGuardCooldown");
        if (tag.contains("BoatCollector", Tag.TAG_BYTE)) {
            this.boatCollector = tag.getBoolean("BoatCollector");
        }
        if (tag.contains("DesiredBoatCount", Tag.TAG_INT)) {
            this.desiredBoatCount = Math.max(2, Math.min(3, tag.getInt("DesiredBoatCount")));
        }
        this.useBow = tag.getBoolean("UseBow");
        if (tag.contains("BlockProjectileChance", Tag.TAG_DOUBLE)) {
            this.placeBlockToParryChance = tag.getDouble("BlockProjectileChance");
        }
        this.placeBlockParryCooldown = tag.getInt("BlockParryCooldown");
        if (tag.contains("PlayerNpcTarget", Tag.TAG_STRING)) {
            String name = tag.getString("PlayerNpcTarget");
            try {
                this.target = PlayerNpcTarget.valueOf(name);
            } catch (IllegalArgumentException e) {
                this.target = PlayerNpcTarget.MONSTER_HUNTER;
            }
        }
        if (tag.contains("MainHandItem", Tag.TAG_COMPOUND)) {
            this.mainWeaponItem = ItemStack.of(tag.getCompound("MainHandItem"));
        } else {
            this.mainWeaponItem = ItemStack.EMPTY;
        }
        if (tag.contains("OffHandItem", Tag.TAG_COMPOUND)) {
            this.offWeaponItem = ItemStack.of(tag.getCompound("OffHandItem"));
        } else {
            this.offWeaponItem = ItemStack.EMPTY;
        }
        this.mainWeaponDisarmed = tag.getBoolean("MainWeaponDisarmed");
    }

    @Override
    protected void dropCustomDeathLoot(@NotNull DamageSource source, int looting, boolean recentlyHit) {
        super.dropCustomDeathLoot(source, looting, recentlyHit);

        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!stack.isEmpty()) {
                this.spawnAtLocation(stack);
            }
        }
    }

    private boolean shouldCustomInventoryPickup(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        EquipmentSlot slot = LivingEntity.getEquipmentSlotForItem(stack);

        if (slot.getType() == EquipmentSlot.Type.ARMOR) {
            return !this.wantsToPickUp(stack);
        }

        return !isRecoverableWeapon(stack)
                || this.getTarget() == null
                || !this.getMainHandItem().isEmpty();
    }

    private boolean isRecoverableWeapon(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        Item item = stack.getItem();

        return item instanceof SwordItem
                || item instanceof DiggerItem
                || item instanceof TridentItem;
    }

    @Override
    public boolean wantsToPickUp(@NotNull ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        EquipmentSlot slot = LivingEntity.getEquipmentSlotForItem(stack);
        if (slot.getType() != EquipmentSlot.Type.ARMOR) {
            return false;
        }
        return super.wantsToPickUp(stack);
    }

    public @NotNull Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    protected void registerGoals() {
        this.goalSelector.addGoal(-2, new RecoverWeaponInCombatGoal(this, 1.0D, 10.0D));
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.targetSelector.addGoal(0, new RetargetCloserThreatGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new PlayerNpcSmartTargetGoal(this));
        this.goalSelector.addGoal(1, new EscapeHoleWithBlockGoal(this));
        this.goalSelector.addGoal(1, new RespondToNpcAlertGoal(this));
        this.goalSelector.addGoal(2, new CallForHelpGoal(this));
        this.goalSelector.addGoal(2, new SleepAtHomeGoal(this));
        this.goalSelector.addGoal(2, new BoatTrapMonsterGoal(this));
        this.goalSelector.addGoal(2, new TrollHitGoal(this));
        this.goalSelector.addGoal(5, new BurnNearbyItemGoal(this, 1.0D, 10.0D));
        this.goalSelector.addGoal(5, new IronGolemTrollGoal(this));
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.0D, false));
        this.goalSelector.addGoal(6, new GatherMaterialsGoal(this, 1.0D));
        this.goalSelector.addGoal(6, new ExploreCaveOreGoal(this, 1.0D));
        this.goalSelector.addGoal(6, new ManageHomeBaseGoal(this));
        this.goalSelector.addGoal(6, new CraftBasicGearGoal(this));
        this.goalSelector.addGoal(6, new CraftIronGearGoal(this));
        this.goalSelector.addGoal(6, new CookFoodGoal(this));
        this.goalSelector.addGoal(6, new CraftShieldGoal(this));
        this.goalSelector.addGoal(6, new FarmCropGoal(this));
        this.goalSelector.addGoal(6, new PlantSaplingGoal(this));
        this.goalSelector.addGoal(6, new LootNearbyChestGoal(this, 1.0D));
        this.goalSelector.addGoal(6, new HuntSheepForBedGoal(this));
        this.goalSelector.addGoal(6, new PlayerNpcFishingGoal(this));
        this.goalSelector.addGoal(6, new BuildHouseGoal(this));
        this.goalSelector.addGoal(6, new BoatStockpileGoal(this));
        this.goalSelector.addGoal(6, new UtilityCraftingGoal(this));
        this.goalSelector.addGoal(7, new ReturnHomeGoal(this, 1.0D));
        this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 1.0D));
        this.goalSelector.addGoal(8, new UseSpyglassGoal(this));
        this.goalSelector.addGoal(8, new JukeboxDanceGoal(this, 1.0D));
        this.goalSelector.addGoal(8, new RareSneakGoal(this));
        this.goalSelector.addGoal(8, new ScaredHideGoal(this));
        this.goalSelector.addGoal(5, new OpenDoorGoal(this, true));
        if (!ModList.get().isLoaded(EPICFIGHT_PLAYER_NPC_MODID)) {
            this.registerVanillaCombatReplacementGoals();
        }
        ((GroundPathNavigation) this.getNavigation()).setCanOpenDoors(true);
        ((GroundPathNavigation) this.getNavigation()).setCanFloat(true);
    }

    private void registerVanillaCombatReplacementGoals() {
        this.goalSelector.addGoal(1, new LowHealthFleeGoal(this));
        this.goalSelector.addGoal(1, new EatHealingFoodGoal(this));
        this.goalSelector.addGoal(2, new ShieldGuardGoal(this));
        this.goalSelector.addGoal(2, new UseWaterBucketGoal(this));
        this.goalSelector.addGoal(2, new PlayerNpcProjectileBlockGoal(this));
        this.goalSelector.addGoal(2, new WaterEnderPearlEscapeGoal(this));
        this.goalSelector.addGoal(2, new RandomCombatJumpGoal(this));
        this.goalSelector.addGoal(2, new PlayerNpcRangedBowAttackGoal(this, 1.0D, 20, 18.0F));
        this.goalSelector.addGoal(3, new CombatFishingRodGoal(this));
        this.goalSelector.addGoal(3, new ThrowEnderPearlGoal(this));
        this.goalSelector.addGoal(4, new UseLavaBucketGoal(this));
        this.goalSelector.addGoal(8, new FillWaterBucketGoal(this, 1.0D));
    }

    public @NotNull MobType getMobType() {
        return MobType.UNDEFINED;
    }

    public boolean removeWhenFarAway(double d0) {
        return false;
    }

    public double getMyRidingOffset() {
        return -0.35D;
    }

    public @NotNull SoundEvent getHurtSound(@NotNull DamageSource damageSource) {
        return Objects.requireNonNull(ForgeRegistries.SOUND_EVENTS.getValue(ResourceLocation.fromNamespaceAndPath("minecraft", "entity.generic.hurt")));
    }

    public @NotNull SoundEvent getDeathSound() {
        return Objects.requireNonNull(ForgeRegistries.SOUND_EVENTS.getValue(ResourceLocation.fromNamespaceAndPath("minecraft", "entity.generic.death")));
    }

    public void jump() {
        this.jumpFromGround();
        Vec3 motion = this.getDeltaMovement();
        Vec3 forward = this.getForward();
        double strength = new Random().nextDouble(0.2, 0.4);
        this.setDeltaMovement(
                motion.x + forward.x * strength,
                motion.y,
                motion.z + forward.z * strength
        );
        this.hasImpulse = true;
    }

    public void shortPillarJump() {
        if (!this.onGround()) return;
        Vec3 v = this.getDeltaMovement();
        double keepH = 0.02D;
        this.setDeltaMovement(v.x * keepH, 0.42D, v.z * keepH);
        this.hasImpulse = true;
    }

    public boolean hurt(@NotNull DamageSource damageSource, float f) {
        if (this.tryBlockDamageWithShield(damageSource, f)) {
            return false;
        }

        boolean hurt = super.hurt(damageSource, f);
        if (hurt && !this.level().isClientSide() && damageSource.getEntity() instanceof LivingEntity attacker
                && attacker.isAlive()
                && attacker != this
                && !this.isAlliedTo(attacker)) {
            this.setTarget(attacker);
            this.setCurrentAiState("ai.player_npc.retaliating");
        }
        return hurt;
    }

    private boolean tryBlockDamageWithShield(DamageSource damageSource, float amount) {
        if (amount <= 0.0F
                || this.level().isClientSide()
                || damageSource.is(DamageTypeTags.BYPASSES_SHIELD)
                || !this.isUsingItem()
                || this.getUsedItemHand() != InteractionHand.OFF_HAND
                || !(this.getOffhandItem().getItem() instanceof ShieldItem)) {
            return false;
        }

        Vec3 sourcePosition = damageSource.getSourcePosition();
        if (sourcePosition == null || !this.isDamageSourceInFront(sourcePosition)) {
            return false;
        }

        int durabilityDamage = Math.max(1, (int) Math.ceil(amount));
        this.hurtItemInHand(InteractionHand.OFF_HAND, durabilityDamage);
        this.swing(InteractionHand.OFF_HAND, true);
        this.level().playSound(null, this.blockPosition(), SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 1.0F, 0.8F + this.getRandom().nextFloat() * 0.4F);
        return true;
    }

    private boolean isDamageSourceInFront(Vec3 sourcePosition) {
        Vec3 toSource = sourcePosition.subtract(this.position());
        if (toSource.lengthSqr() < 1.0E-4D) {
            return true;
        }
        return toSource.normalize().dot(this.getViewVector(1.0F)) > 0.0D;
    }

    @Override
    public boolean doHurtTarget(@NotNull Entity target) {
        this.setCurrentAiState("ai.player_npc.melee_attacking");
        this.triggerMainHandAttackAnimation();
        boolean hurtTarget = super.doHurtTarget(target);
        if (hurtTarget) {
            this.hurtMainHandItem(1);
        }
        return hurtTarget;
    }

    public void hurtMainHandItem(int amount) {
        this.hurtItemInHand(InteractionHand.MAIN_HAND, amount);
    }

    public void hurtItemInHand(InteractionHand hand, int amount) {
        if (amount <= 0) {
            return;
        }

        ItemStack stack = this.getItemInHand(hand);
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return;
        }

        stack.hurtAndBreak(amount, this, entity -> entity.broadcastBreakEvent(hand));
    }

    public boolean hurtHeldOrInventoryItem(Predicate<ItemStack> matcher, int amount) {
        if (amount <= 0) {
            return false;
        }

        ItemStack mainHand = this.getMainHandItem();
        if (!mainHand.isEmpty() && matcher.test(mainHand)) {
            this.hurtMainHandItem(amount);
            return true;
        }

        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (stack.isEmpty() || !matcher.test(stack) || !stack.isDamageableItem()) {
                continue;
            }

            stack.hurtAndBreak(amount, this, entity -> {});
            if (stack.isEmpty()) {
                this.inventory.setItem(i, ItemStack.EMPTY);
            }
            this.inventory.setChanged();
            return true;
        }

        return false;
    }

    public void triggerMainHandAttackAnimation() {
        this.entityData.set(MAIN_HAND_ATTACK_ANIMATION_TICKS, MAIN_HAND_ATTACK_ANIMATION_DURATION);
        this.swing(InteractionHand.MAIN_HAND, true);
    }

    public int getMainHandAttackAnimationTicks() {
        return this.entityData.get(MAIN_HAND_ATTACK_ANIMATION_TICKS);
    }

    public int getMainHandAttackAnimationDuration() {
        return MAIN_HAND_ATTACK_ANIMATION_DURATION;
    }

    public String getCurrentAiState() {
        return this.entityData.get(AI_STATE);
    }

    public void setCurrentAiState(String state) {
        String normalizedState = state == null || state.isBlank() ? AI_IDLE : state;
        this.entityData.set(AI_STATE, normalizedState);
        if (AI_IDLE.equals(normalizedState)) {
            this.setCurrentAiDetail("");
        }
    }

    public String getCurrentAiDetail() {
        return this.entityData.get(AI_DETAIL);
    }

    public void setCurrentAiDetail(String detail) {
        this.entityData.set(AI_DETAIL, detail == null ? "" : detail);
    }

    public boolean isDancing() {
        return this.entityData.get(DANCING);
    }

    public void setDancing(boolean dancing) {
        this.entityData.set(DANCING, dancing);
    }

    public PlayerNpcTarget getNpcTargetPersonality() {
        return this.target;
    }

    @Override
    public boolean canFireProjectileWeapon(@NotNull ProjectileWeaponItem item) {
        return item instanceof BowItem;
    }

    public boolean canFireProjectileWeapon(@NotNull Item item) {
        if (item instanceof ProjectileWeaponItem weaponItem) {
            return this.canFireProjectileWeapon(weaponItem);
        }
        return false;
    }

    @Override
    public void performRangedAttack(@NotNull LivingEntity pTarget, float pVelocity) {
        if (!BowFunction.hasClearShot(this, pTarget)) {
            return;
        }

        InteractionHand weaponHand = ProjectileUtil.getWeaponHoldingHand(this, this::canFireProjectileWeapon);
        ItemStack weaponStack = this.getItemInHand(weaponHand);
        ItemStack itemstack = InventoryUtils.consumeArrowAmmo(this).orElse(ItemStack.EMPTY);
        if (itemstack.isEmpty()) {
            return;
        }

        AbstractArrow mobArrow = ProjectileUtil.getMobArrow(this, itemstack, pVelocity);
        if (weaponStack.getItem() instanceof BowItem bowItem) {
            mobArrow = bowItem.customArrow(mobArrow);
        }

        double x = pTarget.getX() - this.getX();
        double y = pTarget.getY(0.3333333333333333) - mobArrow.getY();
        double z = pTarget.getZ() - this.getZ();
        double d3 = Math.sqrt(x * x + z * z);
        mobArrow.setOwner(this);
        mobArrow.shoot(x, y + d3 * (double)0.2F, z, 1.6F, (float)(14 - this.level().getDifficulty().getId() * 4));
        this.playSound(SoundEvents.ARROW_SHOOT, 1.0F, 1.0F / (this.getRandom().nextFloat() * 0.4F + 0.8F));
        this.level().addFreshEntity(mobArrow);
        this.hurtItemInHand(weaponHand, 1);
    }

    @Override
    public void die(@NotNull DamageSource damageSource) {
        super.die(damageSource);
        this.handlePlayerNpcDeathChat(damageSource);

        if (this.level() instanceof ServerLevel serverLevel) {
            if (this.getPersistentData().getBoolean("die_by_possess")) {
                this.remove(Entity.RemovalReason.KILLED);
            }
        }
    }

    private void handlePlayerNpcDeathChat(DamageSource damageSource) {
        Entity killer = damageSource.getEntity();
        if (!ChatUtil.shouldReportPlayerNpcDeath(this, killer)) {
            return;
        }

        ChatUtil.broadcastDeathSummary(this, killer);
        ChatUtil.warnDeath(this, killer);
        ChatUtil.scheduleDeathReaction(this, killer);

        if (killer instanceof LivingEntity livingKiller) {
            PlayerNpcAlertManager.raiseDeathAlert(this, livingKiller);
        }
    }

    private boolean isInventoryFull() {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack s = inventory.getItem(i);
            if (s.isEmpty() || s.getCount() < s.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    private void pickupNearbyItems() {
        if (!isAlive() || isRemoved() || this.isDeadOrDying()) return;

        var box = getBoundingBox().inflate(1.5D);
        List<ItemEntity> items = level().getEntitiesOfClass(
                ItemEntity.class,
                box,
                e -> !e.isRemoved()
                        && !e.hasPickUpDelay()
                        && shouldCustomInventoryPickup(e.getItem())
        );

        for (ItemEntity itemEntity : items) {
            tryPickup(itemEntity);
        }
    }
    private void tryPickup(ItemEntity itemEntity) {
        ItemStack remaining = itemEntity.getItem().copy();

        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            if (remaining.isEmpty()) break;
            ItemStack slotStack = this.inventory.getItem(i);

            if (slotStack.isEmpty()) {
                this.inventory.setItem(i, remaining);
                remaining = ItemStack.EMPTY;
                break;
            } else if (ItemStack.isSameItemSameTags(slotStack, remaining) &&
                    slotStack.getCount() < slotStack.getMaxStackSize()) {
                int transferable = Math.min(
                        remaining.getCount(),
                        slotStack.getMaxStackSize() - slotStack.getCount()
                );
                slotStack.grow(transferable);
                remaining.shrink(transferable);
            }
        }

        if (remaining.isEmpty()) {
            itemEntity.setDeltaMovement(
                    (this.getX() - itemEntity.getX()) * 0.25,
                    (this.getY() + 1.0 - itemEntity.getY()) * 0.25,
                    (this.getZ() - itemEntity.getZ()) * 0.25
            );
            itemEntity.setPickUpDelay(0);
            itemEntity.discard();
            this.level().playSound(null, this.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.HOSTILE, 0.2F, 1.0F);
        } else {
            itemEntity.setItem(remaining);
        }
    }

    @Override
    public void tick() {
        super.tick();

        int mainHandAttackAnimationTicks = this.getMainHandAttackAnimationTicks();
        if (mainHandAttackAnimationTicks > 0) {
            this.entityData.set(MAIN_HAND_ATTACK_ANIMATION_TICKS, mainHandAttackAnimationTicks - 1);
        }

        if (!(this.level() instanceof ServerLevel)) return;

        this.tickAiCooldowns();
        this.cleanupStaleCombatState();

        if ((tickCount + getId()) % 20 != 0) {
            return;
        }

        if (isInventoryFull()) return;

        pickupNearbyItems();
    }

    private void tickAiCooldowns() {
        this.gapCooldown = tickCooldown(this.gapCooldown);
        this.bucketCooldown = tickCooldown(this.bucketCooldown);
        this.enderPearlCooldown = tickCooldown(this.enderPearlCooldown);
        this.swapToBowCooldown = tickCooldown(this.swapToBowCooldown);
        this.helpAlertCooldown = tickCooldown(this.helpAlertCooldown);
        this.holeEscapeCooldown = tickCooldown(this.holeEscapeCooldown);
        this.rareSneakCooldown = tickCooldown(this.rareSneakCooldown);
        this.scaredHideCooldown = tickCooldown(this.scaredHideCooldown);
        this.buildHouseCooldown = tickCooldown(this.buildHouseCooldown);
        this.cookFoodCooldown = tickCooldown(this.cookFoodCooldown);
        this.craftGearCooldown = tickCooldown(this.craftGearCooldown);
        this.farmCooldown = tickCooldown(this.farmCooldown);
        this.gatherCooldown = tickCooldown(this.gatherCooldown);
        this.huntSheepCooldown = tickCooldown(this.huntSheepCooldown);
        this.ironGolemTrollCooldown = tickCooldown(this.ironGolemTrollCooldown);
        this.lootChestCooldown = tickCooldown(this.lootChestCooldown);
        this.manageHomeCooldown = tickCooldown(this.manageHomeCooldown);
        this.fishingCooldown = tickCooldown(this.fishingCooldown);
        this.returnHomeCooldown = tickCooldown(this.returnHomeCooldown);
        this.sleepCooldown = tickCooldown(this.sleepCooldown);
        this.craftCooldown = tickCooldown(this.craftCooldown);
        this.oreMiningCooldown = tickCooldown(this.oreMiningCooldown);
        this.ironGearCooldown = tickCooldown(this.ironGearCooldown);
        this.spyglassCooldown = tickCooldown(this.spyglassCooldown);
        this.saplingPlantCooldown = tickCooldown(this.saplingPlantCooldown);
        this.boatStockCooldown = tickCooldown(this.boatStockCooldown);
        this.boatTrapCooldown = tickCooldown(this.boatTrapCooldown);
        this.jukeboxDanceCooldown = tickCooldown(this.jukeboxDanceCooldown);
        this.trollHitCooldown = tickCooldown(this.trollHitCooldown);
        this.combatFishingCooldown = tickCooldown(this.combatFishingCooldown);
        this.shieldCraftCooldown = tickCooldown(this.shieldCraftCooldown);
        this.shieldGuardCooldown = tickCooldown(this.shieldGuardCooldown);
        this.placeBlockParryCooldown = tickCooldown(this.placeBlockParryCooldown);
        this.stunEscapeCooldown = tickCooldown(this.stunEscapeCooldown);
        this.playingIdleCooldown = tickCooldown(this.playingIdleCooldown);
    }

    private static int tickCooldown(int cooldown) {
        return cooldown > 0 ? cooldown - 1 : 0;
    }

    private void cleanupStaleCombatState() {
        LivingEntity currentTarget = this.getTarget();
        if (currentTarget != null && (!currentTarget.isAlive() || currentTarget.isRemoved())) {
            this.setTarget(null);
            currentTarget = null;
        }

        if (currentTarget == null && this.isCombatAiState(this.getCurrentAiState())) {
            this.setCurrentAiState(AI_IDLE);
        }
    }

    private boolean isCombatAiState(String state) {
        return "ai.player_npc.retaliating".equals(state)
                || "ai.player_npc.melee_attacking".equals(state)
                || "ai.player_npc.ranged_bow".equals(state)
                || "ai.player_npc.throwing_ender_pearl".equals(state)
                || "ai.player_npc.combat_fishing".equals(state)
                || "ai.player_npc.shield_guarding".equals(state)
                || "ai.player_npc.troll_hit".equals(state)
                || "ai.player_npc.using_lava_bucket".equals(state)
                || "ai.player_npc.blocking_projectile".equals(state)
                || "ai.player_npc.engaging".equals(state)
                || "ai.player_npc.engaging_player_like".equals(state)
                || "ai.player_npc.engaging_monster".equals(state)
                || "ai.player_npc.hunting_animal".equals(state)
                || "ai.player_npc.engaging_villager".equals(state)
                || "ai.player_npc.assisting_alert".equals(state);
    }

    public SpawnGroupData finalizeSpawn(@NotNull ServerLevelAccessor serverLevelAccessor, @NotNull DifficultyInstance difficultyInstance, @NotNull MobSpawnType mobSpawnType, @Nullable SpawnGroupData spawngroupdata, @Nullable CompoundTag compoundtag) {
        SpawnGroupData returnSpawnGroupData = super.finalizeSpawn(serverLevelAccessor, difficultyInstance, mobSpawnType, spawngroupdata, compoundtag);

        ServerLevel serverLevel = serverLevelAccessor.getLevel();

        this.target = PlayerNpcTarget.random(this.getRandom());
        this.setCurrentAiState(AI_IDLE);

        List<String> commands = EquipmentDataLoader.getEquipCommands(0.85f, this);
        for (String cmd : commands) {
            try {
                Objects.requireNonNull(this.getServer()).getCommands().getDispatcher().execute(
                        cmd,
                        this.createCommandSourceStack().withSuppressedOutput().withPermission(4)
                );
            } catch (CommandSyntaxException ignored) {
            }
        }

        this.mainWeaponItem = this.getMainHandItem().copy();
        this.offWeaponItem = this.getOffWeaponItem().copy();
        this.seedInventory();

        ChatUtil.joinGame(this);

        if (Math.random() <= 0.05D) {
            TeamUtil.addOrJoinTeam(this, "player");
        }

        return returnSpawnGroupData;
    }

    protected boolean seedInventory() {
        if (!InventoryUtils.isEmpty(this.inventory)) {
            return false;
        }

        Random random = new Random();
        boolean isHard = ProgressionUtil.isAtLeastDifficulty(Difficulty.HARD);
        boolean isMedium = ProgressionUtil.isAtLeastDifficulty(Difficulty.MEDIUM);

        int goldenAppleCount = isHard ? random.nextInt(8, 16)
                : isMedium ? random.nextInt(4, 8)
                : random.nextInt(0, 2);
        if (goldenAppleCount > 0) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.GOLDEN_APPLE, goldenAppleCount));
        }
        if (isHard) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, random.nextInt(0, 4)));
        }

        List<ItemLike> foods = new ArrayList<>(REGULAR_FOODS);
        for (int i = 0; i < 2 && !foods.isEmpty(); i++) {
            ItemLike food = foods.remove(random.nextInt(foods.size()));
            int foodCount = isHard ? random.nextInt(24, 32)
                    : isMedium ? random.nextInt(12, 24)
                    : random.nextInt(8, 12);
            InventoryUtils.addItem(this.inventory, new ItemStack(food, foodCount));
        }

        int arrowCount = isHard ? random.nextInt(48, 97)
                : isMedium ? random.nextInt(12, 33)
                : 0;
        if (arrowCount > 0) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.BOW));
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.ARROW, arrowCount));
        }

        int enderPearlCount = isHard ? random.nextInt(16, 33)
                : isMedium ? random.nextInt(0, 13)
                : 0;
        if (enderPearlCount > 0) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.ENDER_PEARL, enderPearlCount));
        }

        if (isMedium) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.WATER_BUCKET));
        }
        if ((isHard && random.nextFloat() < 0.50F) || (!isHard && isMedium && random.nextFloat() < 0.30F)) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.FLINT_AND_STEEL));
        }
        if (isHard && random.nextFloat() < 0.08F) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.SPYGLASS));
        }
        if (isHard && random.nextFloat() < 0.04F) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.JUKEBOX));
            ItemLike disc = MUSIC_DISCS.get(random.nextInt(MUSIC_DISCS.size()));
            InventoryUtils.addItem(this.inventory, new ItemStack(disc));
        }

        List<ItemLike> blocks = new ArrayList<>(PLACEABLE_BLOCKS);
        int blockStacks = random.nextInt(1, 2);
        for (int i = 0; i < blockStacks && !blocks.isEmpty(); i++) {
            ItemLike block = blocks.remove(random.nextInt(blocks.size()));
            int blockCount = isHard ? random.nextInt(8, 32)
                    : isMedium ? random.nextInt(8, 12)
                    : random.nextInt(0, 8);
            InventoryUtils.addItem(this.inventory, new ItemStack(block, blockCount));
        }

        List<ItemStack> materials = new ArrayList<>();
        if (isHard) {
            int coalCount = random.nextInt(0, 25);
            if (coalCount > 0) {
                materials.add(new ItemStack(Items.COAL, coalCount));
            }
            int ironCount = random.nextInt(0, 25);
            if (ironCount > 0) {
                materials.add(new ItemStack(Items.IRON_INGOT, ironCount));
            }
            int goldCount = random.nextInt(0, 15);
            if (goldCount > 0) {
                materials.add(new ItemStack(Items.GOLD_INGOT, goldCount));
            }
            int redstoneCount = random.nextInt(0, 25);
            if (redstoneCount > 0) {
                materials.add(new ItemStack(Items.REDSTONE, redstoneCount));
            }
            int lapisCount = random.nextInt(0, 17);
            if (lapisCount > 0) {
                materials.add(new ItemStack(Items.LAPIS_LAZULI, lapisCount));
            }
            if (random.nextFloat() < 0.82F) {
                materials.add(new ItemStack(Items.DIAMOND, random.nextInt(1, 7)));
            }
            if (random.nextFloat() < 0.78F) {
                materials.add(new ItemStack(Items.EMERALD, random.nextInt(2, 11)));
            }
        } else if (isMedium) {
            int coalCount = random.nextInt(0, 13);
            if (coalCount > 0) {
                materials.add(new ItemStack(Items.COAL, coalCount));
            }
            int ironCount = random.nextInt(0, 13);
            if (ironCount > 0) {
                materials.add(new ItemStack(Items.IRON_INGOT, ironCount));
            }
            if (random.nextFloat() < 0.72F) {
                materials.add(new ItemStack(Items.GOLD_INGOT, random.nextInt(1, 7)));
            }
            if (random.nextFloat() < 0.70F) {
                materials.add(new ItemStack(Items.REDSTONE, random.nextInt(2, 13)));
            }
        } else if (random.nextFloat() >= 0.55F) {
            if (random.nextFloat() < 0.70F) {
                materials.add(new ItemStack(Items.COAL, random.nextInt(1, 7)));
            }
            if (random.nextFloat() < 0.55F) {
                materials.add(new ItemStack(Items.IRON_INGOT, random.nextInt(1, 5)));
            }
        }

        int materialTypes = random.nextInt(0, Math.min(2, materials.size()) + 1);
        for (int i = 0; i < materialTypes; i++) {
            ItemStack material = materials.remove(random.nextInt(materials.size()));
            InventoryUtils.addItem(this.inventory, material);
        }

        return true;
    }

    @Override
    public void awardKillScore(@NotNull Entity entity, int i, @NotNull DamageSource damageSource) {
        super.awardKillScore(entity, i, damageSource);
        if (ChatUtil.shouldPlayerNpcTauntKill(this, entity)) {
            ChatUtil.scheduleKillerTaunt(this, entity);
        }
    }

    @Override
    public void onEquipItem(@NotNull EquipmentSlot pSlot, @NotNull ItemStack pOldItem, @NotNull ItemStack pNewItem) {
        if (pSlot == EquipmentSlot.MAINHAND &&
                (pNewItem.getItem() instanceof SwordItem || pNewItem.getItem() instanceof AxeItem)) {
            this.mainWeaponItem = pNewItem.copy();
            this.mainWeaponDisarmed = false;
        }

        if (pSlot == EquipmentSlot.OFFHAND &&
                (pNewItem.getItem() instanceof SwordItem || pNewItem.getItem() instanceof AxeItem || pNewItem.getItem() instanceof ShieldItem)) {
            this.offWeaponItem = pNewItem.copy();
        }

        super.onEquipItem(pSlot, pOldItem, pNewItem);
    }

    public static boolean canSpawn(EntityType<PlayerNpcEntity> entityType, ServerLevelAccessor level, MobSpawnType spawnType, BlockPos position, RandomSource random) {
        ServerLevel serverLevel = level.getLevel();
        if (serverLevel.isNight()) {
            // Nerf Player NPC spawn at night
            return false;
        }
        return PathfinderMob.checkMobSpawnRules(entityType, level, spawnType, position, random);
    }

    public static AttributeSupplier.Builder createAttributes() {
        AttributeSupplier.Builder builder = Mob.createMobAttributes();

        builder = builder.add(Attributes.MOVEMENT_SPEED, 0.35D);
        builder = builder.add(Attributes.MAX_HEALTH, 20.0D);
        builder = builder.add(Attributes.ARMOR, 0.0D);
        builder = builder.add(Attributes.ATTACK_DAMAGE, 1.0D);
        builder = builder.add(Attributes.FOLLOW_RANGE, 48.0D);
        return builder;
    }
}
