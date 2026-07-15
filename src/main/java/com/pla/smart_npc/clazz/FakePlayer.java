package com.pla.smart_npc.clazz;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringUtil;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FakePlayer extends PathfinderMob {
    private static final EntityDataAccessor<String> NAME = SynchedEntityData.defineId(FakePlayer.class, EntityDataSerializers.STRING);
    private static final List<FakePlayerName> HARDCODED_NAMES = List.of(
            new FakePlayerName("Gory_Moon", PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING)
//            new FakePlayerName("Darkosto", PlayerNpcInterest.MINING, PlayerNpcInterest.HUNT_MONSTERS),
//            new FakePlayerName("Darkere", PlayerNpcInterest.BUILDING, PlayerNpcInterest.MINING),
//            new FakePlayerName("Darkhax", PlayerNpcInterest.EXPLORING),
//            new FakePlayerName("Emberwalker", PlayerNpcInterest.EXPLORING, PlayerNpcInterest.HUNT_MONSTERS),
//            new FakePlayerName("Gigabit101", PlayerNpcInterest.BUILDING),
//            new FakePlayerName("Kamefrede", PlayerNpcInterest.FARMING, PlayerNpcInterest.FISHING),
//            new FakePlayerName("KnightMiner_", PlayerNpcInterest.MINING),
//            new FakePlayerName("Lat", PlayerNpcInterest.FARMING),
//            new FakePlayerName("LexManos", PlayerNpcInterest.EXPLORING, PlayerNpcInterest.MINING),
//            new FakePlayerName("Mrbysco", PlayerNpcInterest.FARMING, PlayerNpcInterest.BUILDING),
//            new FakePlayerName("P3pp3rF1y", PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.LOOTING),
//            new FakePlayerName("Ray", PlayerNpcInterest.FISHING, PlayerNpcInterest.EXPLORING),
//            new FakePlayerName("Ridanis", PlayerNpcInterest.BUILDING, PlayerNpcInterest.FISHING),
//            new FakePlayerName("SOTMead", PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.CAUTIOUS),
//            new FakePlayerName("ShyNieke", PlayerNpcInterest.CAUTIOUS, PlayerNpcInterest.FARMING),
//            new FakePlayerName("SkySom", PlayerNpcInterest.EXPLORING, PlayerNpcInterest.LOOTING),
//            new FakePlayerName("Soaryn", PlayerNpcInterest.BUILDING),
//            new FakePlayerName("ValkyrieofNight", PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.MINING),
//            new FakePlayerName("XCompWiz", PlayerNpcInterest.BUILDING, PlayerNpcInterest.EXPLORING),
//            new FakePlayerName("DaReal_BingoBear", PlayerNpcInterest.FISHING, PlayerNpcInterest.HUNT_ANIMALS),
//            new FakePlayerName("darkphan", PlayerNpcInterest.LOOTING, PlayerNpcInterest.EXPLORING),
//            new FakePlayerName("direwolf20", PlayerNpcInterest.MINING),
//            new FakePlayerName("dmodoomsirius", PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.LOOTING),
//            new FakePlayerName("dmodoomsirius", PlayerNpcInterest.CAUTIOUS, PlayerNpcInterest.EXPLORING),
//            new FakePlayerName("malte0811", PlayerNpcInterest.BUILDING),
//            new FakePlayerName("nekosune", PlayerNpcInterest.FISHING, PlayerNpcInterest.FARMING),
//            new FakePlayerName("neptunepink", PlayerNpcInterest.CAUTIOUS, PlayerNpcInterest.FISHING),
//            new FakePlayerName("vadis365", PlayerNpcInterest.BUILDING, PlayerNpcInterest.FISHING),
//            new FakePlayerName("wyld", PlayerNpcInterest.HUNT_ANIMALS),
//            new FakePlayerName("paulsoaresjr", PlayerNpcInterest.EXPLORING, PlayerNpcInterest.LOOTING),
//            new FakePlayerName("Mhykol", PlayerNpcInterest.MINING, PlayerNpcInterest.HUNT_MONSTERS),
//            new FakePlayerName("Vswe", PlayerNpcInterest.EXPLORING),
//            new FakePlayerName("TurkeyDev", PlayerNpcInterest.FARMING),
//            new FakePlayerName("Gen_Deathrow", PlayerNpcInterest.HUNT_PLAYERS, PlayerNpcInterest.HUNT_MONSTERS, PlayerNpcInterest.HUNT_VILLAGERS, PlayerNpcInterest.MINING),
//            new FakePlayerName("Sevadus", PlayerNpcInterest.EXPLORING, PlayerNpcInterest.CAUTIOUS)
    );
    private static final List<PlayerNpcInterest> DEFAULT_INTERESTS = List.of(
            PlayerNpcInterest.BUILDING
    );
    private static final Queue<FakePlayerName> NAME_POOL = new ArrayDeque<>();
    private static final Queue<FakePlayer> PROFILE_QUEUE = new ConcurrentLinkedQueue<>();
    private static final Object PROFILE_LOCK = new Object();
    private static Thread profileThread;

    private GameProfile profile;
    private ResourceLocation skin;
    private ResourceLocation cape;
    private ResourceLocation elytra;
    private boolean skinAvailable;
    private boolean capeAvailable;
    private boolean elytraAvailable;
    private volatile boolean profileUpdateQueued;

    public double xCloakO;
    public double yCloakO;
    public double zCloakO;
    public double xCloak;
    public double yCloak;
    public double zCloak;

    public FakePlayer(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(NAME, "");
    }

    @Override
    public void onSyncedDataUpdated(@NotNull EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (NAME.equals(key)) {
            this.profile = null;
            this.clearTextureState();
            if (this.hasUsername()) {
                this.getProfile();
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.updateCapeMotion();
    }

    private void updateCapeMotion() {
        this.xCloakO = this.xCloak;
        this.yCloakO = this.yCloak;
        this.zCloakO = this.zCloak;
        double xDiff = this.getX() - this.xCloak;
        double yDiff = this.getY() - this.yCloak;
        double zDiff = this.getZ() - this.zCloak;
        double limit = 10.0D;

        if (xDiff > limit || xDiff < -limit) {
            this.xCloak = this.getX();
            this.xCloakO = this.xCloak;
        }
        if (yDiff > limit || yDiff < -limit) {
            this.yCloak = this.getY();
            this.yCloakO = this.yCloak;
        }
        if (zDiff > limit || zDiff < -limit) {
            this.zCloak = this.getZ();
            this.zCloakO = this.zCloak;
        }

        this.xCloak += xDiff * 0.25D;
        this.yCloak += yDiff * 0.25D;
        this.zCloak += zDiff * 0.25D;
    }

    @Override
    public @Nullable SpawnGroupData finalizeSpawn(@NotNull ServerLevelAccessor level, @NotNull DifficultyInstance difficulty, @NotNull MobSpawnType spawnType, @Nullable SpawnGroupData groupData, @Nullable CompoundTag tag) {
        SpawnGroupData result = super.finalizeSpawn(level, difficulty, spawnType, groupData, tag);
        if (!this.hasUsername()) {
            this.setUsername(nextHardcodedName(level.getRandom()));
        }
        return result;
    }

    @Override
    public void addAdditionalSaveData(@NotNull CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (this.hasUsername()) {
            tag.putString("Username", this.getUsername().getCombinedNames());
        }
        if (this.profile != null && this.profile.isComplete()) {
            tag.put("Profile", NbtUtils.writeGameProfile(new CompoundTag(), this.profile));
        }
    }

    @Override
    public void readAdditionalSaveData(@NotNull CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        String username = tag.getString("Username");
        if (!StringUtil.isNullOrEmpty(username)) {
            this.setUsername(username);
        } else if (!this.level().isClientSide()) {
            this.setUsername(nextHardcodedName(this.getRandom()));
        }
        if (tag.contains("Profile", CompoundTag.TAG_COMPOUND)) {
            this.profile = NbtUtils.readGameProfile(tag.getCompound("Profile"));
        }
    }

    @Override
    public @Nullable Component getCustomName() {
        Component customName = super.getCustomName();
        if (customName != null && !customName.getString().isEmpty()) {
            return customName;
        }
        String displayName = this.getUsername().getDisplayName();
        return StringUtil.isNullOrEmpty(displayName) ? null : Component.literal(displayName);
    }

    @Override
    public boolean hasCustomName() {
        return super.hasCustomName() || !StringUtil.isNullOrEmpty(this.getUsername().getDisplayName());
    }

    @Override
    public @NotNull Component getDisplayName() {
        return this.getName();
    }

    public boolean hasUsername() {
        return !StringUtil.isNullOrEmpty(this.entityData.get(NAME));
    }

    public FakePlayerName getUsername() {
        if (!this.hasUsername() && !this.level().isClientSide()) {
            this.setUsername(nextHardcodedName(this.getRandom()));
        }
        return new FakePlayerName(this.entityData.get(NAME));
    }

    public void setUsername(String username) {
        this.setUsername(new FakePlayerName(username));
    }

    public void setUsername(FakePlayerName username) {
        FakePlayerName newName = username == null || username.isInvalid()
                ? nextHardcodedName(this.getRandom())
                : username;
        FakePlayerName oldName = this.hasUsername() ? this.getUsername() : null;

        useName(newName);
        this.entityData.set(NAME, newName.getCombinedNames());

        if (!Objects.equals(oldName, newName)) {
            this.profile = null;
            this.profileUpdateQueued = false;
            this.clearTextureState();
            this.getProfile();
        }
    }

    public @Nullable GameProfile getProfile() {
        if (this.profile == null && this.hasUsername()) {
            this.profile = new GameProfile(null, this.getUsername().getSkinName());
            requestProfileUpdate(this);
        }
        return this.profile;
    }

    public void setProfile(@Nullable GameProfile profile) {
        this.profile = profile;
        this.profileUpdateQueued = false;
        this.clearTextureState();
    }

    public boolean isTextureAvailable(MinecraftProfileTexture.Type type) {
        if (type == MinecraftProfileTexture.Type.SKIN) {
            return this.skinAvailable;
        }
        if (type == MinecraftProfileTexture.Type.ELYTRA) {
            return this.elytraAvailable;
        }
        return this.capeAvailable;
    }

    public @Nullable ResourceLocation getTexture(MinecraftProfileTexture.Type type) {
        if (type == MinecraftProfileTexture.Type.SKIN) {
            return this.skin;
        }
        if (type == MinecraftProfileTexture.Type.ELYTRA) {
            return this.elytra;
        }
        return this.cape;
    }

    public void setTexture(MinecraftProfileTexture.Type type, ResourceLocation location) {
        if (type == MinecraftProfileTexture.Type.SKIN) {
            this.skin = location;
            this.skinAvailable = true;
        } else if (type == MinecraftProfileTexture.Type.ELYTRA) {
            this.elytra = location;
            this.elytraAvailable = true;
        } else {
            this.cape = location;
            this.capeAvailable = true;
        }
    }

    private void clearTextureState() {
        this.skin = null;
        this.cape = null;
        this.elytra = null;
        this.skinAvailable = false;
        this.capeAvailable = false;
        this.elytraAvailable = false;
    }

    private static FakePlayerName nextHardcodedName(RandomSource random) {
        synchronized (NAME_POOL) {
            if (NAME_POOL.isEmpty()) {
                List<FakePlayerName> shuffled = new ArrayList<>(HARDCODED_NAMES);
                Collections.shuffle(shuffled, new java.util.Random(random.nextLong()));
                NAME_POOL.addAll(shuffled);
            }
            FakePlayerName name = NAME_POOL.poll();
            return name == null ? new FakePlayerName("Steve") : name;
        }
    }

    private static void useName(FakePlayerName name) {
        synchronized (NAME_POOL) {
            NAME_POOL.remove(name);
        }
    }

    public static String getRandomHardcodedName(RandomSource random) {
        return nextHardcodedName(random).getCombinedNames();
    }

    private static void requestProfileUpdate(FakePlayer entity) {
        if (entity.profileUpdateQueued) {
            return;
        }
        entity.profileUpdateQueued = true;
        PROFILE_QUEUE.add(entity);
        synchronized (PROFILE_LOCK) {
            if (profileThread == null || profileThread.getState() == Thread.State.TERMINATED) {
            profileThread = new Thread(FakePlayer::runProfileUpdates, "PlayerNpc FakePlayer Profile Updater");
                profileThread.setDaemon(true);
                profileThread.start();
            }
        }
    }

    private static void runProfileUpdates() {
        FakePlayer entity;
        while ((entity = PROFILE_QUEUE.poll()) != null) {
            GameProfile currentProfile = entity.profile;
            if (currentProfile == null) {
                entity.profileUpdateQueued = false;
                continue;
            }
            try {
                FakePlayer target = entity;
                SkullBlockEntity.updateGameprofile(currentProfile, target::setProfile);
            } catch (Exception ignored) {
                entity.profileUpdateQueued = false;
            }
        }
    }

    public static final class FakePlayerName {
        private final String skinName;
        private final String displayName;
        private final List<PlayerNpcInterest> interests;

        public FakePlayerName(String combinedName) {
            String[] names = combinedName == null ? new String[] {""} : combinedName.split(":", 2);
            this.skinName = names[0];
            this.displayName = names.length > 1 && !StringUtil.isNullOrEmpty(names[1]) ? names[1] : null;
            this.interests = hardcodedInterests(this.skinName);
        }

        public FakePlayerName(String skinName, String displayName) {
            this.skinName = skinName;
            this.displayName = StringUtil.isNullOrEmpty(displayName) ? null : displayName;
            this.interests = hardcodedInterests(this.skinName);
        }

        public FakePlayerName(String skinName, PlayerNpcInterest... interests) {
            this.skinName = skinName;
            this.displayName = null;
            this.interests = sanitizeInterests(interests);
        }

        public FakePlayerName(String skinName, String displayName, PlayerNpcInterest... interests) {
            this.skinName = skinName;
            this.displayName = StringUtil.isNullOrEmpty(displayName) ? null : displayName;
            this.interests = sanitizeInterests(interests);
        }

        public String getSkinName() {
            return this.skinName;
        }

        public String getDisplayName() {
            return StringUtil.isNullOrEmpty(this.displayName) ? this.skinName : this.displayName;
        }

        public String getCombinedNames() {
            if (StringUtil.isNullOrEmpty(this.displayName) || this.skinName.equals(this.displayName)) {
                return this.skinName;
            }
            return this.skinName + ":" + this.displayName;
        }

        public List<PlayerNpcInterest> getInterests() {
            return this.interests;
        }

        public boolean hasInterest(PlayerNpcInterest interest) {
            return this.interests.contains(interest);
        }

        public String getInterestDisplayText() {
            if (this.interests.isEmpty()) {
                return "";
            }
            List<String> names = new ArrayList<>(this.interests.size());
            for (PlayerNpcInterest interest : this.interests) {
                names.add(interest.displayName());
            }
            return String.join(", ", names);
        }

        private static List<PlayerNpcInterest> sanitizeInterests(PlayerNpcInterest... interests) {
            List<PlayerNpcInterest> result = new ArrayList<>();
            if (interests == null || interests.length == 0) {
                addInterests(result, DEFAULT_INTERESTS);
                return List.copyOf(result);
            }

            for (PlayerNpcInterest interest : interests) {
                addInterest(result, interest);
            }
            if (result.isEmpty()) {
                addInterests(result, DEFAULT_INTERESTS);
            }
            return List.copyOf(result);
        }

        private static List<PlayerNpcInterest> hardcodedInterests(String skinName) {
            if (!StringUtil.isNullOrEmpty(skinName)) {
                for (FakePlayerName name : HARDCODED_NAMES) {
                    if (skinName.equals(name.skinName)) {
                        return name.interests;
                    }
                }
            }
            return DEFAULT_INTERESTS;
        }

        private static void addInterests(List<PlayerNpcInterest> result, List<PlayerNpcInterest> interests) {
            for (PlayerNpcInterest interest : interests) {
                addInterest(result, interest);
            }
        }

        private static void addInterest(List<PlayerNpcInterest> result, PlayerNpcInterest interest) {
            if (interest != null && !result.contains(interest)) {
                result.add(interest);
            }
        }

        public boolean isInvalid() {
            return StringUtil.isNullOrEmpty(this.skinName);
        }

        @Override
        public boolean equals(Object object) {
            return object instanceof FakePlayerName other && this.getCombinedNames().equals(other.getCombinedNames());
        }

        @Override
        public int hashCode() {
            return this.getCombinedNames().hashCode();
        }

        @Override
        public String toString() {
            return this.getCombinedNames();
        }
    }
}
