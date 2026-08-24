package com.pla.smart_npc.compat;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class EpicFightCompat {
    private static final String MOD_ID = "epicfight";
    private static final String HOOKS_CLASS = "com.pla.smart_npc.compat.epicfight.EpicFight";
    private static final String ANIMATIONS_CLASS = "com.pla.smart_npc.compat.epicfight.EpicFightCloneAnimations";
    private static final String PATCHES_CLASS = "com.pla.smart_npc.compat.epicfight.EpicFightSmartNpcPatches";
    private static final String RENDERER_CLASS = "com.pla.smart_npc.compat.epicfight.EpicFightSmartNpcPatchedRenderer";

    private static Class<?> hooksClass;
    private static Method registerArmaturesMethod;
    private static Method keepDiggingStateMethod;
    private static Method playDiggingAnimationMethod;
    private static Method playMainHandUseAnimationMethod;
    private static Method stopDiggingAnimationMethod;
    private static Method keepSleepingStateMethod;
    private static Method playSleepingAnimationMethod;
    private static Method stopSleepingAnimationMethod;
    private static boolean handlersRegistered;
    private static boolean hooksDisabled;
    private static boolean warnedHandlerFailure;

    private EpicFightCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    public static void registerModEventHandlers(IEventBus modEventBus) {
        if (!isLoaded() || handlersRegistered) {
            return;
        }

        registerEventHandler(modEventBus, ANIMATIONS_CLASS);
        registerEventHandler(modEventBus, PATCHES_CLASS);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            registerEventHandler(modEventBus, RENDERER_CLASS);
        }
        handlersRegistered = true;
    }

    public static void enqueueRegisterArmatures(FMLCommonSetupEvent event) {
        if (!isLoaded()) {
            return;
        }

        event.enqueueWork(EpicFightCompat::registerArmatures);
    }

    public static void keepDiggingState(PlayerNpcEntity playerNpc) {
        invokeEntityHook("keep digging state", playerNpc, EpicFightCompat::keepDiggingStateMethod);
    }

    public static void playDiggingAnimation(PlayerNpcEntity playerNpc) {
        invokeEntityHook("play digging animation", playerNpc, EpicFightCompat::playDiggingAnimationMethod);
    }

    public static boolean playMainHandUseAnimation(PlayerNpcEntity playerNpc) {
        return invokeBooleanEntityHook("play main-hand use animation", playerNpc, EpicFightCompat::playMainHandUseAnimationMethod);
    }

    public static void stopDiggingAnimation(PlayerNpcEntity playerNpc) {
        invokeEntityHook("stop digging animation", playerNpc, EpicFightCompat::stopDiggingAnimationMethod);
    }

    public static void keepSleepingState(PlayerNpcEntity playerNpc) {
        invokeEntityHook("keep sleeping state", playerNpc, EpicFightCompat::keepSleepingStateMethod);
    }

    public static void playSleepingAnimation(PlayerNpcEntity playerNpc) {
        invokeEntityHook("play sleeping animation", playerNpc, EpicFightCompat::playSleepingAnimationMethod);
    }

    public static void stopSleepingAnimation(PlayerNpcEntity playerNpc) {
        invokeEntityHook("stop sleeping animation", playerNpc, EpicFightCompat::stopSleepingAnimationMethod);
    }

    private static void registerArmatures() {
        if (hooksDisabled) {
            return;
        }

        try {
            registerArmaturesMethod().invoke(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            disableHooks("register armatures", exception);
        }
    }

    private static void invokeEntityHook(String action, PlayerNpcEntity playerNpc, MethodLookup methodLookup) {
        if (!isLoaded() || hooksDisabled) {
            return;
        }

        try {
            methodLookup.method().invoke(null, playerNpc);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            disableHooks(action, exception);
        }
    }

    private static boolean invokeBooleanEntityHook(String action, PlayerNpcEntity playerNpc, MethodLookup methodLookup) {
        if (!isLoaded() || hooksDisabled) {
            return false;
        }

        try {
            return Boolean.TRUE.equals(methodLookup.method().invoke(null, playerNpc));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            disableHooks(action, exception);
            return false;
        }
    }

    private static Method registerArmaturesMethod() throws ClassNotFoundException, NoSuchMethodException {
        if (registerArmaturesMethod == null) {
            registerArmaturesMethod = hooksClass().getMethod("registerArmatures");
        }
        return registerArmaturesMethod;
    }

    private static Method keepDiggingStateMethod() throws ClassNotFoundException, NoSuchMethodException {
        if (keepDiggingStateMethod == null) {
            keepDiggingStateMethod = entityHookMethod("keepDiggingState");
        }
        return keepDiggingStateMethod;
    }

    private static Method playDiggingAnimationMethod() throws ClassNotFoundException, NoSuchMethodException {
        if (playDiggingAnimationMethod == null) {
            playDiggingAnimationMethod = entityHookMethod("playDiggingAnimation");
        }
        return playDiggingAnimationMethod;
    }

    private static Method playMainHandUseAnimationMethod() throws ClassNotFoundException, NoSuchMethodException {
        if (playMainHandUseAnimationMethod == null) {
            playMainHandUseAnimationMethod = entityHookMethod("playMainHandUseAnimation");
        }
        return playMainHandUseAnimationMethod;
    }

    private static Method stopDiggingAnimationMethod() throws ClassNotFoundException, NoSuchMethodException {
        if (stopDiggingAnimationMethod == null) {
            stopDiggingAnimationMethod = entityHookMethod("stopDiggingAnimation");
        }
        return stopDiggingAnimationMethod;
    }

    private static Method keepSleepingStateMethod() throws ClassNotFoundException, NoSuchMethodException {
        if (keepSleepingStateMethod == null) {
            keepSleepingStateMethod = entityHookMethod("keepSleepingState");
        }
        return keepSleepingStateMethod;
    }

    private static Method playSleepingAnimationMethod() throws ClassNotFoundException, NoSuchMethodException {
        if (playSleepingAnimationMethod == null) {
            playSleepingAnimationMethod = entityHookMethod("playSleepingAnimation");
        }
        return playSleepingAnimationMethod;
    }

    private static Method stopSleepingAnimationMethod() throws ClassNotFoundException, NoSuchMethodException {
        if (stopSleepingAnimationMethod == null) {
            stopSleepingAnimationMethod = entityHookMethod("stopSleepingAnimation");
        }
        return stopSleepingAnimationMethod;
    }

    private static Method entityHookMethod(String methodName) throws ClassNotFoundException, NoSuchMethodException {
        return hooksClass().getMethod(methodName, PlayerNpcEntity.class);
    }

    private static Class<?> hooksClass() throws ClassNotFoundException {
        if (hooksClass == null) {
            hooksClass = Class.forName(HOOKS_CLASS);
        }
        return hooksClass;
    }

    private static void registerEventHandler(IEventBus modEventBus, String className) {
        try {
            modEventBus.register(Class.forName(className));
        } catch (ClassNotFoundException | LinkageError | RuntimeException exception) {
            warnHandlerFailure(className, exception);
        }
    }

    private static void warnHandlerFailure(String className, Throwable exception) {
        if (!warnedHandlerFailure) {
            warnedHandlerFailure = true;
            SmartNpc.LOGGER.warn("Smart NPC could not register Epic Fight compat handler {}.", className, unwrap(exception));
        }
    }

    private static void disableHooks(String action, Throwable exception) {
        hooksDisabled = true;
        SmartNpc.LOGGER.warn("Smart NPC Epic Fight compat could not {}; disabling Epic Fight hooks.", action, unwrap(exception));
    }

    private static Throwable unwrap(Throwable exception) {
        if (exception instanceof InvocationTargetException invocationException && invocationException.getCause() != null) {
            return invocationException.getCause();
        }
        return exception;
    }

    @FunctionalInterface
    private interface MethodLookup {
        Method method() throws ClassNotFoundException, NoSuchMethodException;
    }
}
