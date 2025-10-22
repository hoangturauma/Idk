package eclipse.euphoriacompanion.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for tracking block properties found in shader packs.
 * This improved implementation focuses on the properties actually used in block.properties files
 * and correctly identifies missing properties.
 */
public class BlockPropertyRegistry {
    private static final String REGISTRY_FILENAME = "block_properties.json";
    // Singleton instance for global access
    private static BlockPropertyRegistry instance;
    // Map of block identifiers to their properties and used values
    // Key: Normalized block ID (e.g., "minecraft:oak_fence")
    // Value: Map of property name to set of used values from block.properties
    private final Map<String, Map<String, Set<String>>> usedBlockProperties = new ConcurrentHashMap<>();
    // Cache for property values from the game
    // Key: Normalized block ID (e.g., "minecraft:oak_fence")
    // Value: Map of property name to all possible values from the game
    private final Map<String, Map<String, Set<String>>> gameBlockProperties = new ConcurrentHashMap<>();
    private final Path gameDir;
    private final Gson gson;

    /**
     * Private constructor - use getInstance() instead
     */
    private BlockPropertyRegistry(Path gameDir) {
        this.gameDir = gameDir;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
        loadRegistry();
    }

    /**
     * Get the singleton instance of the registry
     * If gameDir is different from the existing instance, a new instance is created
     */
    public static synchronized BlockPropertyRegistry getInstance(Path gameDir) {
        if (instance == null || !instance.gameDir.equals(gameDir)) {
            instance = new BlockPropertyRegistry(gameDir);
        }
        return instance;
    }
    
    /**
     * Reset the singleton instance to force a completely new registry to be created
     * This ensures all data is reloaded from disk and no stale data persists
     */
    public static synchronized void resetInstance() {
        instance = null;
        EuphoriaCompanion.LOGGER.debug("Reset block property registry instance");
    }

    /**
     * Loads the registry from the JSON file if it exists
     */
    private void loadRegistry() {
        File registryFile = gameDir.resolve(REGISTRY_FILENAME).toFile();

        if (registryFile.exists()) {
            try (FileReader reader = new FileReader(registryFile)) {
                Map<String, Map<String, Set<String>>> loadedRegistry = gson.fromJson(reader, new TypeToken<Map<String, Map<String, Set<String>>>>() {
                }.getType());

                if (loadedRegistry != null) {
                    usedBlockProperties.putAll(loadedRegistry);
                    EuphoriaCompanion.LOGGER.debug("Loaded property registry with {} blocks", usedBlockProperties.size());
                }
            } catch (IOException e) {
                EuphoriaCompanion.LOGGER.error("Failed to load property registry: {}", e.getMessage());
            }
        } else {
            EuphoriaCompanion.LOGGER.debug("No existing property registry found, creating new one");
        }
    }

    /**
     * Saves the registry to the JSON file
     */
    public void saveRegistry() {
        File registryFile = gameDir.resolve(REGISTRY_FILENAME).toFile();

        try (FileWriter writer = new FileWriter(registryFile)) {
            gson.toJson(usedBlockProperties, writer);
            EuphoriaCompanion.LOGGER.debug("Saved property registry with {} blocks", usedBlockProperties.size());
        } catch (IOException e) {
            EuphoriaCompanion.LOGGER.error("Failed to save property registry: {}", e.getMessage());
        }
    }

    /**
     * Clears all caches and data
     */
    public void clearAll() {
        usedBlockProperties.clear();
        gameBlockProperties.clear();
        EuphoriaCompanion.LOGGER.debug("Cleared all property registry data");
    }

    /**
     * Registers a property and value used in block.properties
     * Normalizes property values to lowercase for consistent matching
     *
     * @param blockId       The block identifier
     * @param propertyName  The property name
     * @param propertyValue The property value
     */
    public void registerUsedProperty(String blockId, String propertyName, String propertyValue) {
        // Ensure block ID has namespace
        if (!blockId.contains(":")) {
            blockId = "minecraft:" + blockId;
        }

        // Normalize property value to lowercase
        String normalizedValue = propertyValue.toLowerCase();

        // Add to registry of used properties
        Map<String, Set<String>> blockProps = usedBlockProperties.computeIfAbsent(blockId, k -> new ConcurrentHashMap<>());
        Set<String> values = blockProps.computeIfAbsent(propertyName, k -> new HashSet<>());

        if (values.add(normalizedValue)) {
            EuphoriaCompanion.LOGGER.debug("Registered used property {}={} for block {} (normalized from {})", propertyName, normalizedValue, blockId, propertyValue);
        }
    }

    /**
     * Register block with all its properties from a block identifier string
     *
     * @param blockIdWithProperties Full block ID with properties (e.g., "minecraft:oak_fence:waterlogged=true")
     */
    public void registerBlockWithProperties(String blockIdWithProperties) {
        // Use BlockPropertyExtractor to parse the ID and extract properties
        BlockPropertyExtractor.ParsedBlockIdentifier parsed = BlockPropertyExtractor.parseBlockIdentifier(blockIdWithProperties);

        // Skip if no properties found
        if (parsed.properties().isEmpty()) {
            return;
        }

        // Get normalized block name
        String blockId = parsed.blockName();
        if (!blockId.contains(":")) {
            blockId = "minecraft:" + blockId;
        }

        // Register each property
        for (BlockPropertyExtractor.BlockStateProperty property : parsed.properties()) {
            registerUsedProperty(blockId, property.name(), property.value());
        }
    }

