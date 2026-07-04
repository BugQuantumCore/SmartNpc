package com.pla.player_npc.util;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.RandomSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class PlayerNpcBuildLayoutLoader extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = LogManager.getLogger();
    private static List<PlayerNpcBuildLayout> layouts = List.of();

    public PlayerNpcBuildLayoutLoader() {
        super(GSON, "builds");
    }

    @Override
    protected void apply(
            java.util.Map<ResourceLocation, JsonElement> map,
            ResourceManager resourceManager,
            ProfilerFiller profilerFiller
    ) {
        List<PlayerNpcBuildLayout> parsedLayouts = new ArrayList<>();
        for (java.util.Map.Entry<ResourceLocation, JsonElement> entry : map.entrySet()) {
            try {
                parseLayout(entry.getKey(), GsonHelper.convertToJsonObject(entry.getValue(), "build layout"))
                        .ifPresent(parsedLayouts::add);
            } catch (RuntimeException exception) {
                LOGGER.warn("Skipping invalid Player NPC build layout {}: {}", entry.getKey(), exception.getMessage());
            }
        }

        layouts = parsedLayouts.stream()
                .filter(layout -> !layout.blocks().isEmpty() && !layout.footprint().isEmpty())
                .sorted(Comparator.comparing(PlayerNpcBuildLayout::id))
                .collect(Collectors.toUnmodifiableList());
        LOGGER.info("Loaded {} Player NPC build layouts", layouts.size());
    }

    public static List<PlayerNpcBuildLayout> getLayouts() {
        return layouts;
    }

    public static Optional<PlayerNpcBuildLayout> getRandomLayout(RandomSource randomSource, int maxBlocks) {
        List<PlayerNpcBuildLayout> candidates = layouts.stream()
                .filter(layout -> layout.requiredBlocks() <= maxBlocks)
                .toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(candidates.get(randomSource.nextInt(candidates.size())));
    }

    private static Optional<PlayerNpcBuildLayout> parseLayout(ResourceLocation id, JsonObject root) {
        JsonObject size = GsonHelper.getAsJsonObject(root, "size");
        int width = GsonHelper.getAsInt(size, "width");
        int height = GsonHelper.getAsInt(size, "height");
        int depth = GsonHelper.getAsInt(size, "depth");
        if (width < 3 || height < 1 || depth < 3) {
            return Optional.empty();
        }

        String name = GsonHelper.getAsString(root, "name", id.toString());
        String shape = GsonHelper.getAsString(root, "shape", "rectangle");
        List<PlayerNpcBuildLayout.RelativeBlock> blocks = new ArrayList<>();
        JsonArray blocksArray = GsonHelper.getAsJsonArray(root, "blocks");
        for (JsonElement blockElement : blocksArray) {
            JsonObject block = GsonHelper.convertToJsonObject(blockElement, "block");
            JsonArray pos = GsonHelper.getAsJsonArray(block, "pos");
            if (pos.size() != 3) {
                continue;
            }

            int x = pos.get(0).getAsInt();
            int y = pos.get(1).getAsInt();
            int z = pos.get(2).getAsInt();
            if (x < 0 || x >= width || y < 0 || y >= height || z < 0 || z >= depth) {
                continue;
            }

            String role = GsonHelper.getAsString(block, "role", "wall");
            blocks.add(new PlayerNpcBuildLayout.RelativeBlock(x, y, z, role));
        }

        if (blocks.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new PlayerNpcBuildLayout(name, width, height, depth, shape, blocks));
    }
}
