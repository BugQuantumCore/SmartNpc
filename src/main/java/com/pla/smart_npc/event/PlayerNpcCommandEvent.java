package com.pla.smart_npc.event;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.config.SmartNpcConfig;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.init.SmartNpcModEntities;
import com.pla.smart_npc.clazz.Difficulty;
import com.pla.smart_npc.util.PlayerNpcForceTickManager;
import com.pla.smart_npc.util.PlayerNpcGoalTraceLogger;
import com.pla.smart_npc.util.ProgressionUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SmartNpc.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlayerNpcCommandEvent {
    private PlayerNpcCommandEvent() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("smart_npc")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spawn_player")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> spawnPlayer(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")
                                ))))
                .then(Commands.literal("tp")
                        .requires(source -> SmartNpcConfig.isForceTickManageEnabled())
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((context, builder) -> PlayerNpcForceTickManager.suggestNpcNames(
                                        context.getSource().getServer(),
                                        builder
                                ))
                                .executes(context -> teleportToPlayerNpc(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")
                                ))))
                .then(Commands.literal("difficulty")
                        .then(Commands.literal("get")
                                .executes(context -> getDifficulty(context.getSource())))
                        .then(Commands.literal("set")
                                .then(Commands.argument("difficulty", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(new String[]{"easy", "medium", "hard"}, builder))
                                        .executes(context -> setDifficulty(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "difficulty")
                                        )))))
                .then(Commands.literal("trace")
                        .then(Commands.literal("all")
                                .then(Commands.literal("on")
                                        .executes(context -> setTraceAll(context.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(context -> setTraceAll(context.getSource(), false)))
                                .then(Commands.literal("status")
                                        .executes(context -> getTraceAllStatus(context.getSource()))))));
    }

    private static int spawnPlayer(CommandSourceStack source, String name) {
        ServerLevel level = source.getLevel();
        PlayerNpcEntity entity = SmartNpcModEntities.PLAYER_NPC.get().create(level);
        if (entity == null) {
            source.sendFailure(Component.literal("Failed to create player NPC"));
            return 0;
        }

        Vec3 position = source.getPosition();
        Vec2 rotation = source.getRotation();
        entity.moveTo(position.x, position.y, position.z, rotation.y, rotation.x);
        entity.setUsername(name);
        DifficultyInstance difficulty = level.getCurrentDifficultyAt(entity.blockPosition());
        entity.finalizeSpawn(level, difficulty, MobSpawnType.COMMAND, null, null);
        level.addFreshEntity(entity);
        source.sendSuccess(() -> Component.literal("Spawned player NPC " + entity.getName().getString()), true);
        return 1;
    }

    private static int teleportToPlayerNpc(CommandSourceStack source, String name) throws CommandSyntaxException {
        if (!SmartNpcConfig.isForceTickManageEnabled()) {
            source.sendFailure(Component.literal("Player NPC force-tick management is disabled"));
            return 0;
        }

        ServerPlayer player = source.getPlayerOrException();
        PlayerNpcEntity npc = PlayerNpcForceTickManager.chooseRandomByName(source.getServer(), name).orElse(null);
        if (npc == null || !(npc.level() instanceof ServerLevel targetLevel)) {
            source.sendFailure(Component.literal("No tracked player NPC named " + name));
            return 0;
        }

        player.teleportTo(targetLevel, npc.getX(), npc.getY(), npc.getZ(), npc.getYRot(), npc.getXRot());
        source.sendSuccess(() -> Component.literal("Teleported to player NPC " + npc.getName().getString()), true);
        return 1;
    }

    private static int getDifficulty(CommandSourceStack source) {
        Difficulty difficulty = ProgressionUtil.getDifficulty(source.getServer());
        source.sendSuccess(() -> Component.literal("Current Annoying Villagers difficulty is " + difficulty.id()), false);
        return 1;
    }

    private static int setDifficulty(CommandSourceStack source, String name) {
        Difficulty difficulty = Difficulty.findByName(name);
        if (difficulty == null) {
            source.sendFailure(Component.literal("Unknown Annoying Villagers difficulty: " + name));
            return 0;
        }

        boolean changed = ProgressionUtil.setDifficulty(source.getServer(), difficulty);
        source.sendSuccess(() -> Component.literal("Annoying Villagers difficulty "
                + (changed ? "changed to " : "is already ")
                + difficulty.id()), true);
        return changed ? 1 : 0;
    }

    private static int setTraceAll(CommandSourceStack source, boolean enabled) {
        PlayerNpcGoalTraceLogger.setAllTraceEnabled(enabled, sourceName(source));
        int loadedCount = PlayerNpcGoalTraceLogger.countLoadedPlayerNpcs(source.getServer());
        source.sendSuccess(() -> Component.literal("Player NPC all trace "
                + (enabled ? "enabled" : "disabled")
                + " for "
                + loadedCount
                + " loaded NPC(s)"), true);
        return 1;
    }

    private static int getTraceAllStatus(CommandSourceStack source) {
        int loadedCount = PlayerNpcGoalTraceLogger.countLoadedPlayerNpcs(source.getServer());
        source.sendSuccess(() -> Component.literal("Player NPC all trace is "
                + (PlayerNpcGoalTraceLogger.isAllTraceEnabled() ? "enabled" : "disabled")
                + " with "
                + loadedCount
                + " loaded NPC(s)"), false);
        return 1;
    }

    private static String sourceName(CommandSourceStack source) {
        return source.getDisplayName().getString();
    }
}