    /**
     * Process all shader blocks to build the used properties registry
     *
     * @param shaderBlocks Set of all block identifiers from shader files
     */
    public void processAllShaderBlocks(Set<String> shaderBlocks) {
        // Clear existing data first
        clearAll();

        // Process each block
        for (String blockId : shaderBlocks) {
            // Skip blocks without properties
            if (!blockId.contains("=")) {
                continue;
            }

            registerBlockWithProperties(blockId);
        }

        EuphoriaCompanion.LOGGER.info("Processed {} shader blocks, found {} blocks with properties", shaderBlocks.size(), usedBlockProperties.size());
    }

    /**
     * Gets all possible property values for a block from the game
     *
     * @param blockId The normalized block identifier
     * @return Map of property names to all possible values
     */
    public Map<String, Set<String>> getAllGamePropertyValues(String blockId) {
        // Ensure block ID has namespace
        if (!blockId.contains(":")) {
            blockId = "minecraft:" + blockId;
        }

        // Check cache first
        if (gameBlockProperties.containsKey(blockId)) {
            return new HashMap<>(gameBlockProperties.get(blockId));
        }

        // Load from game
        Map<String, Set<String>> result = new HashMap<>();

        // Get block from registry
        String[] parts = blockId.split(":", 2);
        if (parts.length != 2) {
            return result;
        }

        // Use get for 1.21+ compatibility (returns default if not found)
        Block block = Registries.BLOCK.get(Identifier.of(parts[0], parts[1]));
        if (block == null || block == Blocks.AIR) {
            EuphoriaCompanion.LOGGER.debug("Block not found in registry: {}", blockId);
            return result;
        }

        try {
            // Extract all properties from block's state manager
            for (Property<?> property : block.getStateManager().getProperties()) {
                String propertyName = property.getName();
                Set<String> values = new HashSet<>();

                // Get all possible values
                Collection<? extends Comparable<?>> possibleValues = BlockPropertyExtractor.getPropertyValuesViaReflection(property);

                for (Comparable<?> value : possibleValues) {
                    // Normalize to lowercase for consistent matching
                    values.add(value.toString().toLowerCase());
                }

                result.put(propertyName, values);
            }

            // Cache the result
            gameBlockProperties.put(blockId, result);

        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Error getting properties for block {}: {}", blockId, e.getMessage());
        }

        return result;
    }

    /**
     * Checks if a given block state with properties exists in the game
     * Normalizes the property value to lowercase for consistent matching
     *
     * @param blockId       Base block ID
     * @param propertyName  Property name
     * @param propertyValue Property value
     * @return true if this is a valid property state
     */
    public boolean isValidPropertyState(String blockId, String propertyName, String propertyValue) {
        // Ensure block ID has namespace
        if (!blockId.contains(":")) {
            blockId = "minecraft:" + blockId;
        }

        // Normalize property value to lowercase
        String normalizedValue = propertyValue.toLowerCase();

        // Get all properties for this block
        Map<String, Set<String>> allProps = getAllGamePropertyValues(blockId);

        // Check if property exists
        if (!allProps.containsKey(propertyName)) {
            return false;
        }

        // Check if value is valid
        return allProps.get(propertyName).contains(normalizedValue);
    }

    /**
     * Finds all missing property values for all registered blocks
     *
     * @return List of missing property states in the format "blockId:propertyName=propertyValue"
     */
    public List<String> findAllMissingPropertyStates() {
        List<String> missingStates = new ArrayList<>();

        // For each block with used properties
        for (Map.Entry<String, Map<String, Set<String>>> entry : usedBlockProperties.entrySet()) {
            String blockId = entry.getKey();
            Map<String, Set<String>> usedProps = entry.getValue();

            // Get all possible property values from the game
            Map<String, Set<String>> allProps = getAllGamePropertyValues(blockId);

            // Skip if block not found in game
            if (allProps.isEmpty()) {
                EuphoriaCompanion.LOGGER.debug("Skipping block not found in game: {}", blockId);
                continue;
            }

            EuphoriaCompanion.LOGGER.debug("Finding missing properties for {}", blockId);

            // For each property used in block.properties
            for (Map.Entry<String, Set<String>> propEntry : usedProps.entrySet()) {
                String propName = propEntry.getKey();
                Set<String> usedValues = propEntry.getValue();

                // Skip if property not found in game
                if (!allProps.containsKey(propName)) {
                    EuphoriaCompanion.LOGGER.debug("Property {} not found on block {}", propName, blockId);
                    continue;
                }

                // Get all possible values for this property
                Set<String> allValues = allProps.get(propName);

                // Find missing values (values in game but not in block.properties)
                Set<String> missingValues = new HashSet<>(allValues);
                missingValues.removeAll(usedValues);

                // Add missing states to the list
                for (String missingValue : missingValues) {
                    String missingState = blockId + ":" + propName + "=" + missingValue;

                    // Verify this is actually a valid state (some properties may have constraints)
                    if (isValidPropertyState(blockId, propName, missingValue)) {
                        missingStates.add(missingState);
                        EuphoriaCompanion.LOGGER.debug("Found missing property state: {}", missingState);
                    } else {
                        EuphoriaCompanion.LOGGER.debug("Skipping invalid property state: {}", missingState);
                    }
                }
            }
        }

        // Sort the list for consistent output
        Collections.sort(missingStates);

        EuphoriaCompanion.LOGGER.info("Found {} missing property states in total", missingStates.size());
        return missingStates;
    }

}