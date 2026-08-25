package com.pla.smart_npc.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;

public final class TreeAi {
    private static final int MAX_LOGS = 96;
    private static final int MIN_LEAVES = 3;
    private static final int LEAF_RADIUS = 4;
    private static final Map<Integer, List<ColumnOffset>> SEARCH_COLUMNS_BY_RADIUS = new HashMap<>();

    private TreeAi() {
    }

    public static Optional<Tree> findNearest(ServerLevel serverLevel, BlockPos center, int radius) {
        return findNearest(serverLevel, center, radius, pos -> true);
    }

    public static Optional<Tree> findNearest(ServerLevel serverLevel, BlockPos center, int radius, Predicate<BlockPos> allowedLogPos) {
        Set<BlockPos> visited = new HashSet<>();
        Tree bestTree = null;
        Tree bestLooseLog = null;
        double bestTreeDistance = Double.MAX_VALUE;
        double bestLooseLogDistance = Double.MAX_VALUE;

        // Search columns nearest-first. Once a real tree exists, an unvisited column whose
        // horizontal lower-bound already exceeds that tree's full squared distance cannot contain
        // a closer stump. The previous cuboid traversal always read every block in the full
        // 65x25x65 volume even when a tree stood across a small river.
        for (ColumnOffset offset : searchColumns(radius)) {
            if (bestTree != null && offset.distanceSqr() > bestTreeDistance) {
                break;
            }
            for (int dy = -6; dy <= 18; dy++) {
                BlockPos pos = center.offset(offset.dx(), dy, offset.dz());
                if (visited.contains(pos) || !isLog(serverLevel, pos, allowedLogPos)) {
                    continue;
                }

                Tree candidate = scan(serverLevel, pos, visited, allowedLogPos);
                double distance = candidate.stump().distSqr(center);
                if (candidate.isTree() && distance < bestTreeDistance) {
                    bestTree = candidate;
                    bestTreeDistance = distance;
                } else if (!candidate.isTree() && distance < bestLooseLogDistance) {
                    bestLooseLog = candidate;
                    bestLooseLogDistance = distance;
                }
            }
        }

        return Optional.ofNullable(bestTree != null ? bestTree : bestLooseLog);
    }

    private static List<ColumnOffset> searchColumns(int radius) {
        int safeRadius = Math.max(0, radius);
        synchronized (SEARCH_COLUMNS_BY_RADIUS) {
            return SEARCH_COLUMNS_BY_RADIUS.computeIfAbsent(safeRadius, value -> {
                List<ColumnOffset> offsets = new ArrayList<>((value * 2 + 1) * (value * 2 + 1));
                for (int dx = -value; dx <= value; dx++) {
                    for (int dz = -value; dz <= value; dz++) {
                        offsets.add(new ColumnOffset(dx, dz, dx * dx + dz * dz));
                    }
                }
                offsets.sort(Comparator
                        .comparingInt(ColumnOffset::distanceSqr)
                        .thenComparingInt(ColumnOffset::dx)
                        .thenComparingInt(ColumnOffset::dz));
                return List.copyOf(offsets);
            });
        }
    }

    private static Tree scan(ServerLevel serverLevel, BlockPos start, Set<BlockPos> globalVisited, Predicate<BlockPos> allowedLogPos) {
        Queue<BlockPos> open = new ArrayDeque<>();
        Set<BlockPos> localVisited = new HashSet<>();
        List<BlockPos> logs = new ArrayList<>();
        open.add(start.immutable());

        while (!open.isEmpty() && logs.size() < MAX_LOGS) {
            BlockPos pos = open.poll();
            if (!localVisited.add(pos) || !isLog(serverLevel, pos, allowedLogPos)) {
                continue;
            }

            logs.add(pos);
            globalVisited.add(pos);
            for (BlockPos adjacent : BlockPos.betweenClosed(pos.offset(-1, -1, -1), pos.offset(1, 1, 1))) {
                BlockPos next = adjacent.immutable();
                if (!localVisited.contains(next) && isLog(serverLevel, next, allowedLogPos)) {
                    open.add(next);
                }
            }
        }

        BlockPos stump = logs.stream()
                .min(Comparator
                        .comparingInt((BlockPos pos) -> pos.getY())
                        .thenComparingDouble(pos -> start.distSqr(pos)))
                .orElse(start)
                .immutable();
        int leaves = countNearbyLeaves(serverLevel, logs);
        return new Tree(stump, logs, leaves >= MIN_LEAVES);
    }

    private static int countNearbyLeaves(ServerLevel serverLevel, List<BlockPos> logs) {
        Set<BlockPos> leaves = new HashSet<>();
        for (BlockPos log : logs) {
            for (BlockPos mutable : BlockPos.betweenClosed(
                    log.offset(-LEAF_RADIUS, -1, -LEAF_RADIUS),
                    log.offset(LEAF_RADIUS, LEAF_RADIUS, LEAF_RADIUS))) {
                BlockPos pos = mutable.immutable();
                if (serverLevel.getBlockState(pos).is(BlockTags.LEAVES)) {
                    leaves.add(pos);
                    if (leaves.size() >= MIN_LEAVES) {
                        return leaves.size();
                    }
                }
            }
        }
        return leaves.size();
    }

    private static boolean isLog(ServerLevel serverLevel, BlockPos pos) {
        return isLog(serverLevel, pos, blockPos -> true);
    }

    private static boolean isLog(ServerLevel serverLevel, BlockPos pos, Predicate<BlockPos> allowedLogPos) {
        BlockState state = serverLevel.getBlockState(pos);
        return state.is(BlockTags.LOGS) && allowedLogPos.test(pos);
    }

    private record ColumnOffset(int dx, int dz, int distanceSqr) {
    }

    public static final class Tree {
        private final BlockPos stump;
        private final List<BlockPos> logs;
        private final boolean tree;

        private Tree(BlockPos stump, List<BlockPos> logs, boolean tree) {
            this.stump = stump.immutable();
            this.logs = List.copyOf(logs);
            this.tree = tree;
        }

        public BlockPos stump() {
            return this.stump;
        }

        public List<BlockPos> logsNearestFirst(BlockPos origin) {
            List<BlockPos> sorted = new ArrayList<>(this.logs);
            sorted.sort(Comparator
                    .comparingDouble((BlockPos pos) -> pos.distSqr(origin))
                    .thenComparingInt(BlockPos::getY));
            return sorted;
        }

        public boolean isTree() {
            return this.tree;
        }
    }
}
