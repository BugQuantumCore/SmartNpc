package com.pla.smart_npc.util;

import com.mojang.authlib.GameProfile;
import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Mod.EventBusSubscriber(modid = SmartNpc.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerNpcForceTickManager {
    private static final int FORCE_TICK_RADIUS_CHUNKS = 1;
    private static final int FORCE_TICK_DISTANCE = 2;
    private static final int RESTORED_ENTITY_LOAD_GRACE_TICKS = 20 * 30;
    private static final String NPC_TAB_PREFIX = "[NPC] ";
    private static final String NPC_TAB_PROFILE_PREFIX = "zzNPC";
    private static final int TAB_PROFILE_NAME_LENGTH = 16;
    private static final TicketType<TicketKey> PLAYER_NPC_TICKET = TicketType.create(
            SmartNpc.MODID + ":player_npc_force_tick",
            Comparator.comparing(TicketKey::npcId).thenComparingLong(TicketKey::chunkLong)
    );
    private static final Map<UUID, ManagedNpc> MANAGED_NPCS = new LinkedHashMap<>();
    @Nullable
    private static Boolean lastEnabled;

    private PlayerNpcForceTickManager() {
    }

    public static boolean isEnabled() {
        return SmartNpcConfig.isForceTickManageEnabled();
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof PlayerNpcEntity playerNpc
                && event.getLevel() instanceof ServerLevel) {
            track(playerNpc);
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof PlayerNpcEntity playerNpc
                && event.getLevel() instanceof ServerLevel) {
            release(playerNpc, false);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof PlayerNpcEntity playerNpc) {
            release(playerNpc, true);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!isEnabled() || !(event.getEntity() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        MinecraftServer server = serverPlayer.getServer();
        if (server == null) {
            return;
        }

        restorePersistentTickets(server);
        reconcileLoadedNpcs(server);
        for (ManagedNpc managedNpc : new ArrayList<>(MANAGED_NPCS.values())) {
            PlayerNpcEntity npc = managedNpc.resolve(server);
            if (npc == null) {
                continue;
            }
            managedNpc.sendTabAdd(serverPlayer, npc);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        MinecraftServer server = event.getServer();
        boolean enabled = isEnabled();
        if (!enabled) {
            if (!MANAGED_NPCS.isEmpty()) {
                releaseAll(server);
            }
            lastEnabled = false;
            return;
        }

        if (!Boolean.TRUE.equals(lastEnabled)) {
            restorePersistentTickets(server);
            reconcileLoadedNpcs(server);
            lastEnabled = true;
        }

        updateTrackedNpcs(server);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (isEnabled()) {
            restorePersistentTickets(event.getServer());
            reconcileLoadedNpcs(event.getServer());
            lastEnabled = true;
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        releaseAll(event.getServer());
        lastEnabled = null;
    }

    @SubscribeEvent
    public static void onTabListNameFormat(PlayerEvent.TabListNameFormat event) {
        ManagedNpc managedNpc = MANAGED_NPCS.get(event.getEntity().getUUID());
        if (managedNpc != null) {
            event.setDisplayName(managedNpc.tabDisplayName());
        }
    }

    public static boolean isNpcTabProfileName(String profileName) {
        if (profileName == null
                || profileName.length() != TAB_PROFILE_NAME_LENGTH
                || !profileName.startsWith(NPC_TAB_PROFILE_PREFIX)) {
            return false;
        }
        for (int i = NPC_TAB_PROFILE_PREFIX.length(); i < profileName.length(); i++) {
            if (Character.digit(profileName.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    public static void track(PlayerNpcEntity npc) {
        if (!isEnabled()
                || npc == null
                || npc.level().isClientSide()
                || !npc.isAlive()
                || npc.isRemoved()
                || !(npc.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        ManagedNpc managedNpc = MANAGED_NPCS.computeIfAbsent(npc.getUUID(), ManagedNpc::new);
        managedNpc.updateFrom(serverLevel.getServer(), npc);
    }

    public static void release(PlayerNpcEntity npc) {
        release(npc, false);
    }

    private static void release(PlayerNpcEntity npc, boolean removePersistentEntry) {
        if (npc == null || npc.level().isClientSide()) {
            return;
        }

        MinecraftServer server = npc.level().getServer();
        if (server != null) {
            release(npc.getUUID(), server, removePersistentEntry);
        }
    }

    public static Optional<PlayerNpcEntity> chooseRandomByName(MinecraftServer server, String rawName) {
        if (!isEnabled() || server == null || rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }

        String wantedName = normalizeLookupName(rawName);
        List<PlayerNpcEntity> matches = new ArrayList<>();
        for (PlayerNpcEntity npc : aliveTrackedNpcs(server)) {
            if (normalizeLookupName(displayName(npc)).equals(wantedName)) {
                matches.add(npc);
            }
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(matches.get(server.overworld().random.nextInt(matches.size())));
    }

    public static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestNpcNames(
            MinecraftServer server,
            SuggestionsBuilder builder
    ) {
        return SharedSuggestionProvider.suggest(availableNpcNames(server), builder);
    }

    public static Optional<PlayerNpcEntity> findNextForInspectator(
            MinecraftServer server,
            @Nullable PlayerNpcEntity currentNpc,
            int direction
    ) {
        if (!isEnabled() || server == null) {
            return Optional.empty();
        }

        if (currentNpc != null) {
            track(currentNpc);
        }

        List<PlayerNpcEntity> npcs = aliveTrackedNpcs(server);
        if (npcs.size() < 2) {
            return Optional.empty();
        }

        npcs.sort(Comparator
                .comparing((PlayerNpcEntity npc) -> npc.level().dimension().location().toString())
                .thenComparing(npc -> displayName(npc).toLowerCase(Locale.ROOT))
                .thenComparing(Entity::getUUID));

        int currentIndex = -1;
        if (currentNpc != null) {
            UUID currentId = currentNpc.getUUID();
            for (int i = 0; i < npcs.size(); i++) {
                if (npcs.get(i).getUUID().equals(currentId)) {
                    currentIndex = i;
                    break;
                }
            }
        }
        if (currentIndex < 0) {
            currentIndex = 0;
        }

        int step = direction < 0 ? -1 : 1;
        int nextIndex = Math.floorMod(currentIndex + step, npcs.size());
        return Optional.of(npcs.get(nextIndex));
    }

    public static Optional<PlayerNpcEntity> findTrackedByEntityId(MinecraftServer server, ServerLevel preferredLevel, int entityId) {
        if (!isEnabled() || server == null || entityId < 0) {
            return Optional.empty();
        }

        Entity preferredEntity = preferredLevel.getEntity(entityId);
        if (preferredEntity instanceof PlayerNpcEntity playerNpc && playerNpc.isAlive()) {
            return Optional.of(playerNpc);
        }

        for (PlayerNpcEntity npc : aliveTrackedNpcs(server)) {
            if (npc.getId() == entityId) {
                return Optional.of(npc);
            }
        }
        return Optional.empty();
    }

    public static List<PlayerNpcEntity> aliveTrackedNpcs(MinecraftServer server) {
        List<PlayerNpcEntity> result = new ArrayList<>();
        if (server == null) {
            return result;
        }

        for (ManagedNpc managedNpc : new ArrayList<>(MANAGED_NPCS.values())) {
            PlayerNpcEntity npc = managedNpc.resolve(server);
            if (npc != null) {
                result.add(npc);
            }
        }
        return result;
    }

    private static List<String> availableNpcNames(MinecraftServer server) {
        List<String> result = new ArrayList<>();
        for (PlayerNpcEntity npc : aliveTrackedNpcs(server)) {
            String name = displayName(npc);
            if (!result.contains(name)) {
                result.add(name);
            }
        }
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return result;
    }

    private static void updateTrackedNpcs(MinecraftServer server) {
        for (ManagedNpc managedNpc : new ArrayList<>(MANAGED_NPCS.values())) {
            PlayerNpcEntity npc = managedNpc.resolve(server);
            if (npc == null || !npc.isAlive() || npc.isRemoved()) {
                if (!managedNpc.shouldKeepWaitingForEntity(server)) {
                    release(managedNpc.npcId, server, true);
                }
            } else {
                managedNpc.updateFrom(server, npc);
            }
        }
    }

    private static void reconcileLoadedNpcs(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getType() == SmartNpcModEntities.PLAYER_NPC.get()
                        && entity instanceof PlayerNpcEntity playerNpc
                        && playerNpc.isAlive()
                        && !playerNpc.isRemoved()) {
                    track(playerNpc);
                }
            }
        }
    }

    private static void restorePersistentTickets(MinecraftServer server) {
        if (!isEnabled()) {
            return;
        }

        for (PlayerNpcForceTickData.Entry entry : PlayerNpcForceTickData.get(server).entries()) {
            ServerLevel level = server.getLevel(entry.levelKey());
            if (level == null) {
                continue;
            }

            ManagedNpc managedNpc = MANAGED_NPCS.computeIfAbsent(entry.npcId(), ManagedNpc::new);
            managedNpc.restoreFromData(server, level, entry.centerChunk());
        }
    }

    private static void release(UUID npcId, MinecraftServer server, boolean removePersistentEntry) {
        ManagedNpc managedNpc = MANAGED_NPCS.remove(npcId);
        if (managedNpc != null) {
            managedNpc.releaseTickets(server);
            managedNpc.broadcastTabRemove(server);
        }

        if (removePersistentEntry) {
            PlayerNpcForceTickData.get(server).remove(npcId);
        }
    }

    private static void releaseAll(MinecraftServer server) {
        for (ManagedNpc managedNpc : new ArrayList<>(MANAGED_NPCS.values())) {
            managedNpc.releaseTickets(server);
            managedNpc.broadcastTabRemove(server);
        }
        MANAGED_NPCS.clear();
    }

    private static String displayName(PlayerNpcEntity npc) {
        return npc.getName().getString();
    }

    private static String normalizeLookupName(String rawName) {
        String name = rawName.trim();
        if (name.startsWith(NPC_TAB_PREFIX)) {
            name = name.substring(NPC_TAB_PREFIX.length()).trim();
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static GameProfile createTabProfile(PlayerNpcEntity npc) {
        GameProfile profile = new GameProfile(npc.getUUID(), tabProfileName(npc.getUUID()));
        copyProfileProperties(npc.getProfile(), profile);
        return profile;
    }

    private static void copyProfileProperties(@Nullable GameProfile sourceProfile, GameProfile targetProfile) {
        targetProfile.getProperties().clear();
        if (sourceProfile != null) {
            targetProfile.getProperties().putAll(sourceProfile.getProperties());
        }
    }

    private static String profilePropertiesSignature(@Nullable GameProfile profile) {
        if (profile == null || profile.getProperties().isEmpty()) {
            return "";
        }

        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, com.mojang.authlib.properties.Property> entry : profile.getProperties().entries()) {
            com.mojang.authlib.properties.Property property = entry.getValue();
            entries.add(entry.getKey()
                    + "="
                    + property.getValue()
                    + "|"
                    + Objects.toString(property.getSignature(), ""));
        }
        entries.sort(String::compareTo);
        return String.join(";", entries);
    }

    private static String tabProfileName(UUID npcId) {
        String compactId = npcId.toString().replace("-", "");
        return (NPC_TAB_PROFILE_PREFIX + compactId).substring(0, TAB_PROFILE_NAME_LENGTH);
    }

    private static Set<ChunkPos> forceTickChunksAround(ChunkPos center) {
        Set<ChunkPos> result = new LinkedHashSet<>();
        for (int dx = -FORCE_TICK_RADIUS_CHUNKS; dx <= FORCE_TICK_RADIUS_CHUNKS; dx++) {
            for (int dz = -FORCE_TICK_RADIUS_CHUNKS; dz <= FORCE_TICK_RADIUS_CHUNKS; dz++) {
                result.add(new ChunkPos(center.x + dx, center.z + dz));
            }
        }
        return result;
    }

    private record TicketKey(UUID npcId, long chunkLong) {
    }

    private static final class ManagedNpc {
        private final UUID npcId;
        private final Set<ChunkPos> forcedChunks = new LinkedHashSet<>();
        @Nullable
        private net.minecraftforge.common.util.FakePlayer tabPlayer;
        @Nullable
        private net.minecraft.resources.ResourceKey<Level> levelKey;
        @Nullable
        private net.minecraft.resources.ResourceKey<Level> tabPlayerLevelKey;
        @Nullable
        private ChunkPos centerChunk;
        private int entityId = -1;
        private String displayName = "";
        private String profileSignature = "";
        private int unresolvedTicks;
        private boolean tabListed;

        private ManagedNpc(UUID npcId) {
            this.npcId = npcId;
        }

        private void updateFrom(MinecraftServer server, PlayerNpcEntity npc) {
            ServerLevel level = (ServerLevel) npc.level();
            net.minecraft.resources.ResourceKey<Level> currentLevelKey = level.dimension();
            if (this.levelKey != null && !this.levelKey.equals(currentLevelKey)) {
                this.releaseTickets(server);
            }

            this.levelKey = currentLevelKey;
            this.entityId = npc.getId();

            String nextDisplayName = displayName(npc);
            boolean displayNameChanged = !Objects.equals(this.displayName, nextDisplayName);
            this.displayName = nextDisplayName;

            String nextProfileSignature = profilePropertiesSignature(npc.getProfile());
            boolean profileChanged = !Objects.equals(this.profileSignature, nextProfileSignature);
            this.profileSignature = nextProfileSignature;
            this.unresolvedTicks = 0;

            this.updateForceTickets(level, npc.chunkPosition());
            PlayerNpcForceTickData.get(server).put(this.npcId, currentLevelKey, npc.chunkPosition());
            this.updateTabList(server, level, npc, displayNameChanged, profileChanged);
        }

        @Nullable
        private PlayerNpcEntity resolve(MinecraftServer server) {
            if (this.levelKey == null) {
                return null;
            }

            ServerLevel level = server.getLevel(this.levelKey);
            if (level == null) {
                return null;
            }

            Entity entity = level.getEntity(this.npcId);
            if (entity instanceof PlayerNpcEntity playerNpc && playerNpc.isAlive() && !playerNpc.isRemoved()) {
                return playerNpc;
            }
            return null;
        }

        private void restoreFromData(MinecraftServer server, ServerLevel level, ChunkPos savedCenterChunk) {
            net.minecraft.resources.ResourceKey<Level> savedLevelKey = level.dimension();
            if (this.levelKey != null && !this.levelKey.equals(savedLevelKey)) {
                this.releaseTickets(server);
            }

            this.levelKey = savedLevelKey;
            this.entityId = -1;
            this.unresolvedTicks = 0;
            this.updateForceTickets(level, savedCenterChunk);
        }

        private boolean shouldKeepWaitingForEntity(MinecraftServer server) {
            if (this.levelKey == null || this.centerChunk == null) {
                return false;
            }

            ServerLevel level = server.getLevel(this.levelKey);
            if (level == null) {
                return false;
            }

            if (this.forcedChunks.isEmpty()) {
                this.updateForceTickets(level, this.centerChunk);
            }

            this.unresolvedTicks++;
            return this.unresolvedTicks <= RESTORED_ENTITY_LOAD_GRACE_TICKS
                    || !level.hasChunk(this.centerChunk.x, this.centerChunk.z);
        }

        private void updateForceTickets(ServerLevel level, ChunkPos nextCenterChunk) {
            if (this.centerChunk != null && this.centerChunk.equals(nextCenterChunk)) {
                if (this.forcedChunks.isEmpty()) {
                    this.centerChunk = null;
                    this.updateForceTickets(level, nextCenterChunk);
                }
                return;
            }

            Set<ChunkPos> nextChunks = forceTickChunksAround(nextCenterChunk);
            for (ChunkPos oldChunk : new ArrayList<>(this.forcedChunks)) {
                if (!nextChunks.contains(oldChunk)) {
                    this.removeTicket(level, oldChunk);
                    this.forcedChunks.remove(oldChunk);
                }
            }
            for (ChunkPos nextChunk : nextChunks) {
                if (!this.forcedChunks.contains(nextChunk)) {
                    this.addTicket(level, nextChunk);
                    this.forcedChunks.add(nextChunk);
                }
            }
            this.centerChunk = nextCenterChunk;
        }

        private void addTicket(ServerLevel level, ChunkPos chunkPos) {
            level.getChunkSource().addRegionTicket(
                    PLAYER_NPC_TICKET,
                    chunkPos,
                    FORCE_TICK_DISTANCE,
                    new TicketKey(this.npcId, chunkPos.toLong()),
                    true
            );
        }

        private void removeTicket(ServerLevel level, ChunkPos chunkPos) {
            level.getChunkSource().removeRegionTicket(
                    PLAYER_NPC_TICKET,
                    chunkPos,
                    FORCE_TICK_DISTANCE,
                    new TicketKey(this.npcId, chunkPos.toLong()),
                    true
            );
        }

        private void releaseTickets(MinecraftServer server) {
            if (this.levelKey == null || this.forcedChunks.isEmpty()) {
                this.centerChunk = null;
                this.forcedChunks.clear();
                return;
            }

            ServerLevel level = server.getLevel(this.levelKey);
            if (level != null) {
                for (ChunkPos chunkPos : new ArrayList<>(this.forcedChunks)) {
                    this.removeTicket(level, chunkPos);
                }
            }
            this.centerChunk = null;
            this.forcedChunks.clear();
        }

        private void updateTabList(
                MinecraftServer server,
                ServerLevel level,
                PlayerNpcEntity npc,
                boolean displayNameChanged,
                boolean profileChanged
        ) {
            net.minecraftforge.common.util.FakePlayer fakePlayer = this.tabPlayer(level, npc);
            if (!this.tabListed) {
                server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fakePlayer)));
                this.tabListed = true;
                return;
            }

            if (profileChanged) {
                server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(this.npcId)));
                server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(fakePlayer)));
                this.tabListed = true;
                return;
            }

            if (displayNameChanged) {
                server.getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                        fakePlayer
                ));
            }
        }

        private void sendTabAdd(ServerPlayer viewer, PlayerNpcEntity npc) {
            if (!(npc.level() instanceof ServerLevel level)) {
                return;
            }

            viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(this.tabPlayer(level, npc))));
        }

        private net.minecraftforge.common.util.FakePlayer tabPlayer(ServerLevel level, PlayerNpcEntity npc) {
            if (this.tabPlayer == null || !Objects.equals(this.tabPlayerLevelKey, level.dimension())) {
                this.tabPlayer = FakePlayerFactory.get(level, createTabProfile(npc));
                this.tabPlayerLevelKey = level.dimension();
            }
            copyProfileProperties(npc.getProfile(), this.tabPlayer.getGameProfile());
            this.tabPlayer.latency = 0;
            if (this.tabPlayer.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) {
                this.tabPlayer.setGameMode(GameType.SPECTATOR);
            }
            return this.tabPlayer;
        }

        private void broadcastTabRemove(MinecraftServer server) {
            if (!this.tabListed) {
                return;
            }
            server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(this.npcId)));
            this.tabListed = false;
        }

        private Component tabDisplayName() {
            return Component.literal(NPC_TAB_PREFIX)
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(this.displayName));
        }
    }
}
