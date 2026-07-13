package com.pla.smart_npc.util;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

@Mod.EventBusSubscriber(modid = SmartNpc.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerNpcPerformanceMonitor {
    private static final int ROLLING_WINDOW_TICKS = 100;
    private static final int MIN_AVERAGE_WARNING_SAMPLES = 20;
    private static final int STARTUP_WARMUP_TICKS = 20 * 10;
    private static final int PLAYER_JOIN_WARMUP_TICKS = 20 * 8;
    private static final int PLAYER_NPC_JOIN_WARMUP_TICKS = 20 * 5;
    private static final int POST_STALL_WARMUP_TICKS = 20 * 8;
    private static final double NANOS_PER_MILLISECOND = 1_000_000.0D;
    private static final double MAX_TPS = 20.0D;
    private static final double PAUSE_OR_LOAD_TICK_MSPT = 1000.0D;
    private static final double ROLLING_WARNING_CURRENT_TICK_MSPT_FLOOR = 50.0D;
    private static final double HEALTHY_AVERAGE_SPIKE_SUPPRESSION_MSPT = 50.0D;
    private static final double SEVERE_SINGLE_TICK_SPIKE_MSPT = 1000.0D;
    private static final String PASSIVE_HOME_STATE = "ai.player_npc.being_at_home";

    private static final double[] rollingMspt = new double[ROLLING_WINDOW_TICKS];
    private static int rollingIndex;
    private static int rollingCount;
    private static double rollingTotalMspt;
    private static double latestMspt;
    private static long tickStartNanos = -1L;
    private static long ignoreSamplesUntilServerTick = Long.MIN_VALUE;
    private static long lastWarningServerTick = Long.MIN_VALUE;
    private static long lastSuppressedWarningServerTick = Long.MIN_VALUE;

    private PlayerNpcPerformanceMonitor() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!SmartNpcConfig.PERFORMANCE_MONITOR_ENABLED.get()) {
            tickStartNanos = -1L;
            return;
        }

        if (event.phase == TickEvent.Phase.START) {
            tickStartNanos = System.nanoTime();
            return;
        }

        if (event.phase != TickEvent.Phase.END || tickStartNanos < 0L) {
            return;
        }

        long elapsedNanos = Math.max(0L, System.nanoTime() - tickStartNanos);
        tickStartNanos = -1L;

        latestMspt = elapsedNanos / NANOS_PER_MILLISECOND;
        if (shouldIgnoreSample(event.getServer(), latestMspt)) {
            resetSamples();
            latestMspt = 0.0D;
            return;
        }

        addSample(latestMspt);
        maybeLogWarning(event.getServer(), latestMspt);
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity().level() instanceof ServerLevel serverLevel) {
            startWarmup(serverLevel.getServer(), PLAYER_JOIN_WARMUP_TICKS);
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof PlayerNpcEntity
                && event.getLevel() instanceof ServerLevel serverLevel) {
            startWarmup(serverLevel.getServer(), PLAYER_NPC_JOIN_WARMUP_TICKS);
        }
    }

    public static String createInspectorText() {
        if (!SmartNpcConfig.PERFORMANCE_MONITOR_ENABLED.get()) {
            return "TPS monitor off";
        }
        if (rollingCount <= 0) {
            return "TPS warming up";
        }

        return String.format(
                Locale.ROOT,
                "TPS %.1f/20 | MSPT %.1f avg, %.1f last",
                getAverageTps(),
                getAverageMspt(),
                latestMspt
        );
    }

    private static void addSample(double mspt) {
        if (rollingCount < rollingMspt.length) {
            rollingCount++;
        } else {
            rollingTotalMspt -= rollingMspt[rollingIndex];
        }

        rollingMspt[rollingIndex] = mspt;
        rollingTotalMspt += mspt;
        rollingIndex = (rollingIndex + 1) % rollingMspt.length;
    }

    private static boolean shouldIgnoreSample(MinecraftServer server, double mspt) {
        if (server.getTickCount() < STARTUP_WARMUP_TICKS
                || server.getTickCount() < ignoreSamplesUntilServerTick) {
            return true;
        }
        if (mspt >= PAUSE_OR_LOAD_TICK_MSPT) {
            startWarmup(server, POST_STALL_WARMUP_TICKS);
            return true;
        }
        return false;
    }

    private static void startWarmup(MinecraftServer server, int ticks) {
        if (server == null || ticks <= 0) {
            return;
        }

        ignoreSamplesUntilServerTick = Math.max(ignoreSamplesUntilServerTick, server.getTickCount() + ticks);
        resetSamples();
        latestMspt = 0.0D;
    }

    private static void resetSamples() {
        rollingIndex = 0;
        rollingCount = 0;
        rollingTotalMspt = 0.0D;
    }

    private static double getAverageMspt() {
        return rollingCount <= 0 ? 0.0D : rollingTotalMspt / rollingCount;
    }

    private static double getAverageTps() {
        double averageMspt = getAverageMspt();
        if (averageMspt <= 0.0D) {
            return MAX_TPS;
        }
        return Math.min(MAX_TPS, 1000.0D / averageMspt);
    }

    private static void maybeLogWarning(MinecraftServer server, double currentMspt) {
        double averageMspt = getAverageMspt();
        double averageWarningThreshold = SmartNpcConfig.PERFORMANCE_WARNING_AVERAGE_MSPT.get();
        boolean slowAverage = rollingCount >= MIN_AVERAGE_WARNING_SAMPLES
                && averageMspt >= averageWarningThreshold
                && currentMspt >= Math.min(averageWarningThreshold, ROLLING_WARNING_CURRENT_TICK_MSPT_FLOOR);
        boolean tickSpike = rollingCount >= MIN_AVERAGE_WARNING_SAMPLES
                && currentMspt >= SmartNpcConfig.PERFORMANCE_WARNING_SPIKE_MSPT.get()
                && (averageMspt >= HEALTHY_AVERAGE_SPIKE_SUPPRESSION_MSPT
                || currentMspt >= SEVERE_SINGLE_TICK_SPIKE_MSPT);
        if (!slowAverage && !tickSpike) {
            return;
        }

        long serverTick = server.getTickCount();
        int cooldownTicks = SmartNpcConfig.PERFORMANCE_WARNING_COOLDOWN_TICKS.get();
        if (lastWarningServerTick != Long.MIN_VALUE
                && serverTick - lastWarningServerTick < cooldownTicks) {
            return;
        }
        if (lastSuppressedWarningServerTick != Long.MIN_VALUE
                && serverTick - lastSuppressedWarningServerTick < Math.min(20, cooldownTicks)) {
            return;
        }

        NpcTraceSummary summary = collectNpcTrace(server, SmartNpcConfig.PERFORMANCE_WARNING_NPC_TRACE_LIMIT.get());
        if (summary.activeNpcCount() <= 0) {
            lastSuppressedWarningServerTick = serverTick;
            return;
        }

        lastWarningServerTick = serverTick;
        SmartNpc.LOGGER.warn(
                "Smart NPC TPS warning: server tick is slow; latestMspt={}, averageMspt={}, effectiveTps={}/20, sampleWindowTicks={}, reason={}, activePlayerNpcGoals={}, totalPlayerNpcs={}",
                format(currentMspt),
                format(averageMspt),
                format(getAverageTps()),
                rollingCount,
                tickSpike ? "single tick spike" : "rolling average",
                summary.activeNpcCount(),
                summary.totalNpcCount()
        );

        SmartNpc.LOGGER.warn("Smart NPC TPS trace states: {}", summary.stateCountsText());
        for (String traceLine : summary.traceLines()) {
            SmartNpc.LOGGER.warn("Smart NPC TPS trace: {}", traceLine);
        }
    }

    private static NpcTraceSummary collectNpcTrace(MinecraftServer server, int traceLimit) {
        int totalNpcCount = 0;
        int activeNpcCount = 0;
        int normalizedTraceLimit = Math.max(0, traceLimit);
        Map<String, Integer> stateCounts = new LinkedHashMap<>();
        List<String> traceLines = new ArrayList<>();

        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (!(entity instanceof PlayerNpcEntity playerNpc) || !playerNpc.isAlive()) {
                    continue;
                }

                totalNpcCount++;
                String state = sanitize(playerNpc.getCurrentAiState());
                if (state.isBlank() || PlayerNpcEntity.AI_IDLE.equals(state)) {
                    continue;
                }
                if (!isPerformanceRelevantState(state)) {
                    continue;
                }

                activeNpcCount++;
                stateCounts.merge(state, 1, Integer::sum);
                if (traceLines.size() < normalizedTraceLimit) {
                    traceLines.add(createTraceLine(level, playerNpc, state));
                }
            }
        }

        return new NpcTraceSummary(totalNpcCount, activeNpcCount, stateCounts, traceLines);
    }

    private static boolean isPerformanceRelevantState(String state) {
        return !PASSIVE_HOME_STATE.equals(state);
    }

    private static String createTraceLine(ServerLevel level, PlayerNpcEntity playerNpc, String state) {
        BlockPos pos = playerNpc.blockPosition();
        LivingEntity target = playerNpc.getTarget();
        String targetText = target == null
                ? "none"
                : target.getType().toShortString() + "#" + target.getId();
        String detail = sanitize(playerNpc.getCurrentAiDetail());
        if (detail.isBlank()) {
            detail = "none";
        }

        return String.format(
                Locale.ROOT,
                "%s#%d dim=%s pos=%d,%d,%d state=%s detail=\"%s\" target=%s navDone=%s navStuck=%s",
                sanitize(playerNpc.getDisplayName().getString()),
                playerNpc.getId(),
                level.dimension().location(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                state,
                detail,
                targetText,
                playerNpc.getNavigation().isDone(),
                playerNpc.getNavigation().isStuck()
        );
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private record NpcTraceSummary(
            int totalNpcCount,
            int activeNpcCount,
            Map<String, Integer> stateCounts,
            List<String> traceLines
    ) {
        private String stateCountsText() {
            StringJoiner joiner = new StringJoiner(", ");
            this.stateCounts.forEach((state, count) -> joiner.add(state + "=" + count));
            String text = joiner.toString();
            return text.isBlank() ? "none" : text;
        }
    }
}
