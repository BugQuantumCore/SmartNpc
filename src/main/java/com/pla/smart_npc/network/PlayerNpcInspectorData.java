package com.pla.smart_npc.network;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import com.pla.smart_npc.util.PlayerNpcBuildStatusUtil;
import com.pla.smart_npc.util.PlayerNpcAiWorkBudget;
import com.pla.smart_npc.util.PlayerNpcPerformanceMonitor;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class PlayerNpcInspectorData {
    private static final int MAX_RESOURCE_HOLDERS = 8;
    private static final int MAX_RESOURCE_TEXT_LENGTH = 96;

    private PlayerNpcInspectorData() {
    }

    public static List<ItemStack> createSnapshot(PlayerNpcEntity playerNpc) {
        SimpleContainer inventory = playerNpc.getInventory();
        List<ItemStack> items = new ArrayList<>(6 + inventory.getContainerSize());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.MAINHAND).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.OFFHAND).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.HEAD).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.CHEST).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.LEGS).copy());
        items.add(playerNpc.getItemBySlot(EquipmentSlot.FEET).copy());
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            items.add(inventory.getItem(i).copy());
        }
        return items;
    }

    public static String createBuildStatusText(PlayerNpcEntity playerNpc) {
        return PlayerNpcBuildStatusUtil.describe(playerNpc);
    }

    public static String createDailyJobText(PlayerNpcEntity playerNpc) {
        String selectedJob = playerNpc.getSelectedDailyJobDisplayText();
        long selectedDay = playerNpc.getSelectedDailyJobDay();
        if (!(playerNpc.level() instanceof ServerLevel serverLevel)) {
            return selectedDay < 0 ? selectedJob : selectedJob + " day " + selectedDay;
        }

        long currentDay = serverLevel.getDayTime() / 24000L;
        if (selectedDay < 0) {
            return selectedJob + " today " + currentDay;
        }
        return selectedJob
                + " day "
                + selectedDay
                + (selectedDay == currentDay ? "" : " stale, current " + currentDay);
    }

    public static String createBuildRequirementsText(PlayerNpcEntity playerNpc) {
        return PlayerNpcBuildStatusUtil.describeRequirements(playerNpc);
    }

    public static String createPerformanceText() {
        return PlayerNpcPerformanceMonitor.createInspectorText();
    }

    public static String createAiResourceText(MinecraftServer server, PlayerNpcEntity selectedNpc) {
        PlayerNpcAiWorkBudget.ResourceSnapshot snapshot = PlayerNpcAiWorkBudget.resourceSnapshot(server);
        List<PlayerNpcAiWorkBudget.ResourceHolder> holders = snapshot.holders();
        StringBuilder text = new StringBuilder(512);
        if (selectedNpc != null) {
            PlayerNpcAiWorkBudget.ResourceHolder selectedHolder = holders.stream()
                    .filter(holder -> holder.npcId().equals(selectedNpc.getUUID()))
                    .findFirst()
                    .orElse(null);
            text.append("Selected: ").append(bounded(selectedNpc.getDisplayName().getString()));
            text.append(selectedHolder == null ? " [no resource]" : " [" + roles(selectedHolder) + "]");
            text.append('\n');
        }
        text.append("Holders ").append(holders.size())
                .append(" | active ").append(snapshot.activeWorkerCount())
                .append(" | waiting ").append(snapshot.waitingNpcCount())
                .append(" | limit ").append(snapshot.effectiveWorkerLimit());
        text.append('\n').append(PlayerNpcPerformanceMonitor.createInspectorText());

        int shown = Math.min(MAX_RESOURCE_HOLDERS, holders.size());
        for (int i = 0; i < shown; i++) {
            PlayerNpcAiWorkBudget.ResourceHolder holder = holders.get(i);
            PlayerNpcEntity npc = resolveHolder(server, holder);
            boolean selected = selectedNpc != null && holder.npcId().equals(selectedNpc.getUUID());
            String name = npc == null ? holder.npcId().toString().substring(0, 8) : npc.getDisplayName().getString();
            String state = npc == null ? "unloaded" : Component.translatable(npc.getCurrentAiState()).getString();
            String detail = npc == null ? "resource retained while target is unavailable" : npc.getCurrentAiDetail();
            if (detail == null || detail.isBlank()) {
                detail = state;
            }
            text.append('\n').append(selected ? "> " : "- ")
                    .append(bounded(name)).append(" [").append(roles(holder)).append(']')
                    .append('\n').append("  ").append(bounded(state)).append(" - ").append(bounded(detail));
        }
        if (holders.size() > shown) {
            text.append('\n').append('+').append(holders.size() - shown).append(" more holders");
        }
        return text.toString();
    }

    private static PlayerNpcEntity resolveHolder(MinecraftServer server, PlayerNpcAiWorkBudget.ResourceHolder holder) {
        if (holder.playerNpc() != null && holder.playerNpc().isAlive()) {
            return holder.playerNpc();
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(holder.npcId()) instanceof PlayerNpcEntity playerNpc && playerNpc.isAlive()) {
                return playerNpc;
            }
        }
        return null;
    }

    private static String roles(PlayerNpcAiWorkBudget.ResourceHolder holder) {
        List<String> roles = new ArrayList<>(3);
        if (holder.worker()) roles.add("worker");
        if (holder.probeTurn()) roles.add("probe");
        if (holder.expensiveSlice()) roles.add("expensive");
        return roles.isEmpty() ? "resource" : String.join("/", roles);
    }

    private static String bounded(String value) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').strip();
        return clean.length() <= MAX_RESOURCE_TEXT_LENGTH
                ? clean
                : clean.substring(0, MAX_RESOURCE_TEXT_LENGTH - 3) + "...";
    }
}
