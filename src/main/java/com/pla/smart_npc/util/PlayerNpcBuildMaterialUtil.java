package com.pla.smart_npc.util;

import com.pla.smart_npc.entity.PlayerNpcEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class PlayerNpcBuildMaterialUtil {
    private static final int BUILD_CRAFT_RAW_LOG_RESERVE = 0;
    private static final int GLASS_PANE_CRAFT_INPUT = 6;
    private static final EnumMap<MaterialFamily, List<Item>> CANDIDATE_CACHE = new EnumMap<>(MaterialFamily.class);
    private static final Set<String> WOOD_PREFIXES = Set.of(
            "oak",
            "spruce",
            "birch",
            "jungle",
            "acacia",
            "dark_oak",
            "mangrove",
            "cherry",
            "bamboo",
            "crimson",
            "warped"
    );
    private static final List<Item> COBBLESTONE_LIKE = List.of(
            Items.COBBLESTONE,
            Items.MOSSY_COBBLESTONE,
            Items.COBBLED_DEEPSLATE,
            Items.BLACKSTONE,
            Items.SANDSTONE,
            Items.RED_SANDSTONE
    );
    private static final List<Item> LOOSE_FILL = List.of(
            Items.DIRT,
            Items.COARSE_DIRT,
            Items.ROOTED_DIRT,
            Items.GRASS_BLOCK,
            Items.SAND,
            Items.RED_SAND,
            Items.GRAVEL,
            Items.MUD
    );
    private static final List<Item> STONE_MASONRY = List.of(
            Items.STONE,
            Items.SMOOTH_STONE,
            Items.STONE_BRICKS,
            Items.CRACKED_STONE_BRICKS,
            Items.MOSSY_STONE_BRICKS,
            Items.BRICKS,
            Items.ANDESITE,
            Items.POLISHED_ANDESITE,
            Items.DIORITE,
            Items.POLISHED_DIORITE,
            Items.GRANITE,
            Items.POLISHED_GRANITE,
            Items.DEEPSLATE,
            Items.POLISHED_DEEPSLATE,
            Items.DEEPSLATE_BRICKS,
            Items.CRACKED_DEEPSLATE_BRICKS,
            Items.TUFF,
            Items.CALCITE,
            Items.SANDSTONE,
            Items.SMOOTH_SANDSTONE,
            Items.CUT_SANDSTONE,
            Items.RED_SANDSTONE,
            Items.SMOOTH_RED_SANDSTONE,
            Items.CUT_RED_SANDSTONE
    );
    private static final List<Item> STONE_STAIRS = List.of(
            Items.COBBLESTONE_STAIRS,
            Items.MOSSY_COBBLESTONE_STAIRS,
            Items.STONE_STAIRS,
            Items.STONE_BRICK_STAIRS,
            Items.MOSSY_STONE_BRICK_STAIRS,
            Items.BRICK_STAIRS,
            Items.ANDESITE_STAIRS,
            Items.POLISHED_ANDESITE_STAIRS,
            Items.DIORITE_STAIRS,
            Items.POLISHED_DIORITE_STAIRS,
            Items.GRANITE_STAIRS,
            Items.POLISHED_GRANITE_STAIRS,
            Items.COBBLED_DEEPSLATE_STAIRS,
            Items.POLISHED_DEEPSLATE_STAIRS,
            Items.DEEPSLATE_BRICK_STAIRS,
            Items.BLACKSTONE_STAIRS,
            Items.POLISHED_BLACKSTONE_STAIRS,
            Items.SANDSTONE_STAIRS,
            Items.SMOOTH_SANDSTONE_STAIRS,
            Items.RED_SANDSTONE_STAIRS,
            Items.SMOOTH_RED_SANDSTONE_STAIRS
    );
    private static final List<Item> STONE_SLABS = List.of(
            Items.COBBLESTONE_SLAB,
            Items.MOSSY_COBBLESTONE_SLAB,
            Items.STONE_SLAB,
            Items.SMOOTH_STONE_SLAB,
            Items.STONE_BRICK_SLAB,
            Items.MOSSY_STONE_BRICK_SLAB,
            Items.BRICK_SLAB,
            Items.ANDESITE_SLAB,
            Items.POLISHED_ANDESITE_SLAB,
            Items.DIORITE_SLAB,
            Items.POLISHED_DIORITE_SLAB,
            Items.GRANITE_SLAB,
            Items.POLISHED_GRANITE_SLAB,
            Items.COBBLED_DEEPSLATE_SLAB,
            Items.POLISHED_DEEPSLATE_SLAB,
            Items.DEEPSLATE_BRICK_SLAB,
            Items.BLACKSTONE_SLAB,
            Items.POLISHED_BLACKSTONE_SLAB,
            Items.SANDSTONE_SLAB,
            Items.SMOOTH_SANDSTONE_SLAB,
            Items.RED_SANDSTONE_SLAB,
            Items.SMOOTH_RED_SANDSTONE_SLAB
    );
    private static final List<Item> WOODEN_BUTTONS = List.of(
            Items.OAK_BUTTON,
            Items.SPRUCE_BUTTON,
            Items.BIRCH_BUTTON,
            Items.JUNGLE_BUTTON,
            Items.ACACIA_BUTTON,
            Items.DARK_OAK_BUTTON,
            Items.MANGROVE_BUTTON,
            Items.CHERRY_BUTTON,
            Items.BAMBOO_BUTTON,
            Items.CRIMSON_BUTTON,
            Items.WARPED_BUTTON
    );
    private static final List<Item> STONE_BUTTONS = List.of(
            Items.STONE_BUTTON,
            Items.POLISHED_BLACKSTONE_BUTTON
    );
    private static final List<Item> WOODEN_PRESSURE_PLATES = List.of(
            Items.OAK_PRESSURE_PLATE,
            Items.SPRUCE_PRESSURE_PLATE,
            Items.BIRCH_PRESSURE_PLATE,
            Items.JUNGLE_PRESSURE_PLATE,
            Items.ACACIA_PRESSURE_PLATE,
            Items.DARK_OAK_PRESSURE_PLATE,
            Items.MANGROVE_PRESSURE_PLATE,
            Items.CHERRY_PRESSURE_PLATE,
            Items.BAMBOO_PRESSURE_PLATE,
            Items.CRIMSON_PRESSURE_PLATE,
            Items.WARPED_PRESSURE_PLATE
    );
    private static final List<Item> STONE_OR_METAL_PRESSURE_PLATES = List.of(
            Items.STONE_PRESSURE_PLATE,
            Items.POLISHED_BLACKSTONE_PRESSURE_PLATE,
            Items.LIGHT_WEIGHTED_PRESSURE_PLATE,
            Items.HEAVY_WEIGHTED_PRESSURE_PLATE
    );
    private static final List<Item> GLASS_BLOCKS = List.of(
            Items.GLASS,
            Items.WHITE_STAINED_GLASS,
            Items.ORANGE_STAINED_GLASS,
            Items.MAGENTA_STAINED_GLASS,
            Items.LIGHT_BLUE_STAINED_GLASS,
            Items.YELLOW_STAINED_GLASS,
            Items.LIME_STAINED_GLASS,
            Items.PINK_STAINED_GLASS,
            Items.GRAY_STAINED_GLASS,
            Items.LIGHT_GRAY_STAINED_GLASS,
            Items.CYAN_STAINED_GLASS,
            Items.PURPLE_STAINED_GLASS,
            Items.BLUE_STAINED_GLASS,
            Items.BROWN_STAINED_GLASS,
            Items.GREEN_STAINED_GLASS,
            Items.RED_STAINED_GLASS,
            Items.BLACK_STAINED_GLASS
    );
    private static final List<Item> GLASS_PANES = List.of(
            Items.GLASS_PANE,
            Items.WHITE_STAINED_GLASS_PANE,
            Items.ORANGE_STAINED_GLASS_PANE,
            Items.MAGENTA_STAINED_GLASS_PANE,
            Items.LIGHT_BLUE_STAINED_GLASS_PANE,
            Items.YELLOW_STAINED_GLASS_PANE,
            Items.LIME_STAINED_GLASS_PANE,
            Items.PINK_STAINED_GLASS_PANE,
            Items.GRAY_STAINED_GLASS_PANE,
            Items.LIGHT_GRAY_STAINED_GLASS_PANE,
            Items.CYAN_STAINED_GLASS_PANE,
            Items.PURPLE_STAINED_GLASS_PANE,
            Items.BLUE_STAINED_GLASS_PANE,
            Items.BROWN_STAINED_GLASS_PANE,
            Items.GREEN_STAINED_GLASS_PANE,
            Items.RED_STAINED_GLASS_PANE,
            Items.BLACK_STAINED_GLASS_PANE
    );
    private static final List<Item> FLOWERS = List.of(
            Items.DANDELION,
            Items.POPPY,
            Items.BLUE_ORCHID,
            Items.ALLIUM,
            Items.AZURE_BLUET,
            Items.RED_TULIP,
            Items.ORANGE_TULIP,
            Items.WHITE_TULIP,
            Items.PINK_TULIP,
            Items.OXEYE_DAISY,
            Items.CORNFLOWER,
            Items.LILY_OF_THE_VALLEY,
            Items.WITHER_ROSE,
            Items.TORCHFLOWER
    );
    private static final List<Item> POTTABLE_PLANTS = List.of(
            Items.DANDELION,
            Items.POPPY,
            Items.BLUE_ORCHID,
            Items.ALLIUM,
            Items.AZURE_BLUET,
            Items.RED_TULIP,
            Items.ORANGE_TULIP,
            Items.WHITE_TULIP,
            Items.PINK_TULIP,
            Items.OXEYE_DAISY,
            Items.CORNFLOWER,
            Items.LILY_OF_THE_VALLEY,
            Items.WITHER_ROSE,
            Items.TORCHFLOWER,
            Items.OAK_SAPLING,
            Items.SPRUCE_SAPLING,
            Items.BIRCH_SAPLING,
            Items.JUNGLE_SAPLING,
            Items.ACACIA_SAPLING,
            Items.DARK_OAK_SAPLING,
            Items.MANGROVE_PROPAGULE,
            Items.CHERRY_SAPLING,
            Items.FERN,
            Items.DEAD_BUSH,
            Items.CACTUS,
            Items.BAMBOO,
            Items.RED_MUSHROOM,
            Items.BROWN_MUSHROOM,
            Items.CRIMSON_FUNGUS,
            Items.WARPED_FUNGUS,
            Items.CRIMSON_ROOTS,
            Items.WARPED_ROOTS,
            Items.AZALEA,
            Items.FLOWERING_AZALEA
    );

    private PlayerNpcBuildMaterialUtil() {
    }

    public static boolean matches(BlockState existingState, BlockState targetState) {
        if (existingState.equals(targetState)) {
            return true;
        }
        if (targetState.isAir()) {
            return existingState.isAir();
        }

        MaterialFamily family = familyForState(targetState);
        return family != null
                && family == familyForState(existingState)
                && sharedPropertiesMatch(targetState, existingState);
    }

    public static boolean hasMaterialFor(ServerLevel serverLevel, PlayerNpcEntity playerNpc, PlayerNpcBuildLayout.RelativeBlock block, BlockPos origin) {
        BlockState targetState = block.state();
        if (targetState.isAir() || block.isSecondHalfOfSingleItemBlock()) {
            return true;
        }
        if (isPottedPlant(targetState)) {
            return hasFlowerPot(playerNpc) && hasPottablePlant(playerNpc);
        }

        return findAvailableItem(serverLevel, playerNpc, targetState).isPresent();
    }

    public static boolean needsCraftingForPlacement(ServerLevel serverLevel, PlayerNpcEntity playerNpc, PlayerNpcBuildLayout.RelativeBlock block) {
        if (block.state().isAir() || block.isSecondHalfOfSingleItemBlock() || isPottedPlant(block.state())) {
            return false;
        }

        return findDirectItem(playerNpc, block.state()).isEmpty()
                && findCraftableItem(serverLevel, playerNpc, block.state()).isPresent();
    }

    public static boolean craftMaterialFor(ServerLevel serverLevel, PlayerNpcEntity playerNpc, PlayerNpcBuildLayout.RelativeBlock block) {
        if (block.state().isAir() || block.requiredItem().isEmpty() || findDirectItem(playerNpc, block.state()).isPresent()) {
            return false;
        }

        Optional<Item> item = findCraftableItem(serverLevel, playerNpc, block.state());
        if (item.isEmpty()) {
            return false;
        }
        if (item.get() == Items.GLASS_PANE && tryCraftGlassPaneFromAnyGlass(playerNpc.getInventory())) {
            return true;
        }
        return PlayerNpcCraftingUtil.tryCraftWithLogConversion(serverLevel, playerNpc.getInventory(), item.get(), true, BUILD_CRAFT_RAW_LOG_RESERVE);
    }

    public static Optional<PlacementMaterial> resolvePlacement(ServerLevel serverLevel, PlayerNpcEntity playerNpc, PlayerNpcBuildLayout.RelativeBlock block, BlockPos origin) {
        BlockState targetState = block.state();
        if (targetState.isAir()) {
            return Optional.of(new PlacementMaterial(targetState, ItemStack.EMPTY));
        }
        if (isPottedPlant(targetState)) {
            return resolvePottedPlantPlacement(playerNpc, targetState);
        }
        if (block.requiredItem().isEmpty()) {
            return Optional.of(new PlacementMaterial(resolveSecondHalfState(serverLevel, block.toWorld(origin), targetState), ItemStack.EMPTY));
        }

        Optional<Item> item = findAvailableItem(serverLevel, playerNpc, targetState);
        if (item.isEmpty()) {
            return Optional.empty();
        }

        ItemStack consumed = consumeOrCraft(serverLevel, playerNpc, item.get());
        if (consumed.isEmpty()) {
            return Optional.empty();
        }

        BlockState placementState = stateForItem(targetState, consumed.getItem()).orElse(targetState);
        return Optional.of(new PlacementMaterial(placementState, consumed));
    }

    public static ItemStack previewItem(ServerLevel serverLevel, PlayerNpcEntity playerNpc, PlayerNpcBuildLayout.RelativeBlock block) {
        if (block.state().isAir() || block.isSecondHalfOfSingleItemBlock()) {
            return ItemStack.EMPTY;
        }
        if (isPottedPlant(block.state())) {
            return new ItemStack(Items.FLOWER_POT);
        }

        return findAvailableItem(serverLevel, playerNpc, block.state())
                .map(ItemStack::new)
                .orElse(block.requiredItem());
    }

    public static boolean needsSandForBuildMaterial(ServerLevel serverLevel, PlayerNpcEntity playerNpc, PlayerNpcBuildLayout.RelativeBlock block, BlockPos origin) {
        BlockState targetState = block.state();
        MaterialFamily family = familyForState(targetState);
        return (family == MaterialFamily.GLASS_BLOCKS || family == MaterialFamily.GLASS_PANES)
                && !hasMaterialFor(serverLevel, playerNpc, block, origin)
                && countFamilyItems(playerNpc, MaterialFamily.GLASS_BLOCKS) + countGlassSmeltingInput(playerNpc) < requiredGlassFor(family);
    }

    public static boolean isSecondHalfOfSingleItemBlock(BlockState state) {
        if (state.getBlock() instanceof BedBlock
                && state.hasProperty(BedBlock.PART)
                && state.getValue(BedBlock.PART) == BedPart.HEAD) {
            return true;
        }

        if (state.getBlock() instanceof DoublePlantBlock
                && state.hasProperty(DoublePlantBlock.HALF)
                && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }

        return state.getBlock() instanceof DoorBlock
                && state.hasProperty(DoorBlock.HALF)
                && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    public static String describeTarget(BlockState state) {
        MaterialFamily family = familyForState(state);
        return family == null ? state.getBlock().getName().getString() : family.displayName;
    }

    public static boolean needsGlassSmelting(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        return missingGlassForProduction(serverLevel, playerNpc) > 0 && countGlassSmeltingInput(playerNpc) > 0;
    }

    public static int missingGlassForProduction(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<GlassProductionNeed> need = findGlassProductionNeed(serverLevel, playerNpc);
        if (need.isEmpty()) {
            return 0;
        }

        return Math.max(0, need.get().requiredGlass() - countFamilyItems(playerNpc, MaterialFamily.GLASS_BLOCKS));
    }

    public static boolean isGlassSmeltingInput(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(Items.SAND) || stack.is(Items.RED_SAND));
    }

    private static Optional<Item> findAvailableItem(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockState targetState) {
        if (isPottedPlant(targetState)) {
            return hasFlowerPot(playerNpc) && hasPottablePlant(playerNpc) ? Optional.of(Items.FLOWER_POT) : Optional.empty();
        }

        Optional<Item> direct = findDirectItem(playerNpc, targetState);
        if (direct.isPresent()) {
            return direct;
        }
        return findCraftableItem(serverLevel, playerNpc, targetState);
    }

    private static Optional<Item> findDirectItem(PlayerNpcEntity playerNpc, BlockState targetState) {
        Item targetItem = targetState.getBlock().asItem();
        MaterialFamily family = familyForState(targetState);
        List<Item> candidates = candidateItems(family, targetItem);

        for (Item item : candidates) {
            if (InventoryUtils.hasItem(playerNpc, item)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    private static Optional<Item> findCraftableItem(ServerLevel serverLevel, PlayerNpcEntity playerNpc, BlockState targetState) {
        Item targetItem = targetState.getBlock().asItem();
        MaterialFamily family = familyForState(targetState);
        if (family == MaterialFamily.GLASS_PANES && canCraftGlassPaneFromAnyGlass(playerNpc.getInventory())) {
            return Optional.of(Items.GLASS_PANE);
        }

        List<Item> candidates = candidateItems(family, targetItem);

        SimpleContainer inventory = playerNpc.getInventory();
        for (Item item : candidates) {
            if (PlayerNpcCraftingUtil.canCraftWithLogConversion(serverLevel, inventory, item, true, BUILD_CRAFT_RAW_LOG_RESERVE)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    private static ItemStack consumeOrCraft(ServerLevel serverLevel, PlayerNpcEntity playerNpc, Item item) {
        ItemStack consumed = playerNpc.consumeInventoryItem(item, 1).orElse(ItemStack.EMPTY);
        if (!consumed.isEmpty()) {
            return consumed;
        }

        if (item == Items.GLASS_PANE && tryCraftGlassPaneFromAnyGlass(playerNpc.getInventory())) {
            return playerNpc.consumeInventoryItem(item, 1).orElse(ItemStack.EMPTY);
        }

        if (!PlayerNpcCraftingUtil.tryCraftWithLogConversion(serverLevel, playerNpc.getInventory(), item, true, BUILD_CRAFT_RAW_LOG_RESERVE)) {
            return ItemStack.EMPTY;
        }
        return playerNpc.consumeInventoryItem(item, 1).orElse(ItemStack.EMPTY);
    }

    private static List<Item> candidateItems(MaterialFamily family, Item targetItem) {
        List<Item> result = new ArrayList<>();
        if (targetItem != null && targetItem != Items.AIR) {
            result.add(targetItem);
        }
        if (family == null) {
            return result;
        }

        for (Item item : candidatesForFamily(family)) {
            if (item != targetItem && !result.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private static List<Item> candidatesForFamily(MaterialFamily family) {
        return CANDIDATE_CACHE.computeIfAbsent(family, key -> {
            List<Item> curated = curatedCandidates(key);
            if (!curated.isEmpty()) {
                return curated;
            }

            List<Item> result = new ArrayList<>();
            for (Item item : ForgeRegistries.ITEMS.getValues()) {
                if (familyForItem(item) == key) {
                    result.add(item);
                }
            }
            return List.copyOf(result);
        });
    }

    private static List<Item> curatedCandidates(MaterialFamily family) {
        return switch (family) {
            case COBBLESTONE_LIKE -> COBBLESTONE_LIKE;
            case LOOSE_FILL -> LOOSE_FILL;
            case STONE_MASONRY -> STONE_MASONRY;
            case STONE_STAIRS -> STONE_STAIRS;
            case STONE_SLABS -> STONE_SLABS;
            case WOODEN_BUTTONS -> WOODEN_BUTTONS;
            case STONE_BUTTONS -> STONE_BUTTONS;
            case WOODEN_PRESSURE_PLATES -> WOODEN_PRESSURE_PLATES;
            case STONE_OR_METAL_PRESSURE_PLATES -> STONE_OR_METAL_PRESSURE_PLATES;
            case GLASS_BLOCKS -> GLASS_BLOCKS;
            case GLASS_PANES -> GLASS_PANES;
            case FLOWERS -> FLOWERS;
            case FLOWER_POTS -> List.of(Items.FLOWER_POT);
            default -> List.of();
        };
    }

    private static Optional<BlockState> stateForItem(BlockState targetState, Item item) {
        if (!(item instanceof BlockItem blockItem)) {
            return Optional.empty();
        }
        return Optional.of(copySharedProperties(targetState, blockItem.getBlock().defaultBlockState()));
    }

    private static BlockState resolveSecondHalfState(ServerLevel serverLevel, BlockPos pos, BlockState targetState) {
        if (targetState.getBlock() instanceof DoorBlock
                && targetState.hasProperty(DoorBlock.HALF)
                && targetState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            BlockState lower = serverLevel.getBlockState(pos.below());
            if (familyForState(lower) == MaterialFamily.WOODEN_DOORS) {
                return copySharedProperties(targetState, lower.getBlock().defaultBlockState());
            }
        }

        if (targetState.getBlock() instanceof DoublePlantBlock
                && targetState.hasProperty(DoublePlantBlock.HALF)
                && targetState.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            BlockState lower = serverLevel.getBlockState(pos.below());
            if (lower.getBlock() instanceof DoublePlantBlock) {
                return copySharedProperties(targetState, lower.getBlock().defaultBlockState());
            }
        }

        if (targetState.getBlock() instanceof BedBlock
                && targetState.hasProperty(BedBlock.PART)
                && targetState.hasProperty(BedBlock.FACING)
                && targetState.getValue(BedBlock.PART) == BedPart.HEAD) {
            Direction facing = targetState.getValue(BedBlock.FACING);
            BlockState foot = serverLevel.getBlockState(pos.relative(facing.getOpposite()));
            if (familyForState(foot) == MaterialFamily.BEDS) {
                return copySharedProperties(targetState, foot.getBlock().defaultBlockState());
            }
        }

        return targetState;
    }

    private static MaterialFamily familyForState(BlockState state) {
        if (state.is(Blocks.FLOWER_POT)) {
            return MaterialFamily.FLOWER_POTS;
        }
        if (isPottedPlant(state)) {
            return MaterialFamily.POTTED_FLOWERS;
        }
        return familyForItem(state.getBlock().asItem());
    }

    private static MaterialFamily familyForItem(Item item) {
        if (!(item instanceof BlockItem blockItem)) {
            return null;
        }

        Block block = blockItem.getBlock();
        BlockState state = block.defaultBlockState();
        ItemStack stack = new ItemStack(item);
        if (block instanceof BedBlock) {
            return MaterialFamily.BEDS;
        }
        if (block instanceof CarpetBlock) {
            return MaterialFamily.CARPETS;
        }
        if (block instanceof ButtonBlock) {
            if (containsItem(WOODEN_BUTTONS, item) || isWoodNamedItem(item, "_button")) {
                return MaterialFamily.WOODEN_BUTTONS;
            }
            if (containsItem(STONE_BUTTONS, item)) {
                return MaterialFamily.STONE_BUTTONS;
            }
        }
        if (block instanceof PressurePlateBlock || block instanceof WeightedPressurePlateBlock) {
            if (containsItem(WOODEN_PRESSURE_PLATES, item) || isWoodNamedItem(item, "_pressure_plate")) {
                return MaterialFamily.WOODEN_PRESSURE_PLATES;
            }
            if (containsItem(STONE_OR_METAL_PRESSURE_PLATES, item)) {
                return MaterialFamily.STONE_OR_METAL_PRESSURE_PLATES;
            }
        }
        if (item == Items.FLOWER_POT) {
            return MaterialFamily.FLOWER_POTS;
        }
        if (containsItem(GLASS_BLOCKS, item)) {
            return MaterialFamily.GLASS_BLOCKS;
        }
        if (containsItem(GLASS_PANES, item)) {
            return MaterialFamily.GLASS_PANES;
        }
        if (containsItem(FLOWERS, item)) {
            return MaterialFamily.FLOWERS;
        }
        if (stack.is(ItemTags.PLANKS)) {
            return MaterialFamily.PLANKS;
        }
        if (stack.is(ItemTags.LOGS)) {
            return MaterialFamily.LOGS;
        }
        if (block instanceof DoorBlock && isWoodNamedItem(item, "_door")) {
            return MaterialFamily.WOODEN_DOORS;
        }
        if (block instanceof TrapDoorBlock && isWoodNamedItem(item, "_trapdoor")) {
            return MaterialFamily.WOODEN_TRAPDOORS;
        }
        if (block instanceof FenceGateBlock && isWoodNamedItem(item, "_fence_gate")) {
            return MaterialFamily.WOODEN_FENCE_GATES;
        }
        if (block instanceof FenceBlock && isWoodNamedItem(item, "_fence")) {
            return MaterialFamily.WOODEN_FENCES;
        }
        if (block instanceof StairBlock) {
            return isWoodNamedItem(item, "_stairs") ? MaterialFamily.WOODEN_STAIRS : stoneFamilyForItem(item, MaterialFamily.STONE_STAIRS);
        }
        if (block instanceof SlabBlock) {
            return isWoodNamedItem(item, "_slab") ? MaterialFamily.WOODEN_SLABS : stoneFamilyForItem(item, MaterialFamily.STONE_SLABS);
        }
        if (containsItem(COBBLESTONE_LIKE, item)) {
            return MaterialFamily.COBBLESTONE_LIKE;
        }
        if (containsItem(LOOSE_FILL, item)) {
            return MaterialFamily.LOOSE_FILL;
        }
        if (containsItem(STONE_MASONRY, item)) {
            return MaterialFamily.STONE_MASONRY;
        }
        return null;
    }

    private static MaterialFamily stoneFamilyForItem(Item item, MaterialFamily family) {
        List<Item> candidates = family == MaterialFamily.STONE_STAIRS ? STONE_STAIRS : STONE_SLABS;
        return containsItem(candidates, item) ? family : null;
    }

    private static boolean isWoodNamedItem(Item item, String suffix) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
        if (key == null || !key.getPath().endsWith(suffix)) {
            return false;
        }

        String path = key.getPath().substring(0, key.getPath().length() - suffix.length());
        return WOOD_PREFIXES.contains(path);
    }

    private static boolean containsItem(List<Item> items, Item item) {
        for (Item candidate : items) {
            if (candidate == item) {
                return true;
            }
        }
        return false;
    }

    private static Optional<PlacementMaterial> resolvePottedPlantPlacement(PlayerNpcEntity playerNpc, BlockState targetState) {
        ItemStack pot = playerNpc.consumeInventoryItem(Items.FLOWER_POT, 1).orElse(ItemStack.EMPTY);
        if (pot.isEmpty()) {
            return Optional.empty();
        }

        ItemStack plant = playerNpc.consumeInventoryItem(PlayerNpcBuildMaterialUtil::isPottablePlantStack, 1).orElse(ItemStack.EMPTY);
        if (plant.isEmpty()) {
            InventoryUtils.addItem(playerNpc, pot);
            return Optional.empty();
        }

        return Optional.of(new PlacementMaterial(targetState, pot, List.of(plant)));
    }

    private static boolean hasFlowerPot(PlayerNpcEntity playerNpc) {
        return InventoryUtils.hasItem(playerNpc, Items.FLOWER_POT);
    }

    private static boolean hasPottablePlant(PlayerNpcEntity playerNpc) {
        return InventoryUtils.hasItem(playerNpc, PlayerNpcBuildMaterialUtil::isPottablePlantStack);
    }

    private static boolean hasSmeltableSand(PlayerNpcEntity playerNpc) {
        return InventoryUtils.hasItem(playerNpc, stack -> stack.is(Items.SAND) || stack.is(Items.RED_SAND));
    }

    private static boolean isFlowerStack(ItemStack stack) {
        return !stack.isEmpty() && containsItem(FLOWERS, stack.getItem());
    }

    private static boolean isPottablePlantStack(ItemStack stack) {
        return !stack.isEmpty() && containsItem(POTTABLE_PLANTS, stack.getItem());
    }

    private static boolean isPottedPlant(BlockState state) {
        if (!(state.getBlock() instanceof FlowerPotBlock) || state.is(Blocks.FLOWER_POT)) {
            return false;
        }

        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        return key != null && key.getPath().startsWith("potted_");
    }

    private static Optional<GlassProductionNeed> findGlassProductionNeed(ServerLevel serverLevel, PlayerNpcEntity playerNpc) {
        Optional<PlayerNpcHomeUtil.HomeArea> home = PlayerNpcHomeUtil.getHome(playerNpc);
        if (home.isEmpty()) {
            return Optional.empty();
        }

        Optional<PlayerNpcBuildLayout> layout = PlayerNpcHomeUtil.getHomeLayoutId(playerNpc)
                .flatMap(PlayerNpcBuildLayoutLoader::getLayout);
        if (layout.isEmpty()) {
            return Optional.empty();
        }

        BlockPos origin = home.get().origin();
        for (PlayerNpcBuildLayout.RelativeBlock block : layout.get().blocks()) {
            if (block.optional() || matches(serverLevel.getBlockState(block.toWorld(origin)), block.state())) {
                continue;
            }

            MaterialFamily family = familyForState(block.state());
            if ((family == MaterialFamily.GLASS_BLOCKS || family == MaterialFamily.GLASS_PANES)
                    && !hasMaterialFor(serverLevel, playerNpc, block, origin)) {
                return Optional.of(new GlassProductionNeed(requiredGlassFor(family)));
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static int requiredGlassFor(MaterialFamily family) {
        return family == MaterialFamily.GLASS_PANES ? GLASS_PANE_CRAFT_INPUT : 1;
    }

    private static int countGlassSmeltingInput(PlayerNpcEntity playerNpc) {
        return countHeldAndInventoryItems(playerNpc, PlayerNpcBuildMaterialUtil::isGlassSmeltingInput);
    }

    private static int countFamilyItems(PlayerNpcEntity playerNpc, MaterialFamily family) {
        return countHeldAndInventoryItems(playerNpc, stack -> familyForItem(stack.getItem()) == family);
    }

    private static int countHeldAndInventoryItems(PlayerNpcEntity playerNpc, java.util.function.Predicate<ItemStack> matcher) {
        int count = 0;
        ItemStack mainHand = playerNpc.getMainHandItem();
        if (!mainHand.isEmpty() && matcher.test(mainHand)) {
            count += mainHand.getCount();
        }
        ItemStack offhand = playerNpc.getOffhandItem();
        if (!offhand.isEmpty() && matcher.test(offhand)) {
            count += offhand.getCount();
        }

        SimpleContainer inventory = playerNpc.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && matcher.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static boolean canCraftGlassPaneFromAnyGlass(SimpleContainer inventory) {
        return PlayerNpcCraftingUtil.countItem(inventory, stack -> familyForItem(stack.getItem()) == MaterialFamily.GLASS_BLOCKS) >= GLASS_PANE_CRAFT_INPUT;
    }

    private static boolean tryCraftGlassPaneFromAnyGlass(SimpleContainer inventory) {
        if (!PlayerNpcCraftingUtil.consumeItem(inventory, stack -> familyForItem(stack.getItem()) == MaterialFamily.GLASS_BLOCKS, GLASS_PANE_CRAFT_INPUT)) {
            return false;
        }
        return InventoryUtils.addItem(inventory, new ItemStack(Items.GLASS_PANE, 16));
    }

    private static boolean sharedPropertiesMatch(BlockState targetState, BlockState existingState) {
        for (Property<?> targetProperty : targetState.getProperties()) {
            Property<?> existingProperty = existingState.getBlock().getStateDefinition().getProperty(targetProperty.getName());
            if (existingProperty == null) {
                continue;
            }
            if (!propertyValueName(targetState, targetProperty).equals(propertyValueName(existingState, existingProperty))) {
                return false;
            }
        }
        return true;
    }

    private static BlockState copySharedProperties(BlockState source, BlockState replacement) {
        BlockState result = replacement;
        for (Property<?> sourceProperty : source.getProperties()) {
            Property<?> replacementProperty = result.getBlock().getStateDefinition().getProperty(sourceProperty.getName());
            if (replacementProperty == null) {
                continue;
            }
            String valueName = propertyValueName(source, sourceProperty);
            result = setPropertyByName(result, replacementProperty, valueName);
        }
        return result;
    }

    private static String propertyValueName(BlockState state, Property<?> property) {
        return propertyValueNameUnchecked(state, property);
    }

    private static <T extends Comparable<T>> String propertyValueNameUnchecked(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> BlockState setPropertyByName(BlockState state, Property<T> property, String valueName) {
        Optional<T> value = property.getValue(valueName);
        return value.map(t -> state.setValue(property, t)).orElse(state);
    }

    public record PlacementMaterial(BlockState state, ItemStack consumedItem, List<ItemStack> extraConsumedItems) {
        public PlacementMaterial(BlockState state, ItemStack consumedItem) {
            this(state, consumedItem, List.of());
        }
    }

    private record GlassProductionNeed(int requiredGlass) {
    }

    private enum MaterialFamily {
        PLANKS("any planks"),
        LOGS("any log"),
        WOODEN_DOORS("any wooden door"),
        WOODEN_TRAPDOORS("any wooden trapdoor"),
        WOODEN_FENCES("any wooden fence"),
        WOODEN_FENCE_GATES("any wooden fence gate"),
        WOODEN_STAIRS("any wooden stairs"),
        WOODEN_SLABS("any wooden slab"),
        BEDS("any bed"),
        CARPETS("any carpet"),
        COBBLESTONE_LIKE("cobblestone-like block"),
        LOOSE_FILL("dirt/sand/gravel"),
        STONE_MASONRY("stone masonry block"),
        STONE_STAIRS("any stone stairs"),
        STONE_SLABS("any stone slab"),
        WOODEN_BUTTONS("any wooden button"),
        STONE_BUTTONS("any stone button"),
        WOODEN_PRESSURE_PLATES("any wooden pressure plate"),
        STONE_OR_METAL_PRESSURE_PLATES("any stone or metal pressure plate"),
        GLASS_BLOCKS("any glass"),
        GLASS_PANES("any glass pane"),
        FLOWERS("any flower"),
        FLOWER_POTS("flower pot"),
        POTTED_FLOWERS("potted flower");

        private final String displayName;

        MaterialFamily(String displayName) {
            this.displayName = displayName;
        }
    }
}
