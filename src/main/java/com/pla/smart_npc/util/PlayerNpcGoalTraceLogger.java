package com.pla.smart_npc.util;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.clazz.PlayerNpcInterest;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.entity.ai.FurnaceAi;
import com.pla.smart_npc.entity.ai.ResourceAi;
import com.pla.smart_npc.entity.goal.BuildHouseGoal;
import com.pla.smart_npc.entity.goal.InterestGatedGoal;
import com.pla.smart_npc.entity.goal.TerraformBuildSiteGoal;
import com.pla.smart_npc.network.PlayerNpcInspectatorModePacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

@Mod.EventBusSubscriber(modid = SmartNpc.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerNpcGoalTraceLogger {
    private static final String ACTIVE_KEY = "PlayerNpcGoalTraceActive";
    private static final String ENTITY_UUID_KEY = "PlayerNpcGoalTraceEntityUuid";
    private static final String ENTITY_ID_KEY = "PlayerNpcGoalTraceEntityId";
    private static final String LAST_LOG_TICK_KEY = "PlayerNpcGoalTraceLastLogTick";
    private static final String LAST_STATE_KEY = "PlayerNpcGoalTraceLastState";
    private static final int TRACE_INTERVAL_TICKS = 20;
    private static final int BUILDING_TEXT_CACHE_TICKS = 40;
    private static final double MAX_NON_INSPECTATOR_TRACE_DISTANCE_SQR = 64.0D * 64.0D;
    private static final Map<PlayerNpcEntity, BuildingTextCache> BUILDING_TEXT_CACHE = new WeakHashMap<>();

    private PlayerNpcGoalTraceLogger() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        long serverTick = event.getServer().getTickCount();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            CompoundTag data = player.getPersistentData();
            if (!data.getBoolean(ACTIVE_KEY)) {
                continue;
            }

            PlayerNpcEntity tracedNpc = getTracedNpc(player);
            if (tracedNpc == null
                    || !canKeepTracing(player, tracedNpc)) {
                stopTrace(player, "trace target unavailable");
                continue;
            }

            long lastLogTick = data.getLong(LAST_LOG_TICK_KEY);
            if (lastLogTick > 0L && serverTick - lastLogTick < TRACE_INTERVAL_TICKS) {
                continue;
            }

            data.putLong(LAST_LOG_TICK_KEY, serverTick);
            logTraceLine(player, tracedNpc, serverTick);
        }
    }

    public static boolean isTracing(ServerPlayer player, PlayerNpcEntity playerNpc) {
        if (player == null || playerNpc == null) {
            return false;
        }

        CompoundTag data = player.getPersistentData();
        return data.getBoolean(ACTIVE_KEY)
                && data.hasUUID(ENTITY_UUID_KEY)
                && data.getUUID(ENTITY_UUID_KEY).equals(playerNpc.getUUID());
    }

    public static void setTraceEnabled(ServerPlayer player, PlayerNpcEntity playerNpc, boolean enabled) {
        if (player == null) {
            return;
        }

        if (!enabled) {
            stopTrace(player, "disabled by viewer");
            return;
        }

        if (playerNpc == null || !playerNpc.isAlive()) {
            stopTrace(player, "target missing");
            return;
        }

        CompoundTag data = player.getPersistentData();
        data.putBoolean(ACTIVE_KEY, true);
        data.putUUID(ENTITY_UUID_KEY, playerNpc.getUUID());
        data.putInt(ENTITY_ID_KEY, playerNpc.getId());
        data.putLong(LAST_LOG_TICK_KEY, 0L);
        data.putString(LAST_STATE_KEY, sanitize(playerNpc.getCurrentAiState()));

        SmartNpc.LOGGER.info(
                "Smart NPC goal trace enabled: viewer={} npc={}#{} dim={} pos={}",
                player.getGameProfile().getName(),
                sanitize(playerNpc.getDisplayName().getString()),
                playerNpc.getId(),
                dimensionText(playerNpc),
                posText(playerNpc.blockPosition())
        );
    }

    public static void stopTrace(ServerPlayer player, String reason) {
        if (player == null) {
            return;
        }

        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(ACTIVE_KEY)) {
            clearTraceData(data);
            return;
        }

        int entityId = data.getInt(ENTITY_ID_KEY);
        clearTraceData(data);
        SmartNpc.LOGGER.info(
                "Smart NPC goal trace disabled: viewer={} npcId={} reason={}",
                player.getGameProfile().getName(),
                entityId,
                sanitize(reason)
        );
    }

    public static void stopTrace(ServerPlayer player) {
        stopTrace(player, "inspectator stopped");
    }

    public static void stopIfTracingDifferentNpc(ServerPlayer player, PlayerNpcEntity playerNpc) {
        if (player == null || playerNpc == null) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        if (!data.getBoolean(ACTIVE_KEY) || !data.hasUUID(ENTITY_UUID_KEY)) {
            return;
        }
        if (!data.getUUID(ENTITY_UUID_KEY).equals(playerNpc.getUUID())) {
            stopTrace(player, "inspectator target changed");
        }
    }

    private static void logTraceLine(ServerPlayer viewer, PlayerNpcEntity playerNpc, long serverTick) {
        CompoundTag data = viewer.getPersistentData();
        String state = sanitize(playerNpc.getCurrentAiState());
        String previousState = sanitize(data.getString(LAST_STATE_KEY));
        String stateChange = previousState.isBlank() || previousState.equals(state)
                ? "none"
                : previousState + "->" + state;
        data.putString(LAST_STATE_KEY, state);

        String detail = sanitize(playerNpc.getCurrentAiDetail());
        String result = traceResult(playerNpc, state);
        SmartNpc.LOGGER.info(
                "Smart NPC goal trace: viewer={} tick={} npc={}#{} dim={} pos={} health={}/{} flags={} state={} stateChange={} detail=\"{}\" result={} target={} navigation={} cooldowns={} building={} runningGoals={} runningTargetGoals={}",
                viewer.getGameProfile().getName(),
                serverTick,
                sanitize(playerNpc.getDisplayName().getString()),
                playerNpc.getId(),
                dimensionText(playerNpc),
                posText(playerNpc.blockPosition()),
                format(playerNpc.getHealth()),
                format(playerNpc.getMaxHealth()),
                flagsText(playerNpc),
                state.isBlank() ? "none" : state,
                stateChange,
                detail.isBlank() ? "none" : detail,
                result,
                targetText(playerNpc.getTarget()),
                navigationText(playerNpc.getNavigation()),
                cooldownsText(playerNpc),
                buildingText(playerNpc),
                runningGoalsText(playerNpc.goalSelector.getRunningGoals().collect(Collectors.toList())),
                runningGoalsText(playerNpc.targetSelector.getRunningGoals().collect(Collectors.toList()))
        );
    }

    private static PlayerNpcEntity getTracedNpc(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.hasUUID(ENTITY_UUID_KEY) || !(player.level() instanceof ServerLevel level)) {
            return null;
        }

        UUID npcUuid = data.getUUID(ENTITY_UUID_KEY);
        if (level.getEntity(npcUuid) instanceof PlayerNpcEntity playerNpc && playerNpc.isAlive()) {
            return playerNpc;
        }
        return null;
    }

    private static boolean canKeepTracing(ServerPlayer player, PlayerNpcEntity playerNpc) {
        if (PlayerNpcInspectatorModePacket.isInspectatorActive(player)) {
            return player.getVehicle() == playerNpc;
        }

        return player.distanceToSqr(playerNpc) <= MAX_NON_INSPECTATOR_TRACE_DISTANCE_SQR;
    }

    private static String runningGoalsText(java.util.List<WrappedGoal> runningGoals) {
        if (runningGoals.isEmpty()) {
            return "none";
        }
        return runningGoals.stream()
                .map(PlayerNpcGoalTraceLogger::goalText)
                .collect(Collectors.joining(","));
    }

    private static String goalText(WrappedGoal wrappedGoal) {
        Goal goal = unwrapGoal(wrappedGoal.getGoal());
        return wrappedGoal.getPriority() + ":" + goal.getClass().getSimpleName();
    }

    private static Goal unwrapGoal(Goal goal) {
        if (goal instanceof InterestGatedGoal interestGatedGoal) {
            return interestGatedGoal.getDelegateGoal();
        }
        return goal;
    }

    private static String traceResult(PlayerNpcEntity playerNpc, String state) {
        boolean idle = state.isBlank() || PlayerNpcEntity.AI_IDLE.equals(state);
        boolean hasRunningGoal = playerNpc.goalSelector.getRunningGoals().findAny().isPresent()
                || playerNpc.targetSelector.getRunningGoals().findAny().isPresent();
        if (idle && !hasRunningGoal) {
            return "idle_no_running_goal";
        }
        if (idle) {
            return "idle_with_running_goal";
        }
        return hasRunningGoal ? "goal_running" : "state_set_without_running_goal";
    }

    private static String flagsText(PlayerNpcEntity playerNpc) {
        StringJoiner joiner = new StringJoiner(",");
        if (playerNpc.isHealing()) {
            joiner.add("healing");
        }
        if (playerNpc.isNoAi()) {
            joiner.add("noAi");
        }
        if (playerNpc.isPassenger()) {
            joiner.add("passenger");
        }
        if (playerNpc.isSleeping()) {
            joiner.add("sleeping");
        }
        if (playerNpc.isUsingItem()) {
            joiner.add("usingItem");
        }
        if (playerNpc.getTarget() != null) {
            joiner.add("hasTarget");
        }
        String text = joiner.toString();
        return text.isBlank() ? "none" : text;
    }

    private static String cooldownsText(PlayerNpcEntity playerNpc) {
        StringJoiner joiner = new StringJoiner(",");
        appendCooldown(joiner, "gap", playerNpc.getGapCooldown());
        appendCooldown(joiner, "bucket", playerNpc.getBucketCooldown());
        appendCooldown(joiner, "pearl", playerNpc.getEnderPearlCooldown());
        appendCooldown(joiner, "help", playerNpc.getHelpAlertCooldown());
        appendCooldown(joiner, "hole", playerNpc.getHoleEscapeCooldown());
        appendCooldown(joiner, "hide", playerNpc.getScaredHideCooldown());
        appendCooldown(joiner, "build", playerNpc.getBuildHouseCooldown());
        appendCooldown(joiner, "cook", playerNpc.getCookFoodCooldown());
        appendCooldown(joiner, "craftGear", playerNpc.getCraftGearCooldown());
        appendCooldown(joiner, "farm", playerNpc.getFarmCooldown());
        appendCooldown(joiner, "gather", playerNpc.getGatherCooldown());
        appendCooldown(joiner, "biome", playerNpc.getBiomeExploreCooldown());
        appendCooldown(joiner, "sheep", playerNpc.getHuntSheepCooldown());
        appendCooldown(joiner, "loot", playerNpc.getLootChestCooldown());
        appendCooldown(joiner, "home", playerNpc.getManageHomeCooldown());
        appendCooldown(joiner, "fish", playerNpc.getFishingCooldown());
        appendCooldown(joiner, "return", playerNpc.getReturnHomeCooldown());
        appendCooldown(joiner, "sleep", playerNpc.getSleepCooldown());
        appendCooldown(joiner, "craft", playerNpc.getCraftCooldown());
        appendCooldown(joiner, "ore", playerNpc.getOreMiningCooldown());
        appendCooldown(joiner, "ironGear", playerNpc.getIronGearCooldown());
        appendCooldown(joiner, "spyglass", playerNpc.getSpyglassCooldown());
        appendCooldown(joiner, "sapling", playerNpc.getSaplingPlantCooldown());
        appendCooldown(joiner, "boatStock", playerNpc.getBoatStockCooldown());
        appendCooldown(joiner, "boatTrap", playerNpc.getBoatTrapCooldown());
        appendCooldown(joiner, "dance", playerNpc.getJukeboxDanceCooldown());
        appendCooldown(joiner, "troll", playerNpc.getTrollHitCooldown());
        appendCooldown(joiner, "combatFish", playerNpc.getCombatFishingCooldown());
        appendCooldown(joiner, "shieldCraft", playerNpc.getShieldCraftCooldown());
        appendCooldown(joiner, "shieldGuard", playerNpc.getShieldGuardCooldown());
        String text = joiner.toString();
        return text.isBlank() ? "none" : text;
    }

    private static String buildingText(PlayerNpcEntity playerNpc) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)
                || !playerNpc.hasInterest(PlayerNpcInterest.BUILDING)) {
            return "none";
        }

        int logs = ResourceAi.countLogs(playerNpc);
        int stone = ResourceAi.countStone(playerNpc);
        String homeKey = PlayerNpcHomeUtil.getHome(playerNpc)
                .map(home -> home.origin() + ":" + home.width() + "x" + home.depth())
                .orElse("none");
        String layoutId = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc).orElse("");
        BuildingTextCache cache = BUILDING_TEXT_CACHE.get(playerNpc);
        if (cache != null && cache.matches(playerNpc.tickCount, homeKey, layoutId, logs, stone, playerNpc.getLogSupplyGoal(), playerNpc.getStoneSupplyGoal())) {
            return cache.text();
        }

        String missing = PlayerNpcBuildMaterialUtil.findMissingBuildMaterialNeed(serverLevel, playerNpc)
                .map(need -> need.kind().name().toLowerCase(Locale.ROOT)
                        + ":"
                        + sanitize(need.description()))
                .orElse("none");
        FurnaceAi furnaceAi = new FurnaceAi(playerNpc);
        String text = "logs=" + logs + "/" + playerNpc.getLogSupplyGoal()
                + ",stone=" + stone + "/" + playerNpc.getStoneSupplyGoal()
                + ",prep=" + TerraformBuildSiteGoal.hasActionablePrepWork(playerNpc, serverLevel)
                + ",prepNeedsShovel=" + TerraformBuildSiteGoal.needsShovelForPrep(playerNpc, serverLevel)
                + ",build=" + BuildHouseGoal.hasReadyHomeBuildWork(playerNpc, serverLevel)
                + ",needLogs=" + PlayerNpcBuildMaterialUtil.needsLogsForCurrentBuild(serverLevel, playerNpc)
                + ",needStone=" + PlayerNpcBuildMaterialUtil.needsStoneForCurrentBuild(serverLevel, playerNpc)
                + ",torchCharcoal=" + PlayerNpcBuildMaterialUtil.needsTorchCharcoalSmelting(serverLevel, playerNpc)
                + ",furnaceInput=" + furnaceAi.hasInputForWork(serverLevel)
                + ",furnaceFuel=" + furnaceAi.hasFuel()
                + ",furnacePlace=" + furnaceAi.shouldPlaceFurnaceForWork(serverLevel)
                + ",missing=" + missing;
        BUILDING_TEXT_CACHE.put(playerNpc, new BuildingTextCache(
                playerNpc.tickCount,
                homeKey,
                layoutId,
                logs,
                stone,
                playerNpc.getLogSupplyGoal(),
                playerNpc.getStoneSupplyGoal(),
                text
        ));
        return text;
    }

    private static void appendCooldown(StringJoiner joiner, String name, int ticks) {
        if (ticks > 0) {
            joiner.add(name + "=" + ticks);
        }
    }

    private static String navigationText(PathNavigation navigation) {
        Path path = navigation.getPath();
        if (path == null) {
            return String.format(
                    Locale.ROOT,
                    "done=%s stuck=%s target=%s path=none",
                    navigation.isDone(),
                    navigation.isStuck(),
                    posText(navigation.getTargetPos())
            );
        }

        Node endNode = path.getEndNode();
        String nextNodePos = path.isDone() ? "done" : posText(path.getNextNodePos());
        return String.format(
                Locale.ROOT,
                "done=%s stuck=%s target=%s pathTarget=%s canReach=%s next=%d/%d nextPos=%s end=%s dist=%.2f",
                navigation.isDone(),
                navigation.isStuck(),
                posText(navigation.getTargetPos()),
                posText(path.getTarget()),
                path.canReach(),
                path.getNextNodeIndex(),
                path.getNodeCount(),
                nextNodePos,
                endNode == null ? "none" : posText(endNode.asBlockPos()),
                path.getDistToTarget()
        );
    }

    private static String targetText(LivingEntity target) {
        if (target == null) {
            return "none";
        }
        return target.getType().toShortString()
                + "#"
                + target.getId()
                + "@"
                + posText(target.blockPosition())
                + " alive="
                + target.isAlive();
    }

    private static String dimensionText(PlayerNpcEntity playerNpc) {
        return playerNpc.level().dimension().location().toString();
    }

    private static String posText(BlockPos pos) {
        if (pos == null) {
            return "none";
        }
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void clearTraceData(CompoundTag data) {
        data.remove(ACTIVE_KEY);
        data.remove(ENTITY_UUID_KEY);
        data.remove(ENTITY_ID_KEY);
        data.remove(LAST_LOG_TICK_KEY);
        data.remove(LAST_STATE_KEY);
    }

    private record BuildingTextCache(
            int tick,
            String homeKey,
            String layoutId,
            int logs,
            int stone,
            int logGoal,
            int stoneGoal,
            String text
    ) {
        private boolean matches(int currentTick, String currentHomeKey, String currentLayoutId, int currentLogs, int currentStone, int currentLogGoal, int currentStoneGoal) {
            return currentTick - this.tick <= BUILDING_TEXT_CACHE_TICKS
                    && this.homeKey.equals(currentHomeKey)
                    && this.layoutId.equals(currentLayoutId)
                    && this.logs == currentLogs
                    && this.stone == currentStone
                    && this.logGoal == currentLogGoal
                    && this.stoneGoal == currentStoneGoal;
        }
    }
}
