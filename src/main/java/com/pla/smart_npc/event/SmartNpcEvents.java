package com.pla.smart_npc.event;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.SmartNpcNamesConfig;
import com.pla.smart_npc.task.DelayedTask;
import com.pla.smart_npc.util.EquipmentDataLoader;
import com.pla.smart_npc.util.PlayerNpcBuildLayoutLoader;
import com.pla.smart_npc.util.PlayerNpcChatTemplateLoader;
import com.pla.smart_npc.util.PlayerNpcForceTickManager;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import com.pla.smart_npc.util.PlayerNpcNaturalSpawnCap;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Central Fabric event wiring — the replacement for Forge's event bus
 * ({@code @Mod.EventBusSubscriber} / {@code MinecraftForge.EVENT_BUS}).
 *
 * <p>Events Fabric does not offer (living tick, player dimension change,
 * keyboard input mutation, screen-open veto, jump-bar overlay veto) are
 * delivered through the mixins in {@code com.pla.smart_npc.mixin} which call
 * back into the static dispatchers at the bottom of this class.</p>
 */
public final class SmartNpcEvents {
    private SmartNpcEvents() {
    }

    public static void register() {
        // Forge 服务器生命周期钩子等价物: 记录当前服务器供 ServerLifecycleHooks 兼容层使用
        ServerLifecycleEvents.SERVER_STARTING.register(com.pla.smart_npc.util.compat.FabricServerHolder::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(com.pla.smart_npc.util.compat.FabricServerHolder::onServerStopped);
        // 生物群系生成 (Forge: BiomeModifier 数据包)
        com.pla.smart_npc.world.PlayerNpcWorldSpawns.registerBiomeSpawns();

        registerServerLifecycle();
        registerServerTicks();
        registerPlayerLifecycle();
        registerEntityLifecycle();
        registerCombatAndChat();
        registerInteraction();
        registerCommandsAndReloads();
    }

    // ------------------------------------------------------------------ server lifecycle

    private static void registerServerLifecycle() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SmartNpcNamesConfig.reload(); // Forge: ModConfigEvent loading/reloading equivalent for world starts
            ProgressionEvent.onServerStarted(server);
            PlayerNpcForceTickManager.onServerStarted(server);
            PlayerNpcNaturalSpawnCap.onServerStarted(server);
            PlayerNpcGoalTraceLogger.onServerStarted(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            PlayerNpcDepartureEvent.stopping(server);
            PlayerNpcForceTickManager.onServerStopping(server);
            PlayerNpcNaturalSpawnCap.onServerStopping(server);
            PlayerNpcGoalTraceLogger.onServerStopping(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PlayerNpcPerformanceMonitor.onServerStopped(server);
            DelayedTask.onServerStopped(server);
        });
    }

    // ------------------------------------------------------------------ ticks

