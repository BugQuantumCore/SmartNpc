package com.pla.player_npc.util;

import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PlayerNpcBuildLayout {
    private final String id;
    private final int width;
    private final int height;
    private final int depth;
    private final String shape;
    private final List<RelativeBlock> blocks;
    private final Set<Long> footprint;

    public PlayerNpcBuildLayout(String id, int width, int height, int depth, String shape, List<RelativeBlock> blocks) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.shape = shape;
        this.blocks = List.copyOf(blocks);
        this.footprint = buildFootprint(this.blocks);
    }

    public String id() {
        return this.id;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int depth() {
        return this.depth;
    }

    public String shape() {
        return this.shape;
    }

    public List<RelativeBlock> blocks() {
        return this.blocks;
    }

    public int requiredBlocks() {
        return this.blocks.size();
    }

    public boolean isInFootprint(int x, int z) {
        return this.footprint.contains(packFootprint(x, z));
    }

    public Set<Long> footprint() {
        return this.footprint;
    }

    private static Set<Long> buildFootprint(List<RelativeBlock> blocks) {
        Set<Long> result = new HashSet<>();
        for (RelativeBlock block : blocks) {
            if (block.y() == 0 || "floor".equals(block.role()) || "foundation".equals(block.role())) {
                result.add(packFootprint(block.x(), block.z()));
            }
        }
        return Set.copyOf(result);
    }

    private static long packFootprint(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    public record RelativeBlock(int x, int y, int z, String role) {
        public BlockPos toWorld(BlockPos origin) {
            return origin.offset(this.x, this.y, this.z);
        }
    }
}
