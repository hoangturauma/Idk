package eclipse.euphoriacompanion.util;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper utility for working with block render layers and categories.
 */
public class BlockRenderHelper {
    // Categories we're tracking
    private static final BlockRenderCategory TRANSLUCENT = BlockRenderCategory.TRANSLUCENT;
    private static final BlockRenderCategory SOLID = BlockRenderCategory.SOLID;
    private static final BlockRenderCategory LIGHT_EMITTING = BlockRenderCategory.LIGHT_EMITTING;
    private static final BlockRenderCategory FULL_CUBE = BlockRenderCategory.FULL_CUBE;
    private static final BlockRenderCategory BLOCK_ENTITY = BlockRenderCategory.BLOCK_ENTITY;

    // Cache to avoid repeated lookups
    private static final Map<Block, Set<BlockRenderCategory>> blockCategoriesCache = new ConcurrentHashMap<>();

    // Lists to store blocks by category
    private static final List<Block> translucentBlocks = new ArrayList<>();
    private static final List<Block> solidBlocks = new ArrayList<>();
    private static final List<Block> lightEmittingBlocks = new ArrayList<>();
    private static final List<Block> fullCubeBlocks = new ArrayList<>();
    private static final List<Block> blockEntityBlocks = new ArrayList<>();

    // For caching the model based full-cube checks
    private static final Map<Block, Boolean> fullCubeModelCache = new ConcurrentHashMap<>();

    private static boolean isFullCube(Block block, BlockState state) {
        // Check cache first
        if (fullCubeModelCache.containsKey(block)) {
            return fullCubeModelCache.get(block);
        }

        // Basic checks that apply to all versions

        // We'll check if it's a block entity later, after other checks
        // This allows block entities that are visually full cubes to be classified correctly

        // A full cube must be opaque
        if (!state.isOpaque()) {
            fullCubeModelCache.put(block, false);
            return false;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null) {
            // Can't check without a client/world, use a conservative fallback
            fullCubeModelCache.put(block, false);
            return false;
        }

        // Check 1: All sides must be solid full squares
        // This specifically handles stairs, slabs, etc. which have some sides that aren't full
        try {
            for (Direction direction : Direction.values()) {
                try {
                    // If any side is not a full solid square, it's not a full cube
                    if (!state.isSideSolidFullSquare(client.world, BlockPos.ORIGIN, direction)) {
                        fullCubeModelCache.put(block, false);
                        return false;
                    }
                } catch (Exception e) {
                    // If this check fails for any direction, assume it's not a full cube
                    fullCubeModelCache.put(block, false);
                    return false;
                }
            }
        } catch (Exception generalException) {
            // If the whole check fails (method doesn't exist), move to the next check
            EuphoriaCompanion.LOGGER.debug("isSideSolidFullSquare check failed, moving to light check");
        }

        // Check 2: Does the block, block light in all 6 directions
        for (Direction direction : Direction.values()) {
            // If light passes through any direction, the block is not a full cube
            if (doesLightPassThroughDirection(state, direction, client)) {
                fullCubeModelCache.put(block, false);
                return false;
            }
        }

        // If we reach here, the block blocks light in all 6 directions and all sides are full squares
        // This is our complete definition of a "full cube"
        fullCubeModelCache.put(block, true);
        return true;
    }