    private static void registerServerTicks() {
        ServerTickEvents.START_SERVER_TICK.register(PlayerNpcPerformanceMonitor::onServerTickStart);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            DelayedTask.onServerTick(server);
            PlayerNpcForceTickManager.onServerTick(server);
            PlayerNpcNaturalSpawnCap.onServerTick(server);
            PlayerNpcPerformanceMonitor.onServerTickEnd(server);
            PlayerNpcGoalTraceLogger.onServerTick(server);
            ProgressionEvent.onServerTick(server);
            PlayerNpcInspectatorEvent.onServerTick(server);
        });
    }

    // ------------------------------------------------------------------ players

    private static void registerPlayerLifecycle() {
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) ->
                PlayerNpcInspectatorEvent.onPlayerClone(oldPlayer, newPlayer));
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                PlayerNpcInspectatorEvent.onPlayerRespawn(newPlayer));
        ServerPlayerEvents.ALLOW_DEATH.register((player, damageSource, damageAmount) -> {
            // Forge PlayerEvent.PlayerLoggedInEvent etc. equivalents live below; ALLOW_DEATH is
            // unused but kept registered for parity of behaviour with Forge's death flow.
            return true;
        });
        // Join/leave (Forge: PlayerLoggedInEvent / PlayerLoggedOutEvent)
        registerJoinLeave();
        // Forge: PlayerEvent.PlayerChangedDimensionEvent via mixin on ServerPlayer#changeDimension
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) ->
                ProgressionEvent.onPlayerChangedDimension(player));
    }

    private static void registerJoinLeave() {
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer player = handler.player;
            ProgressionEvent.onPlayerLoggedIn(player);
            PlayerNpcInspectatorEvent.onPlayerLoggedIn(player);
            PlayerNpcForceTickManager.onPlayerLoggedIn(player);
            PlayerNpcPerformanceMonitor.onPlayerLoggedIn(player);
            PlayerNpcGoalTraceLogger.onPlayerLoggedIn(player);
        });
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer player = handler.player;
            PlayerNpcInspectatorEvent.onPlayerLoggedOut(player);
            PlayerNpcGoalTraceLogger.onPlayerLoggedOut(player);
        });
    }

    // ------------------------------------------------------------------ entities

    private static void registerEntityLifecycle() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, serverLevel) -> {
            PlayerNpcForceTickManager.onEntityJoinLevel(entity, serverLevel);
            PlayerNpcNaturalSpawnCap.onEntityJoinLevel(entity, serverLevel);
            PlayerNpcPerformanceMonitor.onEntityJoinLevel(entity, serverLevel);
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, serverLevel) -> {
            PlayerNpcForceTickManager.onEntityLeaveLevel(entity, serverLevel);
            PlayerNpcNaturalSpawnCap.onEntityLeaveLevel(entity, serverLevel);
            PlayerNpcInspectatorEvent.onEntityLeaveLevel(entity, serverLevel);
        });
    }

    // ------------------------------------------------------------------ combat + chat

    private static void registerCombatAndChat() {
        // Forge LivingAttackEvent (LOWEST priority, cancellable)
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!PlayerNpcTeamUpEvent.onLivingAttack(entity, source, amount)) {
                return false; // friendly fire cancelled
            }
            return true;
        });
        // Forge LivingDeathEvent
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            PlayerNpcTeamUpEvent.onLivingDeath(entity, source);
            ProgressionEvent.onLivingDeath(entity, source);
            PlayerNpcInspectatorEvent.onLivingDeath(entity, source);
            PlayerNpcForceTickManager.onLivingDeath(entity, source);
            PlayerNpcNaturalSpawnCap.onLivingDeath(entity, source);
        });
        // Forge ServerChatEvent
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, parameters) ->
                PlayerNpcTeamUpEvent.onServerChat(sender, message.signedContent()));
    }

    // ------------------------------------------------------------------ interaction

    private static void registerInteraction() {
        // Forge BlockEvent.BreakEvent
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (!PlayerNpcHomeEvent.onBlockBreak(level, player, pos, state)) {
                return false;
            }
            return PlayerNpcChestProtectEvent.onBlockBreak(level, player, pos, state);
        });
        // Forge PlayerInteractEvent.RightClickBlock (LOWEST priority)
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            InteractionResult forgeResult = PlayerNpcChestProtectEvent.onRightClickBlock(player, level, hand, hitResult);
            if (forgeResult == InteractionResult.FAIL) {
                return InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        });
    }

    // ------------------------------------------------------------------ commands + data reloads

    private static void registerCommandsAndReloads() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                PlayerNpcCommandEvent.registerCommands(dispatcher));

        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new EquipmentDataLoader());
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new PlayerNpcBuildLayoutLoader());
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new PlayerNpcChatTemplateLoader());
    }

    // ------------------------------------------------------------------ mixin dispatch hooks

    /** Called from {@code LivingEntityTickMixin} at the tail of every living tick. */
    public static void onLivingTick(LivingEntity entity) {
        if (entity.level().isClientSide()) {
            return;
        }
        PlayerNpcDepartureEvent.onLivingTick(entity);
        ProgressionEvent.onLivingTick(entity);
    }
}
