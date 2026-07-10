package com.pla.smart_npc.client.gui;

import com.pla.smart_npc.SmartNpc;
import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.network.PlayerNpcGoalTracePacket;
import com.pla.smart_npc.network.PlayerNpcInspectatorModePacket;
import com.pla.smart_npc.network.PlayerNpcInspectorPacket;
import com.pla.smart_npc.network.PlayerNpcInspectorRequestPacket;
import com.pla.smart_npc.network.SmartNpcNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Mod.EventBusSubscriber(modid = SmartNpc.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class SmartNpcInspectorOverlay {
    private static final int PANEL_WIDTH = 196;
    private static final int PANEL_HEIGHT = 294;
    private static final int SLOT_SIZE = 18;
    private static final int TASK_DETAIL_MAX_LINES = 3;
    private static final int REFRESH_INTERVAL_TICKS = 10;
    private static final long DISPLAY_CACHE_INTERVAL_MS = 500L;
    private static final double INSPECTATOR_SWITCH_RADIUS = 64.0D;
    private static final double INSPECTATOR_CAMERA_DISTANCE_DEFAULT = 4.0D;
    private static final double INSPECTATOR_CAMERA_DISTANCE_MIN = 1.5D;
    private static final double INSPECTATOR_CAMERA_DISTANCE_MAX = 12.0D;
    private static final double INSPECTATOR_CAMERA_DISTANCE_STEP = 0.5D;
    private static final int INSPECTATOR_ZOOM_REPEAT_TICKS = 4;
    private static int inspectedEntityId = -1;
    private static List<ItemStack> snapshot = List.of();
    private static String snapshotBuildStatusText = "";
    private static String snapshotPerformanceText = "";
    private static boolean snapshotTraceEnabled;
    private static long lastRefreshGameTime = Long.MIN_VALUE;
    private static long lastDisplayCacheMillis = Long.MIN_VALUE;
    private static boolean inspectatorActive;
    private static int inspectatorEntityId = -1;
    private static double inspectatorCameraDistance = INSPECTATOR_CAMERA_DISTANCE_DEFAULT;
    private static CameraType previousCameraType;
    private static Entity previousCameraEntity;
    private static int inspectatorZoomRepeatTicks;
    private static boolean previousInspectatorToggleDown;
    private static boolean previousCycleLeftDown;
    private static boolean previousCycleRightDown;
    private static boolean previousTraceToggleDown;
    private static Component cachedTitle = Component.empty();
    private static Component cachedHealthText = Component.empty();
    private static int cachedHealthColor = 0xFF74E291;
    private static Component cachedAiText = Component.empty();
    private static String cachedInterestsText = "";
    private static String cachedBuildStatusText = "";
    private static String cachedPerformanceText = "";
    private static String cachedTraceText = "";
    private static Component cachedTaskLabel = Component.empty();
    private static int cachedTaskLabelWidth = 0;
    private static List<String> cachedTaskValueLines = List.of("");
    private static Component cachedMainHandText = Component.empty();
    private static String cachedInspectatorHint = "";

    public static void handlePacket(PlayerNpcInspectorPacket packet) {
        if (packet.entityId() < 0) {
            disableTraceIfNeeded();
            stopInspectator(Minecraft.getInstance(), true);
        }
        inspectedEntityId = packet.entityId();
        snapshot = packet.items();
        snapshotBuildStatusText = packet.buildStatusText();
        snapshotPerformanceText = packet.performanceText();
        snapshotTraceEnabled = packet.traceEnabled();
        lastDisplayCacheMillis = Long.MIN_VALUE;
    }

    public static boolean isInspectatorActive() {
        return inspectatorActive;
    }

    public static double getInspectatorCameraDistance(double vanillaDistance) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!inspectatorActive || minecraft.options.getCameraType() == CameraType.FIRST_PERSON) {
            return vanillaDistance;
        }
        return inspectatorCameraDistance;
    }

    public static boolean shouldHideInspectatorLocalPlayer(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        return inspectatorActive
                && minecraft.player != null
                && entity == minecraft.player;
    }

    public static boolean shouldRenderInspectatorCameraTargetBody(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        return inspectatorActive
                && minecraft.options.getCameraType() == CameraType.FIRST_PERSON
                && entity != null
                && entity.getId() == inspectatorEntityId;
    }

    public static boolean shouldForceInspectatorTargetName(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        return inspectatorActive
                && minecraft.options.getCameraType() != CameraType.FIRST_PERSON
                && entity != null
                && entity.getId() == inspectatorEntityId;
    }

    public static InspectatorCameraTransform getInspectatorCameraTransform(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!inspectatorActive
                || minecraft.level == null
                || minecraft.options.getCameraType() != CameraType.FIRST_PERSON) {
            return null;
        }

        Entity entity = minecraft.level.getEntity(inspectatorEntityId);
        if (!(entity instanceof PlayerNpcEntity playerNpc) || !playerNpc.isAlive()) {
            return null;
        }
        return new InspectatorCameraTransform(
                playerNpc.getEyePosition(partialTick),
                playerNpc.getViewYRot(partialTick),
                playerNpc.getViewXRot(partialTick)
        );
    }

    public static boolean shouldSuppressInspectatorRotationPacket(Packet<?> packet) {
        if (!inspectatorActive
                || Minecraft.getInstance().options.getCameraType() != CameraType.FIRST_PERSON
                || !(packet instanceof ServerboundMovePlayerPacket movePacket)) {
            return false;
        }
        return movePacket.hasRotation() && !movePacket.hasPosition();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }

        tickInspectatorControls(minecraft);
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (inspectatorActive
                && event.getNewScreen() instanceof InventoryScreen
                && minecraft.level != null
                && minecraft.player != null
                && isInspectatorToggleDown(minecraft)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMovementInputUpdate(MovementInputUpdateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (event.getEntity() != minecraft.player) {
            return;
        }

        if (inspectatorActive) {
            event.getInput().leftImpulse = 0.0F;
            event.getInput().forwardImpulse = 0.0F;
            event.getInput().up = false;
            event.getInput().down = false;
            event.getInput().left = false;
            event.getInput().right = false;
            event.getInput().jumping = false;
            event.getInput().shiftKeyDown = false;
        }
    }

    @SubscribeEvent
    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (inspectatorActive && VanillaGuiOverlay.JUMP_BAR.id().equals(event.getOverlay().id())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (inspectedEntityId < 0) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }

        Entity entity = minecraft.level.getEntity(inspectedEntityId);
        if (!(entity instanceof PlayerNpcEntity playerNpc) || !playerNpc.isAlive()) {
            clear();
            return;
        }

        requestRefresh(minecraft);
        refreshDisplayCache(minecraft.font, playerNpc);

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int x = screenWidth - PANEL_WIDTH - 12;
        int y = 18;
        renderPanel(guiGraphics, minecraft.font, x, y);
        renderHoveredItemTooltip(guiGraphics, minecraft, x, y, screenWidth, screenHeight);
    }

    private static void requestRefresh(Minecraft minecraft) {
        long gameTime = minecraft.level.getGameTime();
        if (lastRefreshGameTime != Long.MIN_VALUE
                && gameTime - lastRefreshGameTime < REFRESH_INTERVAL_TICKS) {
            return;
        }

        lastRefreshGameTime = gameTime;
        sendToServerIfConnected(new PlayerNpcInspectorRequestPacket(inspectedEntityId));
    }

    private static void refreshDisplayCache(Font font, PlayerNpcEntity playerNpc) {
        long now = Util.getMillis();
        if (lastDisplayCacheMillis != Long.MIN_VALUE
                && now - lastDisplayCacheMillis < DISPLAY_CACHE_INTERVAL_MS) {
            return;
        }

        lastDisplayCacheMillis = now;
        cachedTitle = Component.translatable("gui.player_npc.inspector.title", playerNpc.getDisplayName());

        float health = playerNpc.getHealth();
        float maxHealth = playerNpc.getMaxHealth();
        cachedHealthColor = health <= maxHealth * 0.35F ? 0xFFFF6B6B : health <= maxHealth * 0.65F ? 0xFFFFD166 : 0xFF74E291;
        cachedHealthText = Component.translatable(
                "gui.player_npc.inspector.health",
                String.format(Locale.ROOT, "%.1f", health),
                String.format(Locale.ROOT, "%.1f", maxHealth)
        );

        cachedAiText = Component.translatable(
                "gui.player_npc.inspector.ai",
                Component.translatable(playerNpc.getCurrentAiState()).withStyle(ChatFormatting.AQUA)
        );
        cachedInterestsText = trimToWidth(
                font,
                Component.translatable("gui.player_npc.inspector.interests", playerNpc.getInterestsDisplayText()).getString(),
                PANEL_WIDTH - 16
        );
        cachedBuildStatusText = trimToWidth(
                font,
                Component.translatable("gui.player_npc.inspector.build", snapshotBuildStatusText).getString(),
                PANEL_WIDTH - 16
        );
        cachedPerformanceText = trimToWidth(
                font,
                Component.translatable("gui.player_npc.inspector.performance", snapshotPerformanceText).getString(),
                PANEL_WIDTH - 16
        );
        cachedTraceText = trimToWidth(
                font,
                Component.translatable(
                        "gui.player_npc.inspector.trace",
                        Component.translatable(snapshotTraceEnabled
                                ? "gui.player_npc.inspector.trace_on"
                                : "gui.player_npc.inspector.trace_off")
                ).getString(),
                PANEL_WIDTH - 16
        );

        cachedTaskLabel = Component.translatable("gui.player_npc.inspector.task");
        cachedTaskLabelWidth = font.width(cachedTaskLabel);
        int taskMaxWidth = PANEL_WIDTH - cachedTaskLabelWidth - 20;
        int taskFullLineWidth = PANEL_WIDTH - 16;
        String detail = playerNpc.getCurrentAiDetail();
        if (detail == null || detail.isBlank()) {
            String state = playerNpc.getCurrentAiState();
            detail = PlayerNpcEntity.AI_IDLE.equals(state)
                    ? Component.translatable("ai.player_npc.looking_for_work").getString()
                    : Component.translatable(state).getString();
        }
        cachedTaskValueLines = taskLines(font, detail, taskMaxWidth, taskFullLineWidth);

        ItemStack mainHand = getSnapshotItem(0);
        Component itemName = mainHand.isEmpty()
                ? Component.translatable("gui.player_npc.inspector.empty")
                : mainHand.getHoverName();
        cachedMainHandText = Component.translatable("gui.player_npc.inspector.item_name", itemName);
        cachedInspectatorHint = trimToWidth(
                font,
                Component.translatable(spectatorHintKey()).getString(),
                PANEL_WIDTH - 16
        );
    }

    private static void renderPanel(GuiGraphics guiGraphics, Font font, int x, int y) {
        guiGraphics.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xE80F1720);
        guiGraphics.fill(x, y, x + PANEL_WIDTH, y + 1, 0xFF4FD1C5);
        guiGraphics.fill(x, y + PANEL_HEIGHT - 1, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xFF243447);
        guiGraphics.fill(x, y, x + 1, y + PANEL_HEIGHT, 0xFF243447);
        guiGraphics.fill(x + PANEL_WIDTH - 1, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xFF243447);

        guiGraphics.drawString(font, cachedTitle, x + 8, y + 7, 0xFFE6FFFA, false);
        guiGraphics.drawString(font, cachedHealthText, x + 8, y + 21, cachedHealthColor, false);
        guiGraphics.drawString(font, cachedAiText, x + 8, y + 35, 0xFFB7C9E2, false);
        guiGraphics.drawString(font, cachedInterestsText, x + 8, y + 49, 0xFFB7C9E2, false);
        guiGraphics.drawString(font, cachedBuildStatusText, x + 8, y + 63, 0xFFB7C9E2, false);
        guiGraphics.drawString(font, cachedPerformanceText, x + 8, y + 77, 0xFFB7C9E2, false);
        guiGraphics.drawString(font, cachedTraceText, x + 8, y + 91, snapshotTraceEnabled ? 0xFF74E291 : 0xFFB7C9E2, false);

        renderTaskDetail(guiGraphics, font, x + 8, y + 106);

        guiGraphics.drawString(font, cachedMainHandText, x + 8, y + 140, 0xFFD6E4FF, false);

        renderEquipment(guiGraphics, font, x + 8, y + 170);
        renderInventory(guiGraphics, font, x + 8, y + 204);
        guiGraphics.drawString(font, cachedInspectatorHint, x + 8, y + PANEL_HEIGHT - 15, 0xFF94A3B8, false);
    }

    private static void renderTaskDetail(GuiGraphics guiGraphics, Font font, int x, int y) {
        guiGraphics.drawString(font, cachedTaskLabel, x, y, 0xFF4FD1C5, false);

        int valueX = x + cachedTaskLabelWidth + 4;
        for (int i = 0; i < cachedTaskValueLines.size(); i++) {
            int lineX = i == 0 ? valueX : x;
            guiGraphics.drawString(font, cachedTaskValueLines.get(i), lineX, y + i * 11, 0xFFFFD166, false);
        }
    }

    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }

        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
    }

    private static List<String> taskLines(Font font, String detail, int firstLineWidth, int fullLineWidth) {
        List<String> lines = new ArrayList<>(TASK_DETAIL_MAX_LINES);
        String[] rawLines = detail.split("\\R", -1);
        for (String rawLine : rawLines) {
            if (lines.size() >= TASK_DETAIL_MAX_LINES) {
                break;
            }
            appendWrappedTaskLine(font, lines, rawLine, firstLineWidth, fullLineWidth);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private static void appendWrappedTaskLine(
            Font font,
            List<String> lines,
            String rawLine,
            int firstLineWidth,
            int fullLineWidth
    ) {
        String remaining = rawLine == null ? "" : rawLine.stripLeading();
        if (remaining.isEmpty()) {
            lines.add("");
            return;
        }

        while (!remaining.isEmpty() && lines.size() < TASK_DETAIL_MAX_LINES) {
            int width = lines.isEmpty() ? firstLineWidth : fullLineWidth;
            boolean isLastTaskLine = lines.size() == TASK_DETAIL_MAX_LINES - 1;
            if (font.width(remaining) <= width) {
                lines.add(remaining);
                return;
            }
            if (isLastTaskLine) {
                lines.add(trimToWidth(font, remaining, width));
                return;
            }

            String segment = font.plainSubstrByWidth(remaining, width).stripTrailing();
            if (segment.isEmpty()) {
                lines.add(trimToWidth(font, remaining, width));
                return;
            }

            lines.add(segment);
            remaining = remaining.substring(segment.length()).stripLeading();
        }
    }

    private static void renderEquipment(GuiGraphics guiGraphics, Font font, int x, int y) {
        guiGraphics.drawString(font, Component.translatable("gui.player_npc.inspector.equipment"), x, y - 10, 0xFF4FD1C5, false);
        for (int i = 0; i < 6; i++) {
            renderSlot(guiGraphics, font, getSnapshotItem(i), x + i * (SLOT_SIZE + 3), y);
        }
    }

    private static void renderInventory(GuiGraphics guiGraphics, Font font, int x, int y) {
        guiGraphics.drawString(font, Component.translatable("gui.player_npc.inspector.inventory"), x, y - 10, 0xFF4FD1C5, false);
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                int index = 6 + column + row * 9;
                renderSlot(guiGraphics, font, getSnapshotItem(index), x + column * (SLOT_SIZE + 3), y + row * (SLOT_SIZE + 2));
            }
        }
    }

    private static void renderSlot(GuiGraphics guiGraphics, Font font, ItemStack stack, int x, int y) {
        guiGraphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF0B1120);
        guiGraphics.fill(x, y, x + 16, y + 16, 0xFF1E293B);
        if (!stack.isEmpty()) {
            guiGraphics.renderItem(stack, x, y);
            guiGraphics.renderItemDecorations(font, stack, x, y);
        }
    }

    private static ItemStack getSnapshotItem(int index) {
        if (index < 0 || index >= snapshot.size()) {
            return ItemStack.EMPTY;
        }
        return snapshot.get(index);
    }

    private static Component getItemId(ItemStack stack) {
        if (stack.isEmpty()) {
            return Component.translatable("gui.player_npc.inspector.empty");
        }

        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? Component.literal("unknown") : Component.literal(id.toString());
    }

    private static void tickInspectatorControls(Minecraft minecraft) {
        boolean chatOpen = isChatScreen(minecraft.screen);
        if (inspectedEntityId < 0 || minecraft.screen != null && !chatOpen) {
            if (inspectatorActive) {
                stopInspectator(minecraft, true);
            }
            resetInspectatorToggle();
            previousCycleLeftDown = false;
            previousCycleRightDown = false;
            previousTraceToggleDown = false;
            inspectatorZoomRepeatTicks = 0;
            return;
        }

        Entity entity = minecraft.level.getEntity(inspectedEntityId);
        if (!(entity instanceof PlayerNpcEntity playerNpc) || !playerNpc.isAlive()) {
            clear();
            return;
        }

        if (chatOpen) {
            resetInspectatorToggle();
            previousCycleLeftDown = false;
            previousCycleRightDown = false;
            previousTraceToggleDown = false;
            inspectatorZoomRepeatTicks = 0;
            if (inspectatorActive) {
                tickActiveInspectator(minecraft, playerNpc, false);
            }
            return;
        }

        handleTraceToggleInput(minecraft, inspectedEntityId);

        boolean toggleDown = isInspectatorToggleDown(minecraft);
        if (toggleDown && !previousInspectatorToggleDown) {
            if (inspectatorActive && inspectatorEntityId == inspectedEntityId) {
                stopInspectator(minecraft, true);
            } else {
                startInspectator(minecraft, playerNpc);
            }
        }
        previousInspectatorToggleDown = toggleDown;

        if (inspectatorActive) {
            tickActiveInspectator(minecraft, playerNpc, true);
            return;
        }

        previousCycleLeftDown = false;
        previousCycleRightDown = false;
    }

    private static void startInspectator(Minecraft minecraft, PlayerNpcEntity playerNpc) {
        boolean startingFresh = !inspectatorActive;
        if (startingFresh) {
            previousCameraType = minecraft.options.getCameraType();
            previousCameraEntity = minecraft.getCameraEntity();
        }
        if (inspectatorEntityId != playerNpc.getId()) {
            snapshotTraceEnabled = false;
        }

        inspectatorActive = true;
        inspectatorEntityId = playerNpc.getId();
        lastDisplayCacheMillis = Long.MIN_VALUE;
        sendToServerIfConnected(new PlayerNpcInspectatorModePacket(true, inspectatorEntityId));
        if (startingFresh) {
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            inspectatorCameraDistance = INSPECTATOR_CAMERA_DISTANCE_DEFAULT;
        }
        minecraft.setCameraEntity(minecraft.player);
        if (minecraft.screen == null) {
            minecraft.mouseHandler.grabMouse();
        }
    }

    private static void tickActiveInspectator(Minecraft minecraft, PlayerNpcEntity playerNpc, boolean allowHotkeys) {
        if (minecraft.player != null && minecraft.getCameraEntity() != minecraft.player) {
            minecraft.setCameraEntity(minecraft.player);
        }

        if (minecraft.options.getCameraType() == CameraType.FIRST_PERSON) {
            inspectatorZoomRepeatTicks = 0;
        } else if (allowHotkeys) {
            handleInspectatorZoomInput(minecraft);
        }

        if (allowHotkeys) {
            handleInspectatorCycleInput(minecraft, playerNpc);
        }
        suppressPlayerInput(minecraft);
    }

    private static void stopInspectator(Minecraft minecraft, boolean notifyServer) {
        boolean wasActive = inspectatorActive;
        inspectatorActive = false;
        inspectatorEntityId = -1;
        resetInspectatorToggle();
        previousCycleLeftDown = false;
        previousCycleRightDown = false;
        previousTraceToggleDown = false;
        inspectatorZoomRepeatTicks = 0;
        lastDisplayCacheMillis = Long.MIN_VALUE;

        if (minecraft != null && minecraft.player != null) {
            minecraft.setCameraEntity(previousCameraEntity != null ? previousCameraEntity : minecraft.player);
            if (previousCameraType != null) {
                minecraft.options.setCameraType(previousCameraType);
            }
            if (minecraft.screen == null && wasActive) {
                minecraft.mouseHandler.grabMouse();
            }
        }

        previousCameraEntity = null;
        previousCameraType = null;
        if (notifyServer && wasActive) {
            sendToServerIfConnected(new PlayerNpcInspectatorModePacket(false, -1));
        }
    }

    private static boolean isInspectatorToggleDown(Minecraft minecraft) {
        return isInspectatorModifierDown(minecraft);
    }

    private static boolean isInspectatorModifierDown(Minecraft minecraft) {
        return isPhysicalKeyDown(minecraft, GLFW.GLFW_KEY_LEFT_ALT)
                || isPhysicalKeyDown(minecraft, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    private static void resetInspectatorToggle() {
        previousInspectatorToggleDown = false;
    }

    private static void handleInspectatorCycleInput(Minecraft minecraft, PlayerNpcEntity currentNpc) {
        boolean leftDown = minecraft.options.keyLeft.isDown() || isPhysicalKeyDown(minecraft, GLFW.GLFW_KEY_LEFT);
        boolean rightDown = minecraft.options.keyRight.isDown() || isPhysicalKeyDown(minecraft, GLFW.GLFW_KEY_RIGHT);

        if (leftDown && !previousCycleLeftDown) {
            cycleInspectedNpc(minecraft, currentNpc, -1);
        } else if (rightDown && !previousCycleRightDown) {
            cycleInspectedNpc(minecraft, currentNpc, 1);
        }

        previousCycleLeftDown = leftDown;
        previousCycleRightDown = rightDown;
        minecraft.options.keyLeft.setDown(false);
        minecraft.options.keyRight.setDown(false);
    }

    private static void handleTraceToggleInput(Minecraft minecraft, int entityId) {
        boolean traceDown = isPhysicalKeyDown(minecraft, GLFW.GLFW_KEY_Z);
        if (traceDown && !previousTraceToggleDown && entityId >= 0) {
            snapshotTraceEnabled = !snapshotTraceEnabled;
            lastDisplayCacheMillis = Long.MIN_VALUE;
            sendToServerIfConnected(new PlayerNpcGoalTracePacket(entityId, snapshotTraceEnabled));
        }
        previousTraceToggleDown = traceDown;
    }

    private static void handleInspectatorZoomInput(Minecraft minecraft) {
        if (inspectatorZoomRepeatTicks > 0) {
            inspectatorZoomRepeatTicks--;
        }

        boolean zoomIn = isPhysicalKeyDown(minecraft, GLFW.GLFW_KEY_UP);
        boolean zoomOut = isPhysicalKeyDown(minecraft, GLFW.GLFW_KEY_DOWN);
        if (zoomIn == zoomOut || inspectatorZoomRepeatTicks > 0) {
            return;
        }

        double delta = zoomIn ? -INSPECTATOR_CAMERA_DISTANCE_STEP : INSPECTATOR_CAMERA_DISTANCE_STEP;
        inspectatorCameraDistance = clamp(
                inspectatorCameraDistance + delta,
                INSPECTATOR_CAMERA_DISTANCE_MIN,
                INSPECTATOR_CAMERA_DISTANCE_MAX
        );
        inspectatorZoomRepeatTicks = INSPECTATOR_ZOOM_REPEAT_TICKS;
    }

    private static boolean isChatScreen(Screen screen) {
        return screen instanceof ChatScreen;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isPhysicalKeyDown(Minecraft minecraft, int key) {
        return InputConstants.isKeyDown(minecraft.getWindow().getWindow(), key);
    }

    private static void cycleInspectedNpc(Minecraft minecraft, PlayerNpcEntity currentNpc, int direction) {
        List<PlayerNpcEntity> nearby = new ArrayList<>(minecraft.level.getEntitiesOfClass(
                PlayerNpcEntity.class,
                currentNpc.getBoundingBox().inflate(INSPECTATOR_SWITCH_RADIUS),
                npc -> npc.isAlive()
        ));
        if (nearby.size() < 2) {
            return;
        }

        nearby.sort(Comparator
                .comparingDouble((PlayerNpcEntity npc) -> currentNpc.distanceToSqr(npc))
                .thenComparingInt(Entity::getId));

        int currentIndex = -1;
        for (int i = 0; i < nearby.size(); i++) {
            if (nearby.get(i).getId() == currentNpc.getId()) {
                currentIndex = i;
                break;
            }
        }
        if (currentIndex < 0) {
            return;
        }

        int nextIndex = Math.floorMod(currentIndex + direction, nearby.size());
        PlayerNpcEntity nextNpc = nearby.get(nextIndex);
        inspectedEntityId = nextNpc.getId();
        snapshot = List.of();
        snapshotTraceEnabled = false;
        lastRefreshGameTime = Long.MIN_VALUE;
        lastDisplayCacheMillis = Long.MIN_VALUE;
        sendToServerIfConnected(new PlayerNpcInspectorRequestPacket(inspectedEntityId));
        startInspectator(minecraft, nextNpc);
    }

    private static void suppressPlayerInput(Minecraft minecraft) {
        minecraft.options.keyUp.setDown(false);
        minecraft.options.keyDown.setDown(false);
        minecraft.options.keySprint.setDown(false);
        minecraft.options.keyShift.setDown(false);
        minecraft.options.keyAttack.setDown(false);
        minecraft.options.keyUse.setDown(false);
        minecraft.options.keyDrop.setDown(false);
        minecraft.options.keyPickItem.setDown(false);
        minecraft.options.keyJump.setDown(false);
        minecraft.options.keyInventory.setDown(false);
        if (minecraft.player != null && minecraft.player.input != null) {
            minecraft.player.input.forwardImpulse = 0.0F;
            minecraft.player.input.leftImpulse = 0.0F;
            minecraft.player.input.jumping = false;
            minecraft.player.input.shiftKeyDown = false;
        }
    }

    private static void renderHoveredItemTooltip(
            GuiGraphics guiGraphics,
            Minecraft minecraft,
            int panelX,
            int panelY,
            int screenWidth,
            int screenHeight
    ) {
        int mouseX = (int) (minecraft.mouseHandler.xpos() * screenWidth / minecraft.getWindow().getScreenWidth());
        int mouseY = (int) (minecraft.mouseHandler.ypos() * screenHeight / minecraft.getWindow().getScreenHeight());
        ItemStack hovered = getHoveredItem(panelX, panelY, mouseX, mouseY);
        if (!hovered.isEmpty()) {
            guiGraphics.renderTooltip(minecraft.font, hovered, mouseX, mouseY);
        }
    }

    private static ItemStack getHoveredItem(int panelX, int panelY, int mouseX, int mouseY) {
        ItemStack equipment = getHoveredItemInGrid(panelX + 8, panelY + 170, 6, 1, mouseX, mouseY, 0);
        if (!equipment.isEmpty()) {
            return equipment;
        }

        return getHoveredItemInGrid(panelX + 8, panelY + 204, 9, 3, mouseX, mouseY, 6);
    }

    private static ItemStack getHoveredItemInGrid(
            int x,
            int y,
            int columns,
            int rows,
            int mouseX,
            int mouseY,
            int itemOffset
    ) {
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int slotX = x + column * (SLOT_SIZE + 3);
                int slotY = y + row * (SLOT_SIZE + 2);
                if (mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16) {
                    return getSnapshotItem(itemOffset + column + row * columns);
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static String spectatorHintKey() {
        return inspectatorActive
                ? "gui.player_npc.inspector.inspectator_active"
                : "gui.player_npc.inspector.inspectator_hint";
    }

    private static void clear() {
        disableTraceIfNeeded();
        stopInspectator(Minecraft.getInstance(), true);
        inspectedEntityId = -1;
        snapshot = new ArrayList<>();
        snapshotBuildStatusText = "";
        snapshotPerformanceText = "";
        snapshotTraceEnabled = false;
        lastRefreshGameTime = Long.MIN_VALUE;
        lastDisplayCacheMillis = Long.MIN_VALUE;
        cachedTitle = Component.empty();
        cachedHealthText = Component.empty();
        cachedHealthColor = 0xFF74E291;
        cachedAiText = Component.empty();
        cachedInterestsText = "";
        cachedBuildStatusText = "";
        cachedPerformanceText = "";
        cachedTraceText = "";
        cachedTaskLabel = Component.empty();
        cachedTaskLabelWidth = 0;
        cachedTaskValueLines = List.of("");
        cachedMainHandText = Component.empty();
        cachedInspectatorHint = "";
    }

    private static void disableTraceIfNeeded() {
        if (snapshotTraceEnabled && inspectedEntityId >= 0) {
            sendToServerIfConnected(new PlayerNpcGoalTracePacket(inspectedEntityId, false));
        }
        snapshotTraceEnabled = false;
    }

    private static void sendToServerIfConnected(Object packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return;
        }

        SmartNpcNetwork.CHANNEL.sendToServer(packet);
    }

    public record InspectatorCameraTransform(Vec3 eyePosition, float yRot, float xRot) {}
}