    /**
     * Checks if light passes through a specific face of a block.
     *
     * @param state     The block state to check
     * @param direction The direction/face to check
     * @param client    The Minecraft client instance
     * @return true if light passes through, false if blocked
     */
    private static boolean doesLightPassThroughDirection(BlockState state, Direction direction, MinecraftClient client) {
        try {
            // Method 1: Use version-specific opacity check safely
            int opacity = 15; // Default to max opacity

            // Use reflection to safely handle method differences between versions
            if (MCVersionChecker.isMinecraft1212OrLater()) {
                // For MC 1.21.2+, try to use parameter-less getOpacity method
                try {
                    // Get the method reference and invoke it
                    java.lang.reflect.Method opacityMethod = state.getClass().getMethod("getOpacity");
                    Object result = opacityMethod.invoke(state);
                    if (result instanceof Integer) {
                        opacity = (Integer) result;
                    }
                } catch (NoSuchMethodException ignored) {
                    // Method doesn't exist, continue with other methods
                    EuphoriaCompanion.LOGGER.debug("No parameter-less getOpacity method found");
                }
            } else {
                // For earlier versions, try the world+pos version but use reflection to avoid errors
                try {
                    // Use reflection to safely check if this method exists with the right parameters
                    Method opacityMethod = getMethod(state);

                    // If we found a valid method, use it
                    if (opacityMethod != null) {
                        try {
                            // Try calling through reflection to avoid direct method reference
                            Object result = opacityMethod.invoke(state, client.world, BlockPos.ORIGIN);
                            if (result instanceof Integer) {
                                opacity = (Integer) result;
                            }
                        } catch (Exception methodInvokeError) {
                            // Method exists but couldn't be called properly
                            // Try direct call without parameters for 1.21+
                            try {
                                opacity = state.getOpacity();
                            } catch (Exception directCallError) {
                                // Both approaches failed, keep default opacity
                            }
                        }
                    }
                } catch (Exception e) {
                    // Reflection approach failed entirely, try direct call without parameters for 1.21+
                    try {
                        opacity = state.getOpacity();
                    } catch (Exception ignored) {
                        // Direct call failed too, keep default opacity
                    }
                }
            }

            // Max opacity (15) means no light passes through
            if (opacity < 15) {
                return true; // Light passes through
            }

            // Method 2: Check if the block is translucent
            // In 1.21+, check render layer by string representation
            try {
                var renderLayer = RenderLayers.getBlockLayer(state);
                if (renderLayer != null && renderLayer.toString().contains("translucent")) {
                    return true; // Translucent blocks let light through
                }
            } catch (Exception ignored) {
                // Continue if this check fails
            }

            // Method 3: Check if the side is a solid full square using safe reflection
            try {
                // Check if the method exists first
                boolean methodExists = isMethodExists(state);

                // If the method exists, try to call it
                if (methodExists) {
                    if (!state.isSideSolidFullSquare(client.world, BlockPos.ORIGIN, direction)) {
                        return true; // If the side isn't a full square, light probably passes
                    }
                }
            } catch (Exception ignored) {
                // Any failure means we skip this check
            }

            // Return false if we've made it through all checks without finding light passage
            return false;
        } catch (Exception e) {
            // In case of any uncaught error, assume light might pass (safer assumption)
            EuphoriaCompanion.LOGGER.debug("Error checking light passage in direction {}: {}", direction, e.getMessage());
            return true;
        }
    }

    private static boolean isMethodExists(BlockState state) {
        boolean methodExists = false;
        try {
            for (Method method : state.getClass().getMethods()) {
                if (method.getName().equals("isSideSolidFullSquare") && method.getParameterCount() >= 3) {
                    methodExists = true;
                    break;
                }
            }
        } catch (Exception ignored) {
            // Reflection failed, assume method doesn't exist
        }
        return methodExists;
    }

    private static @Nullable Method getMethod(BlockState state) {
        Method opacityMethod = null;
        try {
            // Try to find the method with reflection
            for (Method method : state.getClass().getMethods()) {
                if (method.getName().equals("getOpacity") && method.getParameterCount() > 0) {
                    opacityMethod = method;
                    break;
                }
            }
        } catch (Exception ignored) {
            // Method search failed, continue
        }
        return opacityMethod;
    }

    /**
     * Gets the primary render category of a block.
     * Used for backwards compatibility.
     *
     * @param block The block to categorize
     * @return The primary category (TRANSLUCENT takes precedence over others)
     */
    public static BlockRenderCategory getRenderCategory(Block block) {
        Set<BlockRenderCategory> categories = getCategories(block);
        if (categories.contains(LIGHT_EMITTING)) {
            return LIGHT_EMITTING;
        } else if (categories.contains(TRANSLUCENT)) {
            return TRANSLUCENT;
        } else if (categories.contains(FULL_CUBE)) {
            return FULL_CUBE;
        } else if (categories.contains(BLOCK_ENTITY)) {
            return BLOCK_ENTITY;
        } else {
            return SOLID;
        }
    }

    /**
     * Gets all categories a block belongs to.
     * A block can be in multiple categories (e.g., both translucent and light-emitting).
     *
     * @param block The block to categorize
     * @return Set of categories the block belongs to
     */
    public static Set<BlockRenderCategory> getCategories(Block block) {
        // Check cache first
        if (blockCategoriesCache.containsKey(block)) {
            return blockCategoriesCache.get(block);
        }

        Set<BlockRenderCategory> categories = new HashSet<>();

        // Get the block's default state
        BlockState state = block.getDefaultState();

        // Check for translucency
        try {
            var renderLayer = RenderLayers.getBlockLayer(state);
            boolean isTranslucent = renderLayer != null && renderLayer.toString().contains("translucent");
            if (isTranslucent) {
                categories.add(TRANSLUCENT);
            } else {
                categories.add(SOLID);
            }
        } catch (Exception e) {
            // If render layer check fails, default to SOLID
            categories.add(SOLID);
        }

        // Check for light emission
        if (state.getLuminance() > 0) {
            categories.add(LIGHT_EMITTING);
        }

        // Check if it's a full cube by examining its model
        if (isFullCube(block, state)) {
            categories.add(FULL_CUBE);
        }

        // Check if it has a block entity
        boolean hasBlockEntity;
        try {
            hasBlockEntity = state.hasBlockEntity();
            if (hasBlockEntity) {
                categories.add(BLOCK_ENTITY);
            }
        } catch (Exception ignored) {
            // Continue if this check fails
        }

        // Cache the result
        blockCategoriesCache.put(block, categories);
        return categories;
    }

