package com.pla.smart_npc.util;

import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Fair server-thread scheduler for routine Player NPC work and expensive search/path slices.
 * Worker count is a CPU scheduling limit, not a percentage of allocated memory.
 */
public final class PlayerNpcAiWorkBudget {
    private static final int MAX_EXPENSIVE_BATCHES_PER_TICK = 1;
    private static final int MAX_ACTIVE_WORK_TICKS = 20 * 120;
    private static final int PROBE_REQUEST_STALE_TICKS = 5;
    private static final int EXPENSIVE_REQUEST_STALE_TICKS = 40;
    private static final int DENIAL_VISIBLE_TICKS = 20 * 2;
    private static final double AUTO_TARGET_MSPT = 40.0D;
    private static final int AUTO_EVALUATION_INTERVAL_TICKS = 20 * 5;
    private static final int AUTO_HEALTHY_GROWTH_CHECKS = 2;
    private static final int AUTO_CAUTION_GROWTH_CHECKS = 5;
    private static final int AUTO_HIGH_HEALTHY_GROWTH_CHECKS = 3;
    private static final int AUTO_HIGH_CAUTION_GROWTH_CHECKS = 6;
    private static final int AUTO_OVERLOAD_REDUCTION_CHECKS = 2;
    private static final double AUTO_CAUTION_PROBE_MAX_MSPT = 49.0D;
    private static final double AUTO_REDUCTION_MSPT = 52.0D;
    private static final Map<MinecraftServer, SchedulerState> SERVER_SCHEDULERS = new WeakHashMap<>();

    private PlayerNpcAiWorkBudget() {
    }

    public static void clear(MinecraftServer server) {
        if (server != null) {
            SERVER_SCHEDULERS.remove(server);
        }
    }

