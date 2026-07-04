package com.pla.player_npc.client.gui;

import com.pla.player_npc.PlayerNpc;
import com.pla.player_npc.entity.PlayerNpcEntity;
import com.pla.player_npc.network.PlayerNpcInspectorPacket;
import com.pla.player_npc.network.PlayerNpcInspectorRequestPacket;
import com.pla.player_npc.network.PlayerNpcNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Mod.EventBusSubscriber(modid = PlayerNpc.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerNpcInspectorOverlay {
    private static final int PANEL_WIDTH = 196;
    private static final int PANEL_HEIGHT = 194;
    private static final int SLOT_SIZE = 18;
    private static final int REFRESH_INTERVAL_TICKS = 10;
    private static int inspectedEntityId = -1;
    private static List<ItemStack> snapshot = List.of();
    private static long lastRefreshGameTime = Long.MIN_VALUE;

    public static void handlePacket(PlayerNpcInspectorPacket packet) {
        inspectedEntityId = packet.entityId();
        snapshot = packet.items();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiOverlayEvent.Post event) {
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

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int x = screenWidth - PANEL_WIDTH - 12;
        int y = 18;
        renderPanel(guiGraphics, minecraft.font, playerNpc, x, y);
    }

    private static void requestRefresh(Minecraft minecraft) {
        long gameTime = minecraft.level.getGameTime();
        if (lastRefreshGameTime != Long.MIN_VALUE
                && gameTime - lastRefreshGameTime < REFRESH_INTERVAL_TICKS) {
            return;
        }

        lastRefreshGameTime = gameTime;
        PlayerNpcNetwork.CHANNEL.sendToServer(new PlayerNpcInspectorRequestPacket(inspectedEntityId));
    }

    private static void renderPanel(GuiGraphics guiGraphics, Font font, PlayerNpcEntity playerNpc, int x, int y) {
        guiGraphics.fill(x, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xE80F1720);
        guiGraphics.fill(x, y, x + PANEL_WIDTH, y + 1, 0xFF4FD1C5);
        guiGraphics.fill(x, y + PANEL_HEIGHT - 1, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xFF243447);
        guiGraphics.fill(x, y, x + 1, y + PANEL_HEIGHT, 0xFF243447);
        guiGraphics.fill(x + PANEL_WIDTH - 1, y, x + PANEL_WIDTH, y + PANEL_HEIGHT, 0xFF243447);

        Component title = Component.translatable("gui.player_npc.inspector.title", playerNpc.getDisplayName());
        guiGraphics.drawString(font, title, x + 8, y + 7, 0xFFE6FFFA, false);

        float health = playerNpc.getHealth();
        float maxHealth = playerNpc.getMaxHealth();
        int healthColor = health <= maxHealth * 0.35F ? 0xFFFF6B6B : health <= maxHealth * 0.65F ? 0xFFFFD166 : 0xFF74E291;
        Component healthText = Component.translatable(
                "gui.player_npc.inspector.health",
                String.format(Locale.ROOT, "%.1f", health),
                String.format(Locale.ROOT, "%.1f", maxHealth)
        );
        guiGraphics.drawString(font, healthText, x + 8, y + 21, healthColor, false);

        Component aiText = Component.translatable(
                "gui.player_npc.inspector.ai",
                Component.translatable(playerNpc.getCurrentAiState()).withStyle(ChatFormatting.AQUA)
        );
        guiGraphics.drawString(font, aiText, x + 8, y + 35, 0xFFB7C9E2, false);

        renderTaskDetail(guiGraphics, font, playerNpc, x + 8, y + 50);

        ItemStack mainHand = getSnapshotItem(0);
        Component itemName = mainHand.isEmpty()
                ? Component.translatable("gui.player_npc.inspector.empty")
                : mainHand.getHoverName();
        guiGraphics.drawString(font, Component.translatable("gui.player_npc.inspector.item_name", itemName), x + 8, y + 64, 0xFFD6E4FF, false);

        renderEquipment(guiGraphics, font, x + 8, y + 94);
        renderInventory(guiGraphics, font, x + 8, y + 128);
    }

    private static void renderTaskDetail(GuiGraphics guiGraphics, Font font, PlayerNpcEntity playerNpc, int x, int y) {
        Component label = Component.translatable("gui.player_npc.inspector.task");
        guiGraphics.drawString(font, label, x, y, 0xFF4FD1C5, false);

        int valueX = x + font.width(label) + 4;
        int maxWidth = PANEL_WIDTH - (valueX - x) - 16;
        String detail = playerNpc.getCurrentAiDetail();
        String value = detail == null || detail.isBlank()
                ? Component.translatable("gui.player_npc.inspector.empty").getString()
                : trimToWidth(font, detail, maxWidth);
        guiGraphics.drawString(font, value, valueX, y, 0xFFFFD166, false);
    }

    private static String trimToWidth(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }

        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
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

    private static void clear() {
        inspectedEntityId = -1;
        snapshot = new ArrayList<>();
        lastRefreshGameTime = Long.MIN_VALUE;
    }
}