    /**
     * Categorizes all registered blocks by their properties.
     * Should be called after the block registry is frozen.
     */
    public static void categorizeAllBlocks() {
        // No starting message - removed to reduce log spam

        // Clear all caches
        clearCaches();

        // Counters for each category
        Map<BlockRenderCategory, AtomicInteger> counts = new HashMap<>();
        for (BlockRenderCategory category : BlockRenderCategory.values()) {
            counts.put(category, new AtomicInteger());
        }

        // For special combinations
        AtomicInteger translucentAndLightCount = new AtomicInteger();
        AtomicInteger fullCubeAndLightCount = new AtomicInteger();

        // Process blocks using stream for better efficiency
        Registries.BLOCK.forEach(block -> {
            // Get all categories for the block
            Set<BlockRenderCategory> categories = getCategories(block);

            // Add to category lists and increment counters
            for (BlockRenderCategory category : categories) {
                counts.get(category).incrementAndGet();

                // Add to the corresponding list
                if (category == TRANSLUCENT) {
                    translucentBlocks.add(block);
                } else if (category == SOLID) {
                    solidBlocks.add(block);
                } else if (category == LIGHT_EMITTING) {
                    lightEmittingBlocks.add(block);
                } else if (category == FULL_CUBE) {
                    fullCubeBlocks.add(block);
                } else if (category == BLOCK_ENTITY) {
                    blockEntityBlocks.add(block);
                }
            }

            // Track special combinations
            if (categories.contains(TRANSLUCENT) && categories.contains(LIGHT_EMITTING)) {
                translucentAndLightCount.incrementAndGet();
            }

            if (categories.contains(FULL_CUBE) && categories.contains(LIGHT_EMITTING)) {
                fullCubeAndLightCount.incrementAndGet();
            }
        });
    }

    /**
     * Clears all internal caches.
     * Should be called when the block registry changes.
     */
    public static void clearCaches() {
        blockCategoriesCache.clear();
        fullCubeModelCache.clear();
        translucentBlocks.clear();
        solidBlocks.clear();
        lightEmittingBlocks.clear();
        fullCubeBlocks.clear();
        blockEntityBlocks.clear();
    }

    /**
     * Gets all blocks in a specific render category.
     *
     * @param category The category to get blocks for
     * @return List of blocks in that category
     */
    public static List<Block> getBlocksInCategory(BlockRenderCategory category) {
        if (category == TRANSLUCENT) {
            return Collections.unmodifiableList(translucentBlocks);
        } else if (category == LIGHT_EMITTING) {
            return Collections.unmodifiableList(lightEmittingBlocks);
        } else if (category == FULL_CUBE) {
            return Collections.unmodifiableList(fullCubeBlocks);
        } else if (category == BLOCK_ENTITY) {
            return Collections.unmodifiableList(blockEntityBlocks);
        } else {
            return Collections.unmodifiableList(solidBlocks);
        }
    }

    /**
     * Gets a map of all blocks organized by render category.
     *
     * @return Map of categories to block lists
     */
    public static Map<BlockRenderCategory, List<Block>> getAllBlocksByCategory() {
        Map<BlockRenderCategory, List<Block>> result = new HashMap<>();
        result.put(TRANSLUCENT, Collections.unmodifiableList(translucentBlocks));
        result.put(SOLID, Collections.unmodifiableList(solidBlocks));
        result.put(LIGHT_EMITTING, Collections.unmodifiableList(lightEmittingBlocks));
        result.put(FULL_CUBE, Collections.unmodifiableList(fullCubeBlocks));
        result.put(BLOCK_ENTITY, Collections.unmodifiableList(blockEntityBlocks));
        return result;
    }

    /**
     * Gets a map of block identifiers organized by category.
     * Useful for serialization.
     *
     * @return Map of categories to block identifier lists
     */
    public static Map<String, List<String>> getAllBlockIdsByCategory() {
        Map<String, List<String>> result = new HashMap<>();

        // Use a more efficient approach with streams
        for (BlockRenderCategory category : BlockRenderCategory.values()) {
            List<String> blockIds = getBlocksInCategory(category).stream().map(block -> Registries.BLOCK.getId(block).toString()).collect(java.util.stream.Collectors.toList());

            result.put(category.name(), blockIds);
        }

        return result;
    }
}