    /**
     * Gives one NPC a one-tick probe turn. A slot is occupied beyond this tick only if a delegate
     * actually starts, so prerequisite-blocked NPCs cannot reserve the worker window.
     */
    public static boolean canStartWork(PlayerNpcEntity playerNpc, int predicateSlice, int sliceCount) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        long tick = serverLevel.getServer().getTickCount();
        SchedulerState scheduler = scheduler(serverLevel);
        return scheduler.canProbe(playerNpc, tick, resolveWorkerLimit(serverLevel.getServer()))
                && scheduler.canEvaluatePredicateSlice(playerNpc.getUUID(), tick, predicateSlice, sliceCount);
    }

    public static boolean canContinueWork(PlayerNpcEntity playerNpc) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        long tick = serverLevel.getServer().getTickCount();
        return scheduler(serverLevel).canContinue(playerNpc, tick, resolveWorkerLimit(serverLevel.getServer()));
    }

    public static void onWorkStarted(PlayerNpcEntity playerNpc) {
        if (playerNpc.level() instanceof ServerLevel serverLevel) {
            scheduler(serverLevel).workStarted(playerNpc, serverLevel.getServer().getTickCount(), resolveWorkerLimit(serverLevel.getServer()));
        }
    }

    public static void onWorkStopped(PlayerNpcEntity playerNpc) {
        if (playerNpc.level() instanceof ServerLevel serverLevel) {
            scheduler(serverLevel).workStopped(playerNpc, serverLevel.getServer().getTickCount(), resolveWorkerLimit(serverLevel.getServer()));
        }
    }

    public static boolean isWaitingForTurn(PlayerNpcEntity playerNpc) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        long tick = serverLevel.getServer().getTickCount();
        return scheduler(serverLevel).isWaiting(playerNpc.getUUID(), tick, resolveWorkerLimit(serverLevel.getServer()));
    }

    /**
     * Caps optional waiting-stroll path starts separately from routine worker ownership. Vanilla
     * target selection has already succeeded when this is requested. A denied NPC remains a
     * non-worker; this only prevents multiple visual fallback paths from being created in one tick.
     */
    public static boolean tryAcquireWaitingStrollPathStart(PlayerNpcEntity playerNpc) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        return scheduler(serverLevel).tryAcquireWaitingStrollPathStart(serverLevel.getServer().getTickCount());
    }

    /**
     * True only while the NPC owns a scheduler worker/probe resource or consumed the current
     * tick's expensive-work slice. Queue membership alone is deliberately not a resource.
     */
    public static boolean hasResource(PlayerNpcEntity playerNpc) {
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return false;
        }
        SchedulerState scheduler = SERVER_SCHEDULERS.get(serverLevel.getServer());
        if (scheduler == null) {
            return false;
        }
        long tick = serverLevel.getServer().getTickCount();
        return scheduler.hasResource(playerNpc.getUUID(), tick, resolveWorkerLimit(serverLevel.getServer()));
    }

    /** Read-only, bounded view used by commands and diagnostics on the server thread. */
    public static ResourceSnapshot resourceSnapshot(MinecraftServer server) {
        int configuredLimit = SmartNpcConfig.AI_PROCESSING_NPC_LIMIT.get();
        int effectiveLimit = resolveWorkerLimit(server);
        if (server == null) {
            return new ResourceSnapshot(0L, configuredLimit, effectiveLimit, 0, 0, List.of());
        }

        long tick = server.getTickCount();
        SchedulerState scheduler = SERVER_SCHEDULERS.get(server);
        if (scheduler == null) {
            return new ResourceSnapshot(tick, configuredLimit, effectiveLimit, 0, 0, List.of());
        }
        return scheduler.snapshot(tick, configuredLimit, effectiveLimit);
    }

    public static boolean tryAcquire(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        if (serverLevel == null || playerNpc == null || serverLevel.getServer() == null) {
            return false;
        }

        long tick = serverLevel.getServer().getTickCount();
        SchedulerState scheduler = scheduler(serverLevel);
        boolean admitted = scheduler.tryAcquire(playerNpc, tick, resolveWorkerLimit(serverLevel.getServer()));
        if (admitted) {
            scheduler.clearDenied(playerNpc.getUUID());
        } else if (!scheduler.wasExpensiveAdmittedThisTick(playerNpc.getUUID(), tick)) {
            scheduler.markDenied(playerNpc.getUUID(), tick);
        }
        return admitted;
    }

    private static SchedulerState scheduler(ServerLevel serverLevel) {
        return SERVER_SCHEDULERS.computeIfAbsent(serverLevel.getServer(), ignored -> new SchedulerState());
    }

    private static int resolveWorkerLimit(MinecraftServer server) {
        int configured = SmartNpcConfig.AI_PROCESSING_NPC_LIMIT.get();
        if (configured >= 0) {
            return configured;
        }
        return server == null ? 1 : scheduler(server).resolveAutomaticWorkerLimit(server.getTickCount());
    }

    public static String automaticWorkerLimitStatus(MinecraftServer server) {
        if (SmartNpcConfig.AI_PROCESSING_NPC_LIMIT.get() >= 0 || server == null) {
            return "";
        }
        return scheduler(server).automaticWorkerLimitStatus();
    }

    private static SchedulerState scheduler(MinecraftServer server) {
        return SERVER_SCHEDULERS.computeIfAbsent(server, ignored -> new SchedulerState());
    }

    private static final class SchedulerState {
        private final Map<UUID, ActiveWorker> activeWorkers = new LinkedHashMap<>();
        private final Map<UUID, Request> waiting = new LinkedHashMap<>();
        private final Set<UUID> probeOwners = new LinkedHashSet<>();
        private final Map<UUID, Boolean> probeDecisions = new LinkedHashMap<>();
        private final Map<UUID, Integer> predicateSlices = new LinkedHashMap<>();
        private final Map<UUID, Long> predicateSliceTicks = new LinkedHashMap<>();
        private final Map<UUID, Long> deniedAtTick = new LinkedHashMap<>();
        private final Map<UUID, Long> expensiveRequestedAtTick = new LinkedHashMap<>();
        private final Map<UUID, Long> expensiveAdmittedAtTick = new LinkedHashMap<>();
        private final Deque<UUID> expensiveQueue = new ArrayDeque<>();
        private long schedulerTick = Long.MIN_VALUE;
        private long admissionTick = Long.MIN_VALUE;
        private long waitingStrollAdmissionTick = Long.MIN_VALUE;
        private int admissionsThisTick;
        private int automaticWorkerLimit = 1;
        private long lastAutomaticEvaluationTick = Long.MIN_VALUE;
        private int healthyWorkerEvaluations;
        private int overloadedWorkerEvaluations;
        private int automaticCapabilityLimit = 1;
        private int healthyWorkerEvaluationsRequired = AUTO_HEALTHY_GROWTH_CHECKS;
        private double automaticBaselineMspt;
        private String automaticWorkerReason = "warming_up";

        private int resolveAutomaticWorkerLimit(long tick) {
            int processors = Math.max(1, Runtime.getRuntime().availableProcessors());
            long maxHeapBytes = Math.max(1L, Runtime.getRuntime().maxMemory());
            int heapGiB = (int) Math.max(1L, maxHeapBytes / (1024L * 1024L * 1024L));
            // Logical workers still execute on the server thread, so this is an exploration
            // ceiling rather than claimed parallelism. Heap limits retained goal state/searches;
            // CPU count prevents a small host from advertising a large worker catalog.
            long cpuExplorationLimit = (long) processors * 2L;
            long heapExplorationLimit = (long) heapGiB * 3L;
            int capabilityLimit = (int) Math.max(1L, Math.min(12L,
                    Math.min(cpuExplorationLimit, heapExplorationLimit)));
            this.automaticCapabilityLimit = capabilityLimit;
            this.automaticWorkerLimit = Math.min(this.automaticWorkerLimit, capabilityLimit);
            if (!PlayerNpcPerformanceMonitor.hasStableRollingSample()) {
                this.automaticWorkerReason = "warming_up";
                return this.automaticWorkerLimit;
            }
            if (this.lastAutomaticEvaluationTick != Long.MIN_VALUE
                    && tick - this.lastAutomaticEvaluationTick < AUTO_EVALUATION_INTERVAL_TICKS) {
                return this.automaticWorkerLimit;
            }
            this.lastAutomaticEvaluationTick = tick;
            this.automaticBaselineMspt = PlayerNpcPerformanceMonitor.getRollingBaselineMspt();
            double configuredTarget = SmartNpcConfig.AI_TARGET_SERVER_MSPT.get();
            double healthyTarget = configuredTarget > 0.0D ? configuredTarget : AUTO_TARGET_MSPT;
            double cautionProbeMaxMspt = Math.min(AUTO_CAUTION_PROBE_MAX_MSPT, healthyTarget + 9.0D);
            double reductionMspt = Math.min(AUTO_REDUCTION_MSPT, healthyTarget + 12.0D);
            if (this.automaticBaselineMspt >= reductionMspt) {
                this.healthyWorkerEvaluations = 0;
                if (++this.overloadedWorkerEvaluations >= AUTO_OVERLOAD_REDUCTION_CHECKS) {
                    this.automaticWorkerLimit = Math.max(1, this.automaticWorkerLimit - 1);
                    this.overloadedWorkerEvaluations = 0;
                    this.automaticWorkerReason = "sustained_overload_reduced";
                } else {
                    this.automaticWorkerReason = "overload_pending";
                }
                return this.automaticWorkerLimit;
            }
            this.overloadedWorkerEvaluations = 0;
            if (this.automaticWorkerLimit >= capabilityLimit) {
                this.healthyWorkerEvaluations = 0;
                this.automaticWorkerReason = "capability_ceiling";
                return this.automaticWorkerLimit;
            }
            if (this.activeWorkers.size() < this.automaticWorkerLimit || this.waiting.isEmpty()) {
                this.healthyWorkerEvaluations = 0;
                this.automaticWorkerReason = this.activeWorkers.size() < this.automaticWorkerLimit
                        ? "awaiting_worker_occupancy"
                        : "awaiting_queued_demand";
                return this.automaticWorkerLimit;
            }
            int requiredChecks;
            if (this.automaticBaselineMspt <= healthyTarget) {
                requiredChecks = this.automaticWorkerLimit < 3
                        ? AUTO_HEALTHY_GROWTH_CHECKS
                        : AUTO_HIGH_HEALTHY_GROWTH_CHECKS;
                this.automaticWorkerReason = "healthy_growth_pending";
            } else if (this.automaticBaselineMspt <= cautionProbeMaxMspt) {
                requiredChecks = this.automaticWorkerLimit < 3
                        ? AUTO_CAUTION_GROWTH_CHECKS
                        : AUTO_HIGH_CAUTION_GROWTH_CHECKS;
                this.automaticWorkerReason = "cautious_growth_pending";
            } else {
                this.healthyWorkerEvaluations = 0;
                this.automaticWorkerReason = "hysteresis_hold";
                return this.automaticWorkerLimit;
            }
            this.healthyWorkerEvaluationsRequired = requiredChecks;
            if (++this.healthyWorkerEvaluations >= requiredChecks) {
                this.automaticWorkerLimit++;
                this.healthyWorkerEvaluations = 0;
                this.automaticWorkerReason = "stable_headroom_growth";
            }
            return this.automaticWorkerLimit;
        }

        private String automaticWorkerLimitStatus() {
            return "limit " + this.automaticWorkerLimit + " | exploration max " + this.automaticCapabilityLimit
                    + " | baseline " + String.format(java.util.Locale.ROOT, "%.1f", this.automaticBaselineMspt)
                    + "ms | " + this.automaticWorkerReason.replace('_', ' ')
                    + " | growth " + this.healthyWorkerEvaluations + "/" + this.healthyWorkerEvaluationsRequired
                    + " | overload " + this.overloadedWorkerEvaluations + "/" + AUTO_OVERLOAD_REDUCTION_CHECKS;
        }

        private boolean tryAcquireWaitingStrollPathStart(long tick) {
            if (this.waitingStrollAdmissionTick == tick) {
                return false;
            }
            this.waitingStrollAdmissionTick = tick;
            return true;
        }

        private boolean canProbe(PlayerNpcEntity playerNpc, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            UUID id = playerNpc.getUUID();
            ActiveWorker activeWorker = this.activeWorkers.get(id);
            if (activeWorker != null) {
                activeWorker.lastRequestTick = tick;
                this.clearDenied(id);
                return true;
            }
            if (this.probeOwners.contains(id)) {
                this.clearDenied(id);
                return true;
            }

            Boolean cachedDecision = this.probeDecisions.get(id);
            if (cachedDecision != null) {
                return cachedDecision;
            }

            this.updateWaiting(playerNpc, tick);
            int availableProbes = Math.max(0, workerLimit - this.activeWorkers.size());
            if (this.probeOwners.size() >= availableProbes || this.waiting.isEmpty()) {
                this.markDenied(id, tick);
                this.probeDecisions.put(id, false);
                return false;
            }

            UUID nextId = this.waiting.keySet().iterator().next();
            if (!id.equals(nextId)) {
                this.markDenied(id, tick);
                this.probeDecisions.put(id, false);
                return false;
            }
            this.waiting.remove(id);
            this.probeOwners.add(id);
            this.probeDecisions.put(id, true);
            this.clearDenied(id);
            return true;
        }

        private boolean canEvaluatePredicateSlice(UUID id, long tick, int requestedSlice, int sliceCount) {
            int boundedSliceCount = Math.max(1, sliceCount);
            if (this.predicateSliceTicks.getOrDefault(id, Long.MIN_VALUE) != tick) {
                int nextSlice = Math.floorMod(this.predicateSlices.getOrDefault(id, -1) + 1, boundedSliceCount);
                this.predicateSlices.put(id, nextSlice);
                this.predicateSliceTicks.put(id, tick);
            }
            return this.predicateSlices.getOrDefault(id, 0) == Math.floorMod(requestedSlice, boundedSliceCount);
        }

        private boolean canContinue(PlayerNpcEntity playerNpc, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            UUID id = playerNpc.getUUID();
            ActiveWorker worker = this.activeWorkers.get(id);
            if (worker == null || workerLimit <= 0 || this.workerIndex(id) >= workerLimit) {
                return false;
            }
            if (!this.waiting.isEmpty() && tick - worker.startedTick >= MAX_ACTIVE_WORK_TICKS) {
                return false;
            }
            worker.lastRequestTick = tick;
            this.clearDenied(id);
            return true;
        }

        private void workStarted(PlayerNpcEntity playerNpc, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            UUID id = playerNpc.getUUID();
            ActiveWorker worker = this.activeWorkers.get(id);
            if (worker == null) {
                this.probeOwners.remove(id);
                this.waiting.remove(id);
                worker = new ActiveWorker(playerNpc, tick);
                this.activeWorkers.put(id, worker);
            } else {
                worker.playerNpc = playerNpc;
                worker.lastRequestTick = tick;
            }
            worker.runningGoals++;
            this.clearDenied(id);
        }

        private void workStopped(PlayerNpcEntity playerNpc, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            UUID id = playerNpc.getUUID();
            ActiveWorker worker = this.activeWorkers.get(id);
            if (worker == null) {
                return;
            }
            worker.runningGoals = Math.max(0, worker.runningGoals - 1);
            if (worker.runningGoals > 0) {
                return;
            }
            this.activeWorkers.remove(id);
            this.removeExpensiveRequest(id);
            this.updateWaiting(playerNpc, tick);
        }

        private boolean tryAcquire(PlayerNpcEntity playerNpc, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            UUID id = playerNpc.getUUID();
            if (!this.isScheduled(id)) {
                this.updateWaiting(playerNpc, tick);
                return false;
            }
            if (this.admissionTick != tick) {
                this.admissionTick = tick;
                this.admissionsThisTick = 0;
            }

            this.pruneExpensiveQueue(tick);
            if (this.expensiveAdmittedAtTick.getOrDefault(id, Long.MIN_VALUE) == tick) {
                return false;
            }
            if (!this.expensiveQueue.contains(id)) {
                this.expensiveQueue.addLast(id);
            }
            this.expensiveRequestedAtTick.put(id, tick);
            if (this.admissionsThisTick >= MAX_EXPENSIVE_BATCHES_PER_TICK
                    || !id.equals(this.expensiveQueue.peekFirst())) {
                return false;
            }

            this.expensiveQueue.removeFirst();
            this.expensiveRequestedAtTick.remove(id);
            this.admissionsThisTick++;
            this.expensiveAdmittedAtTick.put(id, tick);
            return true;
        }

        private boolean wasExpensiveAdmittedThisTick(UUID id, long tick) {
            return this.expensiveAdmittedAtTick.getOrDefault(id, Long.MIN_VALUE) == tick;
        }

        private boolean isWaiting(UUID id, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            Long deniedTick = this.deniedAtTick.get(id);
            return !this.isScheduled(id)
                    && deniedTick != null
                    && tick - deniedTick <= DENIAL_VISIBLE_TICKS;
        }

        private boolean hasResource(UUID id, long tick, int workerLimit) {
            this.beginTick(tick, workerLimit);
            return this.isScheduled(id)
                    || this.expensiveAdmittedAtTick.getOrDefault(id, Long.MIN_VALUE) == tick;
        }

        private ResourceSnapshot snapshot(long tick, int configuredLimit, int workerLimit) {
            this.beginTick(tick, workerLimit);
            Set<UUID> holderIds = new LinkedHashSet<>();
            holderIds.addAll(this.activeWorkers.keySet());
            holderIds.addAll(this.probeOwners);
            for (Map.Entry<UUID, Long> entry : this.expensiveAdmittedAtTick.entrySet()) {
                if (entry.getValue() == tick) {
                    holderIds.add(entry.getKey());
                }
            }

            List<ResourceHolder> holders = new ArrayList<>(holderIds.size());
            for (UUID id : holderIds) {
                ActiveWorker worker = this.activeWorkers.get(id);
                holders.add(new ResourceHolder(
                        id,
                        worker == null ? null : worker.playerNpc,
                        worker != null,
                        this.probeOwners.contains(id),
                        this.expensiveAdmittedAtTick.getOrDefault(id, Long.MIN_VALUE) == tick,
                        worker == null ? 0 : worker.runningGoals,
                        worker == null ? 0L : Math.max(1L, tick - worker.startedTick + 1L)
                ));
            }
            return new ResourceSnapshot(
                    tick,
                    configuredLimit,
                    workerLimit,
                    this.activeWorkers.size(),
                    this.waiting.size(),
                    List.copyOf(holders)
            );
        }

        private void beginTick(long tick, int workerLimit) {
            if (this.schedulerTick != tick) {
                this.schedulerTick = tick;
                this.probeOwners.clear();
                this.probeDecisions.clear();
                this.prune(tick);
                if (workerLimit <= 0) {
                    this.probeOwners.clear();
                }
            }
        }

        private void updateWaiting(PlayerNpcEntity playerNpc, long tick) {
            UUID id = playerNpc.getUUID();
            if (this.activeWorkers.containsKey(id) || this.probeOwners.contains(id)) {
                return;
            }
            Request request = this.waiting.get(id);
            if (request == null) {
                this.waiting.put(id, new Request(playerNpc, tick));
            } else {
                request.playerNpc = playerNpc;
                request.lastRequestTick = tick;
            }
        }

        private int workerIndex(UUID id) {
            int index = 0;
            for (UUID workerId : this.activeWorkers.keySet()) {
                if (workerId.equals(id)) {
                    return index;
                }
                index++;
            }
            return Integer.MAX_VALUE;
        }

        private boolean isScheduled(UUID id) {
            return this.activeWorkers.containsKey(id) || this.probeOwners.contains(id);
        }

        private void prune(long tick) {
            this.waiting.entrySet().removeIf(entry -> !isUsable(entry.getValue().playerNpc)
                    || tick - entry.getValue().lastRequestTick > PROBE_REQUEST_STALE_TICKS);
            this.activeWorkers.entrySet().removeIf(entry -> !isUsable(entry.getValue().playerNpc));
            this.deniedAtTick.entrySet().removeIf(entry -> tick - entry.getValue() > DENIAL_VISIBLE_TICKS);
            this.expensiveAdmittedAtTick.entrySet().removeIf(entry -> tick - entry.getValue() > 1);
            this.predicateSliceTicks.entrySet().removeIf(entry -> tick - entry.getValue() > DENIAL_VISIBLE_TICKS
                    && !this.activeWorkers.containsKey(entry.getKey())
                    && !this.waiting.containsKey(entry.getKey()));
            this.predicateSlices.keySet().removeIf(id -> !this.predicateSliceTicks.containsKey(id));
            this.pruneExpensiveQueue(tick);
        }

        private void pruneExpensiveQueue(long tick) {
            this.expensiveQueue.removeIf(id -> !this.isScheduled(id)
                    || !this.expensiveRequestedAtTick.containsKey(id)
                    || tick - this.expensiveRequestedAtTick.get(id) > EXPENSIVE_REQUEST_STALE_TICKS);
            this.expensiveRequestedAtTick.keySet().removeIf(id -> !this.expensiveQueue.contains(id));
        }

        private void removeExpensiveRequest(UUID id) {
            this.expensiveQueue.remove(id);
            this.expensiveRequestedAtTick.remove(id);
        }

        private void markDenied(UUID id, long tick) {
            this.deniedAtTick.put(id, tick);
        }

        private void clearDenied(UUID id) {
            this.deniedAtTick.remove(id);
        }

        private static boolean isUsable(PlayerNpcEntity playerNpc) {
            return playerNpc != null && playerNpc.isAlive() && !playerNpc.isRemoved();
        }
    }

    private static final class ActiveWorker {
        private PlayerNpcEntity playerNpc;
        private final long startedTick;
        private long lastRequestTick;
        private int runningGoals;

        private ActiveWorker(PlayerNpcEntity playerNpc, long tick) {
            this.playerNpc = playerNpc;
            this.startedTick = tick;
            this.lastRequestTick = tick;
        }
    }

    private static final class Request {
        private PlayerNpcEntity playerNpc;
        private long lastRequestTick;

        private Request(PlayerNpcEntity playerNpc, long tick) {
            this.playerNpc = playerNpc;
            this.lastRequestTick = tick;
        }
    }

    public record ResourceSnapshot(
            long serverTick,
            int configuredWorkerLimit,
            int effectiveWorkerLimit,
            int activeWorkerCount,
            int waitingNpcCount,
            List<ResourceHolder> holders
    ) {
        public boolean automatic() {
            return this.configuredWorkerLimit < 0;
        }

        public long probeCount() {
            return this.holders.stream().filter(ResourceHolder::probeTurn).count();
        }

        public long expensiveCount() {
            return this.holders.stream().filter(ResourceHolder::expensiveSlice).count();
        }
    }

    public record ResourceHolder(
            UUID npcId,
            PlayerNpcEntity playerNpc,
            boolean worker,
            boolean probeTurn,
            boolean expensiveSlice,
            int runningGoals,
            long heldTicks
    ) {
    }
}
