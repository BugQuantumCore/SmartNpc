package com.pla.smart_npc.entity;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.pla.smart_npc.clazz.Difficulty;
import com.pla.smart_npc.clazz.FakePlayer;
import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.ai.ResourceAi;
import com.pla.smart_npc.entity.goal.BeingAtHomeGoal;
import com.pla.smart_npc.entity.goal.BuildHouseGoal;
import com.pla.smart_npc.entity.goal.BreakTargetObstructionGoal;
import com.pla.smart_npc.entity.goal.BoatStockpileGoal;
import com.pla.smart_npc.entity.goal.BoatTrapMonsterGoal;
import com.pla.smart_npc.entity.goal.CallForHelpGoal;
import com.pla.smart_npc.entity.goal.CheckHomeSuppliesGoal;
import com.pla.smart_npc.entity.goal.CombatFishingRodGoal;
import com.pla.smart_npc.entity.goal.CookFoodGoal;
import com.pla.smart_npc.entity.goal.CraftBasicGearGoal;
import com.pla.smart_npc.entity.goal.CraftCropFoodGoal;
import com.pla.smart_npc.entity.goal.CraftIronGearGoal;
import com.pla.smart_npc.entity.goal.CraftShieldGoal;
import com.pla.smart_npc.entity.goal.DescendHighColumnGoal;
import com.pla.smart_npc.entity.goal.DigDownForStoneGoal;
import com.pla.smart_npc.entity.goal.EatHealingFoodGoal;
import com.pla.smart_npc.entity.goal.EscapeHoleWithBlockGoal;
import com.pla.smart_npc.entity.goal.EscapeWaterCurrentGoal;
import com.pla.smart_npc.entity.goal.ExploreAroundGoal;
import com.pla.smart_npc.entity.goal.ExploreCaveOreGoal;
import com.pla.smart_npc.entity.goal.FillWaterBucketGoal;
import com.pla.smart_npc.entity.goal.FarmCropGoal;
import com.pla.smart_npc.entity.goal.GatherMissingBuildMaterialGoal;
import com.pla.smart_npc.entity.goal.GatherLogsGoal;
import com.pla.smart_npc.entity.goal.GatherStoneGoal;
import com.pla.smart_npc.entity.goal.IronGolemTrollGoal;
import com.pla.smart_npc.entity.goal.InterestGatedGoal;
import com.pla.smart_npc.entity.goal.JukeboxDanceGoal;
import com.pla.smart_npc.entity.goal.LootNearbyChestGoal;
import com.pla.smart_npc.entity.goal.LowHealthFleeGoal;
import com.pla.smart_npc.entity.goal.ManageHomeBaseGoal;
import com.pla.smart_npc.entity.goal.PlayerNpcFishingGoal;
import com.pla.smart_npc.entity.goal.PlayerNpcProjectileBlockGoal;
import com.pla.smart_npc.entity.goal.PlayerNpcRangedBowAttackGoal;
import com.pla.smart_npc.entity.goal.PlayerNpcSmartTargetGoal;
import com.pla.smart_npc.entity.goal.PickupNearbyItemGoal;
import com.pla.smart_npc.entity.goal.RandomCombatJumpGoal;
import com.pla.smart_npc.entity.goal.RareSneakGoal;
import com.pla.smart_npc.entity.goal.RecoverWeaponInCombatGoal;
import com.pla.smart_npc.entity.goal.RespondToNpcAlertGoal;
import com.pla.smart_npc.entity.goal.ReturnHomeGoal;
import com.pla.smart_npc.entity.goal.ScaredHideGoal;
import com.pla.smart_npc.entity.goal.ShieldGuardGoal;
import com.pla.smart_npc.entity.goal.SleepAtHomeGoal;
import com.pla.smart_npc.entity.goal.PlantSaplingGoal;
import com.pla.smart_npc.entity.goal.RetargetCloserThreatGoal;
import com.pla.smart_npc.entity.goal.ThrowEnderPearlGoal;
import com.pla.smart_npc.entity.goal.TerraformBuildSiteGoal;
import com.pla.smart_npc.entity.goal.TrollHitGoal;
import com.pla.smart_npc.entity.goal.UtilityCraftingGoal;
import com.pla.smart_npc.entity.goal.UseFlintAndSteelGoal;
import com.pla.smart_npc.entity.goal.UseLavaBucketGoal;
import com.pla.smart_npc.entity.goal.UseWaterBucketGoal;
import com.pla.smart_npc.entity.goal.UseSpyglassGoal;
import com.pla.smart_npc.entity.goal.WaterEnderPearlEscapeGoal;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.util.*;
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
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PlayMessages;
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
    private static final EntityDataAccessor<Boolean> EPIC_FIGHT_DIGGING = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> SNEAKING_AI_HIDES_DISPLAY_NAME = SynchedEntityData.defineId(PlayerNpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final int MAIN_HAND_ATTACK_ANIMATION_DURATION = 10;
    private static final int MAIN_HAND_USE_ANIMATION_DURATION = 6;
    private static final int PLACE_BLOCK_PARRY_COOLDOWN_TICKS = 60;
    private static final double PLAYER_LIKE_JUMP_Y = 0.42D;
    private static final int EXPLORATION_RETURN_ESCAPE_MIN_PILLAR_BLOCKS = 8;
    private static final int EXPLORATION_RETURN_ESCAPE_EXTRA_BLOCKS = 4;
    private static final int EXPLORATION_RETURN_ESCAPE_MAX_PILLAR_BLOCKS = 24;
    private static final int STARTUP_IDLE_WAKE_TICKS = 20 * 4;
    private static final int TASKLESS_IDLE_WAKE_TICKS = 20;
    private static final long DAY_LENGTH_TICKS = 24000L;
    private static final long DAILY_JOB_ROLL_TIME = 1L;
    private static final long DAILY_JOB_FALLBACK_ROLL_END_TIME = 12000L;
    private static final List<PlayerNpcInterest> DAILY_JOB_INTERESTS = List.of(
            PlayerNpcInterest.BUILDING,
            PlayerNpcInterest.MINING,
            PlayerNpcInterest.FARMING,
            PlayerNpcInterest.FISHING,
            PlayerNpcInterest.EXPLORING
    );
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
    private int flintAndSteelCooldown = 0;
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
    private int stoneAccessClearCooldown = 0;
    private int biomeExploreCooldown = 0;
    private int huntSheepCooldown = 0;
    private int ironGolemTrollCooldown = 0;
    private int lootChestCooldown = 0;
    private int manageHomeCooldown = 0;
    private int fishingCooldown = 0;
    private int returnHomeCooldown = 0;
    private int explorationReturnHomeRequestTicks = 0;
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
    private int rawLogReserveTarget = 24;
    private int woodSupplyTarget = 64;
    private int cobblestoneSupplyTarget = 24;
    private long lastSupplyGoalRerollDay = -1L;
    @Nullable
    private PlayerNpcInterest selectedDailyJobInterest;
    private long selectedDailyJobDay = -1L;
    private ItemStack mainWeaponItem = ItemStack.EMPTY;
    private ItemStack offWeaponItem = ItemStack.EMPTY;
    private boolean suppressHeldItemCacheUpdate = false;
    private boolean healing = false;
    private boolean useBow = true;
    @Nullable
    private BlockPos ownedChestPos;
    @Nullable
    private BlockPos upwardEscapeTarget;
    private int upwardEscapeRequestTicks = 0;
    private int upwardEscapeMaxPillarBlocks = 0;
    private boolean forcedUpwardEscape = false;
    private double placeBlockToParryChance;
    private int placeBlockParryCooldown = 0;
    private int stunEscapeCooldown = 0;
    private int playingIdleCooldown = new Random().nextInt(600, 1200);
    private int tasklessIdleTicks = 0;
    private int startupIdleWakeTicks = STARTUP_IDLE_WAKE_TICKS;
    private int staleTargetTicks = 0;
    private int staleTargetEntityId = -1;
    private int lastCombatProgressTick = 0;
    private int animalLootPriorityTicks = 0;
    @Nullable
    private BlockPos animalLootPriorityPos;

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

    public int getFlintAndSteelCooldown() {
        return flintAndSteelCooldown;
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

    public boolean isStoneAccessClearing() {
        return this.stoneAccessClearCooldown > 0;
    }

    public int getBiomeExploreCooldown() {
        return biomeExploreCooldown;
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

    public int getStunEscapeCooldown() {
        return stunEscapeCooldown;
    }

    public void setStunEscapeCooldown(int stunEscapeCooldown) {
        this.stunEscapeCooldown = stunEscapeCooldown;
    }


    @Nullable
    public BlockPos getUpwardEscapeTarget() {
        return this.upwardEscapeRequestTicks > 0 && this.upwardEscapeTarget != null
                ? this.upwardEscapeTarget
                : null;
    }

    public void requestUpwardEscapeTo(@Nullable BlockPos target, int ticks) {
        this.requestUpwardEscapeTo(target, ticks, 0);
    }

    public void requestUpwardEscapeTo(@Nullable BlockPos target, int ticks, int maxPillarBlocks) {
        if (target == null || ticks <= 0) {
            return;
        }

        this.upwardEscapeTarget = target.immutable();
        this.upwardEscapeRequestTicks = Math.max(this.upwardEscapeRequestTicks, normalizeCooldown(ticks));
        this.upwardEscapeMaxPillarBlocks = Math.max(0, maxPillarBlocks);
        this.forcedUpwardEscape = false;
        this.holeEscapeCooldown = 0;
    }

    public void requestForcedUpwardEscapeTo(@Nullable BlockPos target, int ticks, int maxPillarBlocks) {
        this.requestUpwardEscapeTo(target, ticks, maxPillarBlocks);
        if (target != null && ticks > 0) {
            this.forcedUpwardEscape = true;
        }
    }

    public boolean isForcedUpwardEscape() {
        return this.getUpwardEscapeTarget() != null && this.forcedUpwardEscape;
    }

    public int getUpwardEscapeMaxPillarBlocks() {
        return this.getUpwardEscapeTarget() == null ? 0 : this.upwardEscapeMaxPillarBlocks;
    }

    public void clearUpwardEscapeTarget() {
        this.upwardEscapeTarget = null;
        this.upwardEscapeRequestTicks = 0;
        this.upwardEscapeMaxPillarBlocks = 0;
        this.forcedUpwardEscape = false;
    }

    @Nullable
    public BlockPos getOwnedChestPos() {
        return this.ownedChestPos;
    }

    public void setOwnedChestPos(@Nullable BlockPos ownedChestPos) {
        this.ownedChestPos = ownedChestPos == null ? null : ownedChestPos.immutable();
    }

    public boolean isOwnedChest(BlockPos pos) {
        return this.ownedChestPos != null && this.ownedChestPos.equals(pos);
    }

    public boolean isBoatCollector() {
        return boatCollector;
    }

    public int getDesiredBoatCount() {
        return desiredBoatCount;
    }

    public List<PlayerNpcInterest> getInterests() {
        return this.getUsername().getInterests();
    }

    public boolean hasInterest(PlayerNpcInterest interest) {
        return interest != null && this.getUsername().hasInterest(interest);
    }

    public boolean hasAnyInterest(List<PlayerNpcInterest> interests) {
        if (interests == null || interests.isEmpty()) {
            return false;
        }
        for (PlayerNpcInterest interest : interests) {
            if (this.hasInterest(interest)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAnyInterest(PlayerNpcInterest... interests) {
        if (interests == null || interests.length == 0) {
            return false;
        }
        for (PlayerNpcInterest interest : interests) {
            if (this.hasInterest(interest)) {
                return true;
            }
        }
        return false;
    }

    public boolean isInterestGateActive(List<PlayerNpcInterest> interests) {
        if (interests == null || interests.isEmpty()) {
            return false;
        }

        boolean matchedCharacteristic = false;
        boolean matchedSelectedJob = false;
        for (PlayerNpcInterest interest : interests) {
            if (!this.hasInterest(interest)) {
                continue;
            }
            if (interest.isJob()) {
                matchedSelectedJob = matchedSelectedJob || this.isDailyJobActive(interest);
            } else {
                matchedCharacteristic = true;
            }
        }
        return matchedSelectedJob || matchedCharacteristic;
    }

    public boolean isDailyJobActive(PlayerNpcInterest interest) {
        if (interest == null || !interest.isJob() || !this.hasInterest(interest)) {
            return false;
        }
        if (this.isBuildingBaseSelectionLocked()) {
            return interest == PlayerNpcInterest.BUILDING;
        }
        if (this.level() instanceof ServerLevel serverLevel) {
            if (interest == PlayerNpcInterest.BUILDING && this.shouldRunBuildingHomeDuty(serverLevel)) {
                return true;
            }
            this.tickDailyJobSelection(serverLevel);
        }
        return this.selectedDailyJobInterest == interest;
    }

    public Optional<PlayerNpcInterest> getSelectedDailyJobInterest() {
        return Optional.ofNullable(this.selectedDailyJobInterest);
    }

    public long getSelectedDailyJobDay() {
        return this.selectedDailyJobDay;
    }

    public String getSelectedDailyJobDisplayText() {
        return this.selectedDailyJobInterest == null ? "none" : this.selectedDailyJobInterest.displayName();
    }

    public boolean isBuildingBaseSelectionLocked() {
        return this.hasInterest(PlayerNpcInterest.BUILDING)
                && PlayerNpcHomeUtil.getHomeLayoutId(this).isEmpty();
    }

    private boolean shouldRunBuildingHomeDuty(ServerLevel serverLevel) {
        if (!this.hasInterest(PlayerNpcInterest.BUILDING)
                || PlayerNpcHomeUtil.getHome(this).isEmpty()
                || (!serverLevel.isNight() && !serverLevel.isThundering())) {
            return false;
        }

        long day = serverLevel.getDayTime() / DAY_LENGTH_TICKS;
        return this.selectedDailyJobDay != day
                || this.selectedDailyJobInterest == null
                || this.selectedDailyJobInterest == PlayerNpcInterest.BUILDING;
    }

    public String getInterestsDisplayText() {
        return this.getUsername().getInterestDisplayText();
    }

    public void setGapCooldown() {
        this.gapCooldown = random.nextInt(100, 300);
    }

    public void resetGapCooldown() {this.gapCooldown = 0; }

    public void setBucketCooldown() {
        this.bucketCooldown = random.nextInt(120, 240);
    }

    public void resetBucketCooldown() {this.bucketCooldown = 0; }

    public void setFlintAndSteelCooldown() {
        this.flintAndSteelCooldown = random.nextInt(140, 260);
    }

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

    public void markStoneAccessClearing(int ticks) {
        this.stoneAccessClearCooldown = Math.max(this.stoneAccessClearCooldown, normalizeCooldown(ticks));
    }

    public void setBiomeExploreCooldown(int ticks) {
        this.biomeExploreCooldown = normalizeCooldown(ticks);
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

    public boolean requestReturnHomeAfterExplorationFailure(String reason, int ticks) {
        if (!(this.level() instanceof ServerLevel) || PlayerNpcHomeUtil.getHome(this).isEmpty()) {
            return false;
        }

        this.explorationReturnHomeRequestTicks = normalizeCooldown(ticks);
        this.returnHomeCooldown = 0;
        this.requestExplorationReturnEscape(ticks);
        this.setCurrentAiDetail(reason == null || reason.isBlank() ? "exploration failed; returning home" : reason);
        return true;
    }

    private void requestExplorationReturnEscape(int ticks) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(this);
        if (home.isEmpty()) {
            return;
        }

        PlayerNpcHomeUtil.HomeArea homeArea = home.get();
        BlockPos homeCenter = homeArea.origin().offset(homeArea.width() / 2, 1, homeArea.depth() / 2);
        int climbBlocks = homeCenter.getY() - this.blockPosition().getY();
        int maxPillarBlocks = Math.max(
                EXPLORATION_RETURN_ESCAPE_MIN_PILLAR_BLOCKS,
                Math.min(
                        EXPLORATION_RETURN_ESCAPE_MAX_PILLAR_BLOCKS,
                        climbBlocks + EXPLORATION_RETURN_ESCAPE_EXTRA_BLOCKS
                )
        );
        this.requestUpwardEscapeTo(homeCenter, Math.max(normalizeCooldown(ticks), 20 * 8), maxPillarBlocks);
    }

    public boolean hasExplorationReturnHomeRequest() {
        return this.explorationReturnHomeRequestTicks > 0;
    }

    public void clearExplorationReturnHomeRequest() {
        this.explorationReturnHomeRequestTicks = 0;
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

    public void wakeUpIdleWork() {
        this.buildHouseCooldown = 0;
        this.craftGearCooldown = 0;
        this.manageHomeCooldown = 0;
        this.returnHomeCooldown = 0;
        this.craftCooldown = 0;
        this.farmCooldown = 0;
        this.gatherCooldown = 0;
        this.biomeExploreCooldown = 0;
        this.oreMiningCooldown = 0;
        this.saplingPlantCooldown = 0;
        this.playingIdleCooldown = 0;
    }

    public boolean hasAnimalLootPriority() {
        return this.animalLootPriorityTicks > 0 && this.animalLootPriorityPos != null;
    }

    @Nullable
    public BlockPos getAnimalLootPriorityPos() {
        return this.animalLootPriorityPos;
    }

    public void clearAnimalLootPriority() {
        this.animalLootPriorityTicks = 0;
        this.animalLootPriorityPos = null;
    }

    public boolean hasCollectableSupplyDropNearby(double radius) {
        if (this.level().isClientSide || radius <= 0.0D) {
            return false;
        }

        AABB searchBox = this.getBoundingBox().inflate(radius, Math.min(6.0D, radius), radius);
        return !this.level().getEntitiesOfClass(
                ItemEntity.class,
                searchBox,
                item -> item.isAlive()
                        && !item.isRemoved()
                        && !item.getItem().isEmpty()
                        && InventoryUtils.isInventoryBackedSupplyDrop(item.getItem())
                        && this.canAcceptInventoryStack(item.getItem())
        ).isEmpty();
    }

    public boolean canAcceptInventoryStack(ItemStack incoming) {
        if (incoming.isEmpty()) {
            return false;
        }

        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack slotStack = this.inventory.getItem(i);
            if (slotStack.isEmpty()) {
                return true;
            }
            if (ItemStack.isSameItemSameTags(slotStack, incoming)
                    && slotStack.getCount() < slotStack.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    public int getRawLogReserveTarget() {
        return this.rawLogReserveTarget;
    }

    public int getLogSupplyGoal() {
        return this.rawLogReserveTarget;
    }

    public int getWoodSupplyTarget() {
        return this.woodSupplyTarget;
    }

    public int getCobblestoneSupplyTarget() {
        return this.cobblestoneSupplyTarget;
    }

    public int getStoneSupplyGoal() {
        return this.cobblestoneSupplyTarget;
    }

    public boolean shouldPrioritizeLogGathering() {
        return ResourceAi.countLogs(this) < this.getLogSupplyGoal();
    }

    public boolean shouldPrioritizeCobblestoneGathering() {
        return ResourceAi.countStone(this) < this.getStoneSupplyGoal();
    }

    public boolean hasMetBuildSupplyGoals() {
        return !this.shouldPrioritizeLogGathering()
                && !this.shouldPrioritizeCobblestoneGathering();
    }

    private boolean hasHeldOrInventoryTool(Class<?> toolClass) {
        if (toolClass.isInstance(this.getMainHandItem().getItem())
                || toolClass.isInstance(this.getOffhandItem().getItem())
                || toolClass.isInstance(this.mainWeaponItem.getItem())
                || toolClass.isInstance(this.offWeaponItem.getItem())) {
            return true;
        }

        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!stack.isEmpty() && toolClass.isInstance(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    private int countHeldAndInventoryItems(Predicate<ItemStack> matcher) {
        int count = 0;
        ItemStack mainHand = this.getMainHandItem();
        if (!mainHand.isEmpty() && matcher.test(mainHand)) {
            count += mainHand.getCount();
        }
        ItemStack offhand = this.getOffhandItem();
        if (!offhand.isEmpty() && matcher.test(offhand)) {
            count += offhand.getCount();
        }
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private void prioritizeAnimalLoot(BlockPos pos) {
        this.animalLootPriorityTicks = 20 * 8;
        this.animalLootPriorityPos = pos == null ? this.blockPosition() : pos.immutable();
        this.gatherCooldown = 0;
        this.biomeExploreCooldown = 0;
        this.fishingCooldown = 0;
        this.playingIdleCooldown = 0;
        this.setCurrentAiState("ai.player_npc.collecting_item");
        this.setCurrentAiDetail("animal drops @ "
                + this.animalLootPriorityPos.getX() + " "
                + this.animalLootPriorityPos.getY() + " "
                + this.animalLootPriorityPos.getZ());
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
        this(SmartNpcModEntities.PLAYER_NPC.get(), level);
    }

    public ItemStack getMainWeaponItem() {
        return mainWeaponItem;
    }

    public void setMainWeaponItem(ItemStack mainWeaponItem) {
        this.replaceMainWeaponItem(mainWeaponItem, true, ItemStack.EMPTY);
    }

    public void cacheMainWeaponItemForAi(ItemStack mainWeaponItem) {
        this.replaceMainWeaponItem(mainWeaponItem, false, ItemStack.EMPTY);
    }

    public ItemStack takeMainWeaponItem(Predicate<ItemStack> matcher) {
        if (this.mainWeaponItem.isEmpty() || matcher == null || !matcher.test(this.mainWeaponItem)) {
            return ItemStack.EMPTY;
        }
        ItemStack weapon = this.mainWeaponItem.copy();
        this.mainWeaponItem = ItemStack.EMPTY;
        return weapon;
    }

    public ItemStack takeOffWeaponItem(Predicate<ItemStack> matcher) {
        if (this.offWeaponItem.isEmpty() || matcher == null || !matcher.test(this.offWeaponItem)) {
            return ItemStack.EMPTY;
        }
        ItemStack weapon = this.offWeaponItem.copy();
        this.offWeaponItem = ItemStack.EMPTY;
        return weapon;
    }

    public boolean hasCarriedTool(Class<?> toolClass) {
        return this.hasHeldOrInventoryTool(toolClass);
    }

    public boolean promoteMainWeaponItem(ItemStack stack) {
        if (!this.isCombatMainHandGear(stack)
                || this.gearScore(stack) <= this.gearScore(this.mainWeaponItem) + 0.05D) {
            return false;
        }
        this.setMainWeaponItem(stack);
        return true;
    }

    private boolean promoteMainWeaponItemFromEquip(ItemStack newItem, ItemStack oldItem) {
        if (!this.isCombatMainHandGear(newItem)
                || this.gearScore(newItem) <= this.gearScore(this.mainWeaponItem) + 0.05D) {
            return false;
        }
        this.replaceMainWeaponItem(newItem, true, oldItem);
        return true;
    }

    public void setMainHandItemForAi(ItemStack stack) {
        this.suppressHeldItemCacheUpdate = true;
        try {
            this.setItemInHand(InteractionHand.MAIN_HAND, stack == null ? ItemStack.EMPTY : stack.copy());
        } finally {
            this.suppressHeldItemCacheUpdate = false;
        }
    }

    private void replaceMainWeaponItem(ItemStack stack, boolean moveOldToInventory, ItemStack oldEquippedItem) {
        ItemStack next = stack == null ? ItemStack.EMPTY : stack.copy();
        if (!next.isEmpty()) {
            next.setCount(1);
        }

        if (ItemStack.isSameItemSameTags(this.mainWeaponItem, next)) {
            this.mainWeaponItem = next;
            if (!this.mainWeaponItem.isEmpty()) {
                this.mainWeaponDisarmed = false;
            }
            return;
        }

        ItemStack previous = this.mainWeaponItem.copy();
        this.mainWeaponItem = next;

        if (!this.mainWeaponItem.isEmpty()) {
            this.mainWeaponDisarmed = false;
        }

        if (!moveOldToInventory
                || previous.isEmpty()
                || (!oldEquippedItem.isEmpty() && ItemStack.isSameItemSameTags(previous, oldEquippedItem))
                || this.isCurrentlyHeld(previous)
                || ItemStack.isSameItemSameTags(previous, this.mainWeaponItem)) {
            return;
        }

        this.addOrDropInventoryItem(previous);
    }

    private boolean isCurrentlyHeld(ItemStack stack) {
        return !stack.isEmpty()
                && (ItemStack.isSameItemSameTags(stack, this.getMainHandItem())
                || ItemStack.isSameItemSameTags(stack, this.getOffhandItem()));
    }

    private void addOrDropInventoryItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack copy = stack.copy();
        if (!InventoryUtils.addItem(this.inventory, copy)) {
            this.spawnAtLocation(copy);
        }
    }

    private void materializeCachedMainWeaponAfterLoad() {
        if (this.mainWeaponItem.isEmpty() || !this.getMainHandItem().isEmpty()) {
            return;
        }

        ItemStack weapon = this.mainWeaponItem.copy();
        weapon.setCount(1);
        if (InventoryUtils.addItem(this.inventory, weapon)) {
            this.inventory.setChanged();
            this.mainWeaponItem = ItemStack.EMPTY;
            this.mainWeaponDisarmed = false;
            return;
        }

        this.setItemSlot(EquipmentSlot.MAINHAND, weapon.copy());
        this.mainWeaponItem = weapon.copy();
        this.mainWeaponDisarmed = false;
    }

    public ItemStack getOffWeaponItem() { return offWeaponItem; }

    public void setOffWeaponItem(ItemStack offWeaponItem) {
        this.offWeaponItem = offWeaponItem == null ? ItemStack.EMPTY : offWeaponItem.copy();
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
        this.rawLogReserveTarget = ResourceAi.randomLogSupplyGoal(this.getRandom());
        this.woodSupplyTarget = this.rawLogReserveTarget;
        this.cobblestoneSupplyTarget = ResourceAi.randomStoneSupplyGoal(this.getRandom());
        this.setCanPickUpLoot(true);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(MAIN_HAND_ATTACK_ANIMATION_TICKS, 0);
        this.entityData.define(AI_STATE, AI_IDLE);
        this.entityData.define(AI_DETAIL, "");
        this.entityData.define(DANCING, false);
        this.entityData.define(EPIC_FIGHT_DIGGING, false);
        this.entityData.define(SNEAKING_AI_HIDES_DISPLAY_NAME, false);
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("Inventory", this.inventory.createTag());
        tag.putInt("GapCooldown", this.gapCooldown);
        tag.putInt("BucketCooldown", this.bucketCooldown);
        tag.putInt("FlintAndSteelCooldown", this.flintAndSteelCooldown);
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
        tag.putInt("BiomeExploreCooldown", this.biomeExploreCooldown);
        tag.putInt("HuntSheepCooldown", this.huntSheepCooldown);
        tag.putInt("IronGolemTrollCooldown", this.ironGolemTrollCooldown);
        tag.putInt("LootChestCooldown", this.lootChestCooldown);
        tag.putInt("ManageHomeCooldown", this.manageHomeCooldown);
        tag.putInt("FishingCooldown", this.fishingCooldown);
        tag.putInt("ReturnHomeCooldown", this.returnHomeCooldown);
        tag.putInt("ExplorationReturnHomeRequestTicks", this.explorationReturnHomeRequestTicks);
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
        tag.putInt("RawLogReserveTarget", this.rawLogReserveTarget);
        tag.putInt("WoodSupplyTarget", this.woodSupplyTarget);
        tag.putInt("CobblestoneSupplyTarget", this.cobblestoneSupplyTarget);
        tag.putLong("LastSupplyGoalRerollDay", this.lastSupplyGoalRerollDay);
        tag.putLong("SelectedDailyJobDay", this.selectedDailyJobDay);
        if (this.selectedDailyJobInterest != null) {
            tag.putString("SelectedDailyJobInterest", this.selectedDailyJobInterest.name());
        }
        tag.putBoolean("UseBow", this.useBow);
        tag.putDouble("BlockProjectileChance", this.placeBlockToParryChance);
        tag.putInt("BlockParryCooldown", this.placeBlockParryCooldown);
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
        if (this.ownedChestPos != null) {
            tag.putInt("OwnedChestX", this.ownedChestPos.getX());
            tag.putInt("OwnedChestY", this.ownedChestPos.getY());
            tag.putInt("OwnedChestZ", this.ownedChestPos.getZ());
        }
        PlayerNpcHomeUtil.saveHomeToTag(this, tag);
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
        this.flintAndSteelCooldown = tag.getInt("FlintAndSteelCooldown");
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
        this.biomeExploreCooldown = tag.getInt("BiomeExploreCooldown");
        this.huntSheepCooldown = tag.getInt("HuntSheepCooldown");
        this.ironGolemTrollCooldown = tag.getInt("IronGolemTrollCooldown");
        this.lootChestCooldown = tag.getInt("LootChestCooldown");
        this.manageHomeCooldown = tag.getInt("ManageHomeCooldown");
        this.fishingCooldown = tag.getInt("FishingCooldown");
        this.returnHomeCooldown = tag.getInt("ReturnHomeCooldown");
        this.explorationReturnHomeRequestTicks = tag.getInt("ExplorationReturnHomeRequestTicks");
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
        if (tag.contains("RawLogReserveTarget", Tag.TAG_INT)) {
            this.rawLogReserveTarget = Math.max(0, tag.getInt("RawLogReserveTarget"));
        }
        if (tag.contains("WoodSupplyTarget", Tag.TAG_INT)) {
            this.woodSupplyTarget = Math.max(0, tag.getInt("WoodSupplyTarget"));
        }
        if (tag.contains("CobblestoneSupplyTarget", Tag.TAG_INT)) {
            this.cobblestoneSupplyTarget = Math.max(0, tag.getInt("CobblestoneSupplyTarget"));
        }
        if (tag.contains("LastSupplyGoalRerollDay", Tag.TAG_LONG)) {
            this.lastSupplyGoalRerollDay = tag.getLong("LastSupplyGoalRerollDay");
        }
        if (tag.contains("SelectedDailyJobDay", Tag.TAG_LONG)) {
            this.selectedDailyJobDay = tag.getLong("SelectedDailyJobDay");
        }
        this.selectedDailyJobInterest = parseSavedDailyJobInterest(tag.getString("SelectedDailyJobInterest")).orElse(null);
        this.useBow = tag.getBoolean("UseBow");
        if (tag.contains("BlockProjectileChance", Tag.TAG_DOUBLE)) {
            this.placeBlockToParryChance = tag.getDouble("BlockProjectileChance");
        }
        this.placeBlockParryCooldown = tag.getInt("BlockParryCooldown");
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
        if (tag.contains("OwnedChestX", Tag.TAG_INT)
                && tag.contains("OwnedChestY", Tag.TAG_INT)
                && tag.contains("OwnedChestZ", Tag.TAG_INT)) {
            this.ownedChestPos = new BlockPos(
                    tag.getInt("OwnedChestX"),
                    tag.getInt("OwnedChestY"),
                    tag.getInt("OwnedChestZ")
            );
        } else {
            this.ownedChestPos = null;
        }
        PlayerNpcHomeUtil.readHomeFromTag(this, tag);
        this.mainWeaponDisarmed = tag.getBoolean("MainWeaponDisarmed");
        this.materializeCachedMainWeaponAfterLoad();
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

    public boolean isSmartNpcCompatPlayerLikeTarget(LivingEntity target) {
        return false;
    }

    public boolean isSmartNpcCompatMonsterTarget(LivingEntity target) {
        return false;
    }

    public boolean isSmartNpcCompatVillagerTarget(LivingEntity target) {
        return false;
    }

    public boolean isSmartNpcCompatAnimalTarget(LivingEntity target) {
        return false;
    }

    public boolean isSmartNpcCompatHighDangerThreat(LivingEntity target) {
        return false;
    }

    public boolean shouldSmartNpcAvoidTrollHitTarget(LivingEntity target) {
        return target != null && this.isSmartNpcCompatHighDangerThreat(target);
    }

    public float getSmartNpcTargetAttackChance(LivingEntity target, float baseChance) {
        float chance = Math.max(0.0F, Math.min(1.0F, baseChance));
        if (target != null && this.isSmartNpcCompatHighDangerThreat(target)) {
            chance = Math.min(chance, 0.15F);
        }
        return chance;
    }

    public float getSmartNpcFleeHealthRatio(LivingEntity threat, float baseHealthRatio) {
        float ratio = Math.max(0.0F, Math.min(1.0F, baseHealthRatio));
        if (threat != null && this.isSmartNpcCompatHighDangerThreat(threat)) {
            ratio = Math.max(ratio, 0.85F);
        }
        return ratio;
    }

    public boolean shouldSmartNpcAttackTarget(LivingEntity target) {
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (this.shouldSmartNpcFleeFromTarget(target)) {
            return false;
        }
        return this.getRandom().nextFloat() <= this.getSmartNpcTargetAttackChance(target, 1.0F);
    }

    public boolean shouldSmartNpcFleeFromTarget(LivingEntity threat) {
        return threat != null
                && threat.isAlive()
                && this.getHealth() / this.getMaxHealth() <= this.getSmartNpcFleeHealthRatio(threat, 0.0F);
    }

    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(0, new EscapeWaterCurrentGoal(this));
        this.registerVanillaCombatReplacementGoals();
        this.goalSelector.addGoal(1, new EscapeHoleWithBlockGoal(this));
        this.goalSelector.addGoal(1, new DescendHighColumnGoal(this));
        this.goalSelector.addGoal(1, new CallForHelpGoal(this));
        this.goalSelector.addGoal(2, this.gated(new SleepAtHomeGoal(this), PlayerNpcInterest.BUILDING));
        this.goalSelector.addGoal(2, this.gated(new ScaredHideGoal(this), PlayerNpcInterest.CAUTIOUS));
        this.goalSelector.addGoal(3, new PickupNearbyItemGoal(this, 1.0D));
        this.goalSelector.addGoal(3, new RecoverWeaponInCombatGoal(this, 1.0D, 8.0D));
        this.goalSelector.addGoal(3, this.gated(new RareSneakGoal(this), PlayerNpcInterest.CAUTIOUS));
        this.goalSelector.addGoal(4, this.gated(new ReturnHomeGoal(this, 1.0D), PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING));
        this.goalSelector.addGoal(5, this.gated(new TerraformBuildSiteGoal(this, 1.0D), PlayerNpcInterest.BUILDING));
        this.goalSelector.addGoal(5, new MeleeAttackGoal(this, 1.0D, true));
        this.goalSelector.addGoal(5, new BuildHouseGoal(this));
        this.goalSelector.addGoal(5, new CookFoodGoal(this));
        this.goalSelector.addGoal(5, this.gated(new FarmCropGoal(this), PlayerNpcInterest.FARMING));
        this.goalSelector.addGoal(5, this.gated(new CraftCropFoodGoal(this), PlayerNpcInterest.FARMING));
        this.goalSelector.addGoal(5, this.gated(new PlayerNpcFishingGoal(this), PlayerNpcInterest.FISHING));
        this.goalSelector.addGoal(5, this.gated(new LootNearbyChestGoal(this, 1.0D), PlayerNpcInterest.LOOTING));
        this.goalSelector.addGoal(5, this.gated(new JukeboxDanceGoal(this, 1.0D), PlayerNpcInterest.TROLL_HIT));
        this.goalSelector.addGoal(5, this.gated(new TrollHitGoal(this), PlayerNpcInterest.TROLL_HIT));
        this.goalSelector.addGoal(5, this.gated(new IronGolemTrollGoal(this), PlayerNpcInterest.TROLL_HIT));
        this.goalSelector.addGoal(5, this.gated(new ManageHomeBaseGoal(this), PlayerNpcInterest.BUILDING));
        this.goalSelector.addGoal(5, this.gated(new CheckHomeSuppliesGoal(this), PlayerNpcInterest.BUILDING));
        this.goalSelector.addGoal(5, new CraftBasicGearGoal(this));
        this.goalSelector.addGoal(5, new BreakTargetObstructionGoal(this));
        this.goalSelector.addGoal(5, this.gated(new CraftIronGearGoal(this), PlayerNpcInterest.MINING, PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_ANIMALS, PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_VILLAGERS));
        this.goalSelector.addGoal(5, this.gated(new CraftShieldGoal(this), PlayerNpcInterest.CAUTIOUS, PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_VILLAGERS));
        this.goalSelector.addGoal(5, this.gated(new UtilityCraftingGoal(this), PlayerNpcInterest.EXPLORING, PlayerNpcInterest.FISHING, PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_ANIMALS, PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_VILLAGERS));
        this.goalSelector.addGoal(5, this.gated(new BoatStockpileGoal(this), PlayerNpcInterest.FISHING, PlayerNpcInterest.EXPLORING));
        this.goalSelector.addGoal(5, this.gated(new PlantSaplingGoal(this), PlayerNpcInterest.FARMING));
        this.goalSelector.addGoal(5, this.gated(new UseSpyglassGoal(this), PlayerNpcInterest.EXPLORING, PlayerNpcInterest.CAUTIOUS));
        this.goalSelector.addGoal(6, this.gated(new GatherLogsGoal(this, 1.0D), PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING));
        this.goalSelector.addGoal(6, this.gated(new GatherStoneGoal(this, 1.0D), PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING));
        this.goalSelector.addGoal(6, this.gated(new DigDownForStoneGoal(this, 1.0D), PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING));
        this.goalSelector.addGoal(6, this.gated(new ExploreCaveOreGoal(this, 1.0D), PlayerNpcInterest.MINING));
        this.goalSelector.addGoal(6, new GatherMissingBuildMaterialGoal(this, 1.0D));
        this.goalSelector.addGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "exploring for logs",
                level -> (this.shouldPrioritizeLogGathering()
                        || PlayerNpcBuildMaterialUtil.needsLogsForCurrentBuild(level, this))
                        && this.getGatherCooldown() <= 0
                        && !GatherStoneGoal.isStoneSupplyPhaseActive(this, level)
                        && !this.shouldStayHomeForWeather(level)
                        && !ReturnHomeGoal.shouldSuppressExplorationForHome(this, level),
                level -> GatherLogsGoal.hasNearbyLogTarget(this, level)
        ), PlayerNpcInterest.BUILDING));
        this.goalSelector.addGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "exploring for stone",
                level -> GatherStoneGoal.isStoneSupplyPhaseActive(this, level)
                        && this.getGatherCooldown() <= 0
                        && !this.shouldStayHomeForWeather(level)
                        && !ReturnHomeGoal.shouldSuppressExplorationForHome(this, level),
                level -> GatherStoneGoal.hasNearbyStoneTarget(this, level),
                false
        ), PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING));
        this.goalSelector.addGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "exploring for build materials",
                level -> GatherMissingBuildMaterialGoal.needsMissingBuildMaterial(this, level)
                        && this.getGatherCooldown() <= 0
                        && !this.shouldStayHomeForWeather(level)
                        && !ReturnHomeGoal.shouldSuppressExplorationForHome(this, level),
                level -> GatherMissingBuildMaterialGoal.hasNearbyGatherTarget(this, level)
        ), PlayerNpcInterest.BUILDING));
        this.goalSelector.addGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "exploring",
                level -> this.isDailyJobActive(PlayerNpcInterest.EXPLORING)
                        && !this.shouldStayHomeForWeather(level),
                level -> false
        ), PlayerNpcInterest.EXPLORING));
        this.goalSelector.addGoal(7, this.gated(new ExploreAroundGoal(
                this,
                1.0D,
                "patrolling home for monsters",
                level -> this.hasInterest(PlayerNpcInterest.BUILDING)
                        && this.hasInterest(PlayerNpcInterest.HUNT_MONSTERS)
                        && PlayerNpcHomeUtil.getHome(this).isPresent()
                        && (level.isNight() || level.isThundering()),
                level -> this.getTarget() != null,
                true,
                false
        ), PlayerNpcInterest.HUNT_MONSTERS));
        this.goalSelector.addGoal(4, this.gated(new BeingAtHomeGoal(this, 1.0D), PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING));
        this.goalSelector.addGoal(5, new OpenDoorGoal(this, true));
        ((GroundPathNavigation) this.getNavigation()).setCanOpenDoors(true);
        ((GroundPathNavigation) this.getNavigation()).setCanFloat(true);
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new RetargetCloserThreatGoal(this));
        this.targetSelector.addGoal(3, new RespondToNpcAlertGoal(this));
        this.targetSelector.addGoal(4, this.gated(new PlayerNpcSmartTargetGoal(this), PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_ANIMALS, PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_VILLAGERS));
    }

    private Goal gated(Goal goal, PlayerNpcInterest... interests) {
        return new InterestGatedGoal(this, goal, interests);
    }

    private boolean shouldStayHomeForWeather(ServerLevel serverLevel) {
        return PlayerNpcHomeUtil.getHome(this).isPresent()
                && (serverLevel.isNight() || serverLevel.isThundering());
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
        this.goalSelector.addGoal(3, this.gated(new CombatFishingRodGoal(this), PlayerNpcInterest.FISHING));
        this.goalSelector.addGoal(3, new ThrowEnderPearlGoal(this));
        this.goalSelector.addGoal(4, this.gated(new BoatTrapMonsterGoal(this), PlayerNpcInterest.HUNT_MONSTERS));
        this.goalSelector.addGoal(4, this.gated(new UseFlintAndSteelGoal(this), PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_VILLAGERS, PlayerNpcInterest.TROLL_HIT));
        this.goalSelector.addGoal(4, this.gated(new UseLavaBucketGoal(this), PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_VILLAGERS, PlayerNpcInterest.TROLL_HIT));
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
        double strength = new Random().nextDouble(0.28, 0.48);
        this.setDeltaMovement(
                motion.x + forward.x * strength,
                Math.max(motion.y, PLAYER_LIKE_JUMP_Y),
                motion.z + forward.z * strength
        );
        this.hasImpulse = true;
    }

    public void shortPillarJump() {
        if (!this.onGround()) return;
        Vec3 v = this.getDeltaMovement();
        double keepH = 0.02D;
        this.setDeltaMovement(v.x * keepH, PLAYER_LIKE_JUMP_Y, v.z * keepH);
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
                && !this.isAlliedTo(attacker)
                && !attacker.isAlliedTo(this)) {
            this.setTarget(attacker);
            this.lastCombatProgressTick = this.tickCount;
            this.staleTargetTicks = 0;
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
            this.lastCombatProgressTick = this.tickCount;
            this.staleTargetTicks = 0;
            this.hurtMainHandItem(1);
            if (target instanceof Animal animal && (animal.isDeadOrDying() || !animal.isAlive())) {
                this.prioritizeAnimalLoot(animal.blockPosition());
                this.setTarget(null);
                this.getNavigation().stop();
            }
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

    public void showBlockBreakProgress(BlockPos pos, int breakTicks, int requiredBreakTicks) {
        if (!(this.level() instanceof ServerLevel serverLevel) || pos == null) {
            return;
        }

        int progress = requiredBreakTicks <= 1
                ? 9
                : (int) ((breakTicks * 10.0F) / requiredBreakTicks);
        progress = Math.max(0, Math.min(9, progress));
        serverLevel.destroyBlockProgress(this.getId(), pos, progress);
    }

    public void clearBlockBreakProgress(BlockPos pos) {
        if (this.level() instanceof ServerLevel serverLevel && pos != null) {
            serverLevel.destroyBlockProgress(this.getId(), pos, -1);
        }
    }

    public void markCombatProgress() {
        this.lastCombatProgressTick = this.tickCount;
        this.staleTargetTicks = 0;
    }

    public void equipBetterGearFromInventory() {
        boolean changed = false;
        changed |= this.equipBestArmorFromInventory();
        if (!this.shouldPauseMainHandAutoEquip()) {
            changed |= this.equipBestMainHandFromInventory();
        }
        if (changed) {
            this.inventory.setChanged();
        }
    }

    private boolean shouldPauseMainHandAutoEquip() {
        String state = this.getCurrentAiState();
        return this.isHealing()
                || "ai.player_npc.gathering_materials".equals(state)
                || "ai.player_npc.gathering_logs".equals(state)
                || "ai.player_npc.gathering_stone".equals(state)
                || "ai.player_npc.exploring_cave".equals(state)
                || "ai.player_npc.escaping_hole".equals(state)
                || "ai.player_npc.descending_column".equals(state)
                || "ai.player_npc.pillaring_up".equals(state)
                || "ai.player_npc.escaping_water_current".equals(state)
                || "ai.player_npc.breaking_target_obstruction".equals(state)
                || "ai.player_npc.managing_home".equals(state)
                || "ai.player_npc.checking_home_supplies".equals(state)
                || "ai.player_npc.cooking".equals(state)
                || "ai.player_npc.building_house".equals(state)
                || "ai.player_npc.terraforming_build_site".equals(state)
                || "ai.player_npc.farming".equals(state)
                || "ai.player_npc.planting_sapling".equals(state);
    }

    private boolean equipBestArmorFromInventory() {
        boolean changed = false;
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }

            EquipmentSlot slot = LivingEntity.getEquipmentSlotForItem(stack);
            if (slot.getType() != EquipmentSlot.Type.ARMOR) {
                continue;
            }

            ItemStack equipped = this.getItemBySlot(slot);
            if (this.gearScore(stack) <= this.gearScore(equipped) + 0.05D) {
                continue;
            }

            this.equipOneFromInventorySlot(i, slot);
            changed = true;
        }
        return changed;
    }

    private boolean equipBestMainHandFromInventory() {
        int bestSlot = -1;
        double bestScore = this.gearScore(this.getMainHandItem());
        for (int i = 0; i < this.inventory.getContainerSize(); i++) {
            ItemStack stack = this.inventory.getItem(i);
            if (!this.isMainHandGear(stack)) {
                continue;
            }

            double score = this.gearScore(stack);
            if (score > bestScore + 0.05D) {
                bestScore = score;
                bestSlot = i;
            }
        }

        if (bestSlot < 0) {
            return false;
        }

        this.equipOneFromInventorySlot(bestSlot, EquipmentSlot.MAINHAND);
        return true;
    }

    private void equipOneFromInventorySlot(int inventorySlot, EquipmentSlot equipmentSlot) {
        ItemStack source = this.inventory.getItem(inventorySlot);
        if (source.isEmpty()) {
            return;
        }

        ItemStack replacement = source.copy();
        replacement.setCount(1);
        source.shrink(1);
        if (source.isEmpty()) {
            this.inventory.setItem(inventorySlot, ItemStack.EMPTY);
        }

        ItemStack previous = this.getItemBySlot(equipmentSlot).copy();
        this.setItemSlot(equipmentSlot, replacement);
        if (!previous.isEmpty() && !InventoryUtils.addItem(this.inventory, previous)) {
            this.spawnAtLocation(previous);
        }
    }

    private boolean isMainHandGear(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof DiggerItem
                || stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem
                || stack.getItem() instanceof ProjectileWeaponItem);
    }

    private boolean isCombatMainHandGear(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof SwordItem
                || stack.getItem() instanceof AxeItem
                || stack.getItem() instanceof TridentItem
                || stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem
                || stack.getItem() instanceof ProjectileWeaponItem);
    }

    private double gearScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0.0D;
        }

        double score = 0.0D;
        if (stack.getItem() instanceof ArmorItem armorItem) {
            score += armorItem.getDefense() * 3.0D;
            score += armorItem.getToughness() * 1.5D;
        } else if (stack.getItem() instanceof SwordItem swordItem) {
            score += swordItem.getDamage() + 4.0D;
        } else if (stack.getItem() instanceof AxeItem axeItem) {
            score += axeItem.getAttackDamage() + 3.0D;
        } else if (stack.getItem() instanceof TridentItem) {
            score += 9.0D;
        } else if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem || stack.getItem() instanceof ProjectileWeaponItem) {
            score += 6.0D;
        } else if (stack.getItem() instanceof DiggerItem) {
            score += 3.0D + stack.getDestroySpeed(Blocks.STONE.defaultBlockState()) * 0.1D;
        }

        if (stack.isEnchanted()) {
            score += 2.0D;
        }
        if (stack.isDamageableItem()) {
            score += ((double) stack.getMaxDamage() - stack.getDamageValue()) / Math.max(1, stack.getMaxDamage());
        }
        return score;
    }

    public void triggerMainHandAttackAnimation() {
        this.entityData.set(MAIN_HAND_ATTACK_ANIMATION_TICKS, MAIN_HAND_ATTACK_ANIMATION_DURATION);
    }

    public void triggerMainHandUseAnimation() {
        if (this.entityData.get(MAIN_HAND_ATTACK_ANIMATION_TICKS) <= 0) {
            this.entityData.set(MAIN_HAND_ATTACK_ANIMATION_TICKS, MAIN_HAND_USE_ANIMATION_DURATION);
        }
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

    public boolean isEpicFightDigging() {
        return this.entityData.get(EPIC_FIGHT_DIGGING);
    }

    public void setEpicFightDigging(boolean digging) {
        this.entityData.set(EPIC_FIGHT_DIGGING, digging);
    }

    public boolean isDisplayNameHiddenBySneakingAi() {
        return this.entityData.get(SNEAKING_AI_HIDES_DISPLAY_NAME);
    }

    public void setDisplayNameHiddenBySneakingAi(boolean hidden) {
        this.entityData.set(SNEAKING_AI_HIDES_DISPLAY_NAME, hidden);
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
        this.equipBetterGearFromInventory();
    }

    public boolean tryPickupItemEntity(ItemEntity itemEntity) {
        if (this.level().isClientSide
                || itemEntity == null
                || !itemEntity.isAlive()
                || itemEntity.isRemoved()
                || itemEntity.hasPickUpDelay()
                || itemEntity.getItem().isEmpty()
                || !shouldCustomInventoryPickup(itemEntity.getItem())) {
            return false;
        }

        boolean pickedUp = tryPickup(itemEntity);
        if (pickedUp) {
            this.equipBetterGearFromInventory();
        }
        return pickedUp;
    }

    private boolean tryPickup(ItemEntity itemEntity) {
        ItemStack remaining = itemEntity.getItem().copy();
        int originalCount = remaining.getCount();

        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slotStack = this.inventory.getItem(i);

            if (!slotStack.isEmpty()
                    && ItemStack.isSameItemSameTags(slotStack, remaining) &&
                    slotStack.getCount() < slotStack.getMaxStackSize()) {
                int transferable = Math.min(
                        remaining.getCount(),
                        slotStack.getMaxStackSize() - slotStack.getCount()
                );
                slotStack.grow(transferable);
                remaining.shrink(transferable);
            }
        }

        for (int i = 0; i < inventory.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slotStack = this.inventory.getItem(i);
            if (!slotStack.isEmpty()) {
                continue;
            }

            ItemStack inserted = remaining.copy();
            inserted.setCount(Math.min(remaining.getCount(), remaining.getMaxStackSize()));
            this.inventory.setItem(i, inserted);
            remaining.shrink(inserted.getCount());
        }

        if (remaining.getCount() == originalCount) {
            return false;
        }

        this.inventory.setChanged();
        this.swing(InteractionHand.MAIN_HAND, true);
        this.playInventoryPickupSound();

        if (remaining.isEmpty()) {
            itemEntity.setDeltaMovement(
                    (this.getX() - itemEntity.getX()) * 0.25,
                    (this.getY() + 1.0 - itemEntity.getY()) * 0.25,
                    (this.getZ() - itemEntity.getZ()) * 0.25
            );
            itemEntity.setPickUpDelay(0);
            itemEntity.discard();
        } else {
            itemEntity.setItem(remaining);
        }
        return true;
    }

    public void playInventoryPickupSound() {
        if (this.level().isClientSide) {
            return;
        }
        this.level().playSound(
                null,
                this.blockPosition(),
                SoundEvents.ITEM_PICKUP,
                SoundSource.HOSTILE,
                0.2F,
                1.0F
        );
    }

    @Override
    public void tick() {
        super.tick();

        int mainHandAttackAnimationTicks = this.getMainHandAttackAnimationTicks();
        if (mainHandAttackAnimationTicks > 0) {
            this.entityData.set(MAIN_HAND_ATTACK_ANIMATION_TICKS, mainHandAttackAnimationTicks - 1);
        }

        if (!(this.level() instanceof ServerLevel serverLevel)) return;

        this.tickDailyJobSelection(serverLevel);
        this.tickDailySupplyGoalReroll(serverLevel);
        this.tickAiCooldowns();
        this.clearStaleHealingState();
        this.tickStartupIdleWake();
        this.cleanupStaleCombatState();
        this.tickTasklessActivityWatchdog();

        if ((tickCount + getId()) % 20 != 0) {
            return;
        }

        if (isInventoryFull()) return;

        pickupNearbyItems();
    }

    private void tickAiCooldowns() {
        this.gapCooldown = tickCooldown(this.gapCooldown);
        this.bucketCooldown = tickCooldown(this.bucketCooldown);
        this.flintAndSteelCooldown = tickCooldown(this.flintAndSteelCooldown);
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
        this.stoneAccessClearCooldown = tickCooldown(this.stoneAccessClearCooldown);
        this.biomeExploreCooldown = tickCooldown(this.biomeExploreCooldown);
        this.huntSheepCooldown = tickCooldown(this.huntSheepCooldown);
        this.ironGolemTrollCooldown = tickCooldown(this.ironGolemTrollCooldown);
        this.lootChestCooldown = tickCooldown(this.lootChestCooldown);
        this.manageHomeCooldown = tickCooldown(this.manageHomeCooldown);
        this.fishingCooldown = tickCooldown(this.fishingCooldown);
        this.returnHomeCooldown = tickCooldown(this.returnHomeCooldown);
        this.explorationReturnHomeRequestTicks = tickCooldown(this.explorationReturnHomeRequestTicks);
        this.sleepCooldown = tickCooldown(this.sleepCooldown);
        this.craftCooldown = tickCooldown(this.craftCooldown);
        this.oreMiningCooldown = tickCooldown(this.oreMiningCooldown);
        this.ironGearCooldown = tickCooldown(this.ironGearCooldown);
        this.spyglassCooldown = tickCooldown(this.spyglassCooldown);
        this.saplingPlantCooldown = tickCooldown(this.saplingPlantCooldown);
        this.animalLootPriorityTicks = tickCooldown(this.animalLootPriorityTicks);
        if (this.animalLootPriorityTicks <= 0) {
            this.animalLootPriorityPos = null;
        }
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
        this.upwardEscapeRequestTicks = tickCooldown(this.upwardEscapeRequestTicks);
        if (this.upwardEscapeRequestTicks <= 0) {
            if (this.upwardEscapeTarget != null
                    && AI_IDLE.equals(this.getCurrentAiState())
                    && this.getCurrentAiDetail().startsWith("exploration climb request")) {
                this.setCurrentAiDetail("");
            }
            this.upwardEscapeTarget = null;
            this.upwardEscapeMaxPillarBlocks = 0;
            this.forcedUpwardEscape = false;
        }
    }

    private void tickDailySupplyGoalReroll(ServerLevel serverLevel) {
        long dayTime = serverLevel.getDayTime();
        long day = dayTime / DAY_LENGTH_TICKS;
        if (dayTime % DAY_LENGTH_TICKS != 0L || day == this.lastSupplyGoalRerollDay) {
            return;
        }

        this.rawLogReserveTarget = ResourceAi.randomLogSupplyGoal(this.getRandom());
        this.woodSupplyTarget = this.rawLogReserveTarget;
        this.cobblestoneSupplyTarget = ResourceAi.randomStoneSupplyGoal(this.getRandom());
        this.lastSupplyGoalRerollDay = day;
        this.gatherCooldown = 0;
        this.biomeExploreCooldown = 0;
        this.setCurrentAiDetail("new daily supply goals logs="
                + this.rawLogReserveTarget
                + " stone="
                + this.cobblestoneSupplyTarget);
    }

    private void tickDailyJobSelection(ServerLevel serverLevel) {
        long dayTime = serverLevel.getDayTime();
        long day = dayTime / DAY_LENGTH_TICKS;
        long timeOfDay = dayTime % DAY_LENGTH_TICKS;

        if (this.isBuildingBaseSelectionLocked()) {
            this.selectDailyJob(day, PlayerNpcInterest.BUILDING, "building base selection locked");
            return;
        }

        if (this.selectedDailyJobDay == day
                && this.selectedDailyJobInterest != null
                && this.hasInterest(this.selectedDailyJobInterest)) {
            return;
        }
        if (this.selectedDailyJobDay == day
                && this.selectedDailyJobInterest == null
                && this.availableDailyJobs().isEmpty()) {
            return;
        }

        boolean exactRollTime = timeOfDay == DAILY_JOB_ROLL_TIME;
        boolean fallbackDayRoll = timeOfDay > DAILY_JOB_ROLL_TIME
                && timeOfDay < DAILY_JOB_FALLBACK_ROLL_END_TIME
                && this.selectedDailyJobDay != day;
        if (!exactRollTime && !fallbackDayRoll) {
            return;
        }

        List<PlayerNpcInterest> jobs = this.availableDailyJobs();
        if (jobs.isEmpty()) {
            this.selectDailyJob(day, null, "no job interests");
            return;
        }

        PlayerNpcInterest selected = jobs.get(this.getRandom().nextInt(jobs.size()));
        this.selectDailyJob(day, selected, "daily roll");
    }

    private List<PlayerNpcInterest> availableDailyJobs() {
        List<PlayerNpcInterest> jobs = new ArrayList<>();
        for (PlayerNpcInterest interest : DAILY_JOB_INTERESTS) {
            if (this.hasInterest(interest)) {
                jobs.add(interest);
            }
        }
        return jobs;
    }

    private void selectDailyJob(long day, @Nullable PlayerNpcInterest interest, String reason) {
        if (interest != null && (!interest.isJob() || !this.hasInterest(interest))) {
            interest = null;
        }
        if (this.selectedDailyJobDay == day && this.selectedDailyJobInterest == interest) {
            return;
        }

        this.selectedDailyJobDay = day;
        this.selectedDailyJobInterest = interest;
        this.gatherCooldown = 0;
        this.biomeExploreCooldown = 0;
        this.setCurrentAiDetail("daily job="
                + (interest == null ? "none" : interest.displayName())
                + " reason="
                + reason);
    }

    private static Optional<PlayerNpcInterest> parseSavedDailyJobInterest(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            PlayerNpcInterest interest = PlayerNpcInterest.valueOf(name);
            return interest.isJob() ? Optional.of(interest) : Optional.empty();
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private static int tickCooldown(int cooldown) {
        return cooldown > 0 ? cooldown - 1 : 0;
    }

    private void clearStaleHealingState() {
        if (!this.healing) {
            return;
        }

        boolean healingGoalRunning = this.goalSelector.getRunningGoals()
                .map(WrappedGoal::getGoal)
                .anyMatch(EatHealingFoodGoal.class::isInstance);
        if (healingGoalRunning
                || ("ai.player_npc.eating".equals(this.getCurrentAiState())
                && this.isUsingItem()
                && this.getMainHandItem().isEdible())) {
            return;
        }

        this.healing = false;
        if (AI_IDLE.equals(this.getCurrentAiState())) {
            this.wakeUpIdleWork();
        }
    }

    private void tickStartupIdleWake() {
        if (this.startupIdleWakeTicks <= 0) {
            return;
        }

        this.startupIdleWakeTicks--;
        if (!this.isAlive()
                || this.isNoAi()
                || this.isPassenger()
                || this.isHealing()
                || this.getTarget() != null
                || this.isSleeping()
                || this.hasRunningAiGoals()) {
            return;
        }

        String state = this.getCurrentAiState();
        if (!AI_IDLE.equals(state) && !"ai.player_npc.looking_for_work".equals(state)) {
            return;
        }

        this.wakeUpIdleWork();
        this.clearStaleIdleNavigation();
    }

    private void tickTasklessActivityWatchdog() {
        if (this.level().isClientSide
                || !this.isAlive()
                || this.isNoAi()
                || this.isPassenger()
                || this.isHealing()
                || this.getTarget() != null
                || this.isSleeping()) {
            this.tasklessIdleTicks = 0;
            return;
        }

        String state = this.getCurrentAiState();
        if (!AI_IDLE.equals(state) && !"ai.player_npc.looking_for_work".equals(state)) {
            this.tasklessIdleTicks = 0;
            return;
        }

        if ("ai.player_npc.looking_for_work".equals(state) && !this.hasRunningAiGoals()) {
            this.setCurrentAiState(AI_IDLE);
        }

        this.tasklessIdleTicks++;
        if (this.tasklessIdleTicks >= TASKLESS_IDLE_WAKE_TICKS) {
            if (!this.hasActiveIdleWorkCooldown()) {
                this.wakeUpIdleWork();
            }
            if (!this.hasRunningAiGoals()) {
                this.clearStaleIdleNavigation();
            }
            if (this.tasklessIdleTicks > TASKLESS_IDLE_WAKE_TICKS * 4) {
                this.tasklessIdleTicks = TASKLESS_IDLE_WAKE_TICKS;
            }
        }
    }

    private boolean hasActiveIdleWorkCooldown() {
        return this.buildHouseCooldown > 0
                || this.craftGearCooldown > 0
                || this.manageHomeCooldown > 0
                || this.returnHomeCooldown > 0
                || this.craftCooldown > 0
                || this.farmCooldown > 0
                || this.gatherCooldown > 0
                || this.biomeExploreCooldown > 0
                || this.oreMiningCooldown > 0
                || this.saplingPlantCooldown > 0;
    }

    private boolean hasRunningAiGoals() {
        return this.goalSelector.getRunningGoals().findAny().isPresent()
                || this.targetSelector.getRunningGoals().findAny().isPresent();
    }

    private void clearStaleIdleNavigation() {
        if (this.getNavigation().isDone() || this.getNavigation().isStuck()) {
            this.getNavigation().stop();
        }
    }

    private void cleanupStaleCombatState() {
        LivingEntity currentTarget = this.getTarget();
        if (currentTarget != null && (!currentTarget.isAlive() || currentTarget.isRemoved())) {
            if (currentTarget instanceof Animal) {
                this.prioritizeAnimalLoot(currentTarget.blockPosition());
            }
            this.setTarget(null);
            currentTarget = null;
        }

        if (currentTarget != null
                && !this.hasInterest(PlayerNpcInterest.HUNT_PLAYERS)
                && this.isPassiveBuildingBlockedByPlayerTarget(currentTarget)
                && !this.isRecentRetaliationTarget(currentTarget)) {
            this.setTarget(null);
            this.staleTargetTicks = 0;
            this.staleTargetEntityId = -1;
            this.setCurrentAiState(AI_IDLE);
            this.setCurrentAiDetail("");
            this.wakeUpIdleWork();
            currentTarget = null;
        }

        if (currentTarget == null && this.isCombatAiState(this.getCurrentAiState())) {
            this.setCurrentAiState(AI_IDLE);
        }

        if (currentTarget == null) {
            this.staleTargetTicks = 0;
            this.staleTargetEntityId = -1;
            return;
        }

        if (this.staleTargetEntityId != currentTarget.getId()) {
            this.staleTargetEntityId = currentTarget.getId();
            this.staleTargetTicks = 0;
            this.lastCombatProgressTick = this.tickCount;
        }

        double followDistance = this.getAttributeValue(Attributes.FOLLOW_RANGE);
        double clearDistance = Math.max(48.0D, followDistance + 24.0D);
        double distanceSqr = this.distanceToSqr(currentTarget);
        boolean tooFar = distanceSqr > clearDistance * clearDistance;
        boolean blockedAndNotMoving = distanceSqr > 16.0D * 16.0D
                && !this.hasLineOfSight(currentTarget)
                && (this.getNavigation().isDone() || this.getNavigation().isStuck());
        boolean noCombatProgress = this.tickCount - this.lastCombatProgressTick > 20 * 8;

        if (noCombatProgress && (tooFar || blockedAndNotMoving)) {
            this.staleTargetTicks++;
        } else {
            this.staleTargetTicks = 0;
        }

        if (this.staleTargetTicks > 20 * 3) {
            this.setTarget(null);
            this.staleTargetTicks = 0;
            this.staleTargetEntityId = -1;
            if (this.isCombatAiState(this.getCurrentAiState())) {
                this.setCurrentAiState(AI_IDLE);
            }
        }
    }

    private boolean isPassiveBuildingBlockedByPlayerTarget(LivingEntity target) {
        return target instanceof net.minecraft.world.entity.player.Player
                || target instanceof PlayerNpcEntity
                || this.isSmartNpcCompatPlayerLikeTarget(target);
    }

    private boolean isRecentRetaliationTarget(LivingEntity target) {
        return target != null && target == this.getLastHurtByMob();
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
                || "ai.player_npc.breaking_target_obstruction".equals(state)
                || "ai.player_npc.hunting_animal".equals(state)
                || "ai.player_npc.engaging_villager".equals(state)
                || "ai.player_npc.assisting_alert".equals(state);
    }

    public SpawnGroupData finalizeSpawn(@NotNull ServerLevelAccessor serverLevelAccessor, @NotNull DifficultyInstance difficultyInstance, @NotNull MobSpawnType mobSpawnType, @Nullable SpawnGroupData spawngroupdata, @Nullable CompoundTag compoundtag) {
        SpawnGroupData returnSpawnGroupData = super.finalizeSpawn(serverLevelAccessor, difficultyInstance, mobSpawnType, spawngroupdata, compoundtag);

        ServerLevel serverLevel = serverLevelAccessor.getLevel();

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

        int goldenAppleCount = isHard ? random.nextInt(6, 12)
                : isMedium ? random.nextInt(2, 6)
                : 0;
        if (goldenAppleCount > 0) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.GOLDEN_APPLE, goldenAppleCount));
        }
        if (isHard) {
            InventoryUtils.addItem(this.inventory, new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, random.nextInt(0, 4)));
        }

        List<ItemLike> foods = new ArrayList<>(REGULAR_FOODS);
        for (int i = 0; i < random.nextInt(isHard ? 2 : (isMedium ? 1 : 0), isHard ? 3 : (isMedium ? 2 : 1)) && !foods.isEmpty(); i++) {
            ItemLike food = foods.remove(random.nextInt(foods.size()));
            int foodCount = isHard ? random.nextInt(12, 24)
                    : isMedium ? random.nextInt(8, 12)
                    : random.nextInt(2, 4);
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
        if (pSlot == EquipmentSlot.MAINHAND && !this.suppressHeldItemCacheUpdate) {
            this.promoteMainWeaponItemFromEquip(pNewItem, pOldItem);
        }

        if (pSlot == EquipmentSlot.OFFHAND &&
                (pNewItem.getItem() instanceof SwordItem || pNewItem.getItem() instanceof AxeItem || pNewItem.getItem() instanceof ShieldItem)) {
            this.setOffWeaponItem(pNewItem);
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
