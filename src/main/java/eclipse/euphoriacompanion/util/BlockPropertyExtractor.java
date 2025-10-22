package eclipse.euphoriacompanion.util;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for extracting and comparing block properties from block.properties files
 * and in-game block states.
 */
public class BlockPropertyExtractor {
    // Cache for parsed properties from files
    private static final Map<Path, Map<String, Set<BlockStateProperty>>> filePropertyCache = new ConcurrentHashMap<>();

    // Cache for extracted properties from in-game blocks
    private static final Map<Block, Set<BlockStateProperty>> blockPropertyCache = new ConcurrentHashMap<>();

    // Cache for all possible property values for a block's property
    private static final Map<Block, Map<String, Set<String>>> blockPropertyValuesCache = new ConcurrentHashMap<>();

    /**
     * Parses a block.properties file and extracts block properties
     *
     * @param propertiesFile The path to the block.properties file
     * @return A map of block identifiers to their properties
     */
    public static Map<String, Set<BlockStateProperty>> parsePropertiesFile(Path propertiesFile) {
        Map<String, Set<BlockStateProperty>> result = new HashMap<>();
        EuphoriaCompanion.LOGGER.debug("Parsing properties file: {}", propertiesFile);

        try (BufferedReader reader = Files.newBufferedReader(propertiesFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Skip comments and empty lines
                if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                    continue;
                }

                // Parse the line (format: block_identifier = shader_value)
                // Split at the first equals sign which separates block identifier from shader value
                String[] mainParts = line.split("=", 2);
                if (mainParts.length < 2) {
                    continue; // Invalid line format
                }

                String blockIdentifier = mainParts[0].trim();

                // Process the block identifier which could be:
                // 1. modName:blockName (simple block)
                // 2. blockName (simple block without mod)
                // 3. modName:blockName:prop1=val1:prop2=val2 (block with properties)
                // 4. blockName:prop1=val1:prop2=val2 (block with properties without mod)

                // Parse block identifier to extract the base block name and properties
                ParsedBlockIdentifier parsed = parseBlockIdentifier(blockIdentifier);

                // Add to result map
                result.computeIfAbsent(parsed.blockName, k -> new HashSet<>()).addAll(parsed.properties);

                EuphoriaCompanion.LOGGER.debug("Parsed block: {} with {} properties", parsed.blockName, parsed.properties.size());
                for (BlockStateProperty prop : parsed.properties) {
                    EuphoriaCompanion.LOGGER.debug("  - Property: {}={}", prop.name(), prop.value());
                }
            }
        } catch (IOException e) {
            EuphoriaCompanion.LOGGER.error("Error parsing properties file: {}", propertiesFile, e);
        }

        // We'll still cache the result, but the cache is cleared at the start of each processing session
        // to ensure we always get fresh data when the user edits block.properties
        filePropertyCache.put(propertiesFile, result);
        return result;
    }

    /**
     * Parses a block identifier string to extract block name and properties
     *
     * @param blockIdentifier The block identifier string
     * @return A ParsedBlockIdentifier containing block name and properties
     */
    public static ParsedBlockIdentifier parseBlockIdentifier(String blockIdentifier) {
        Set<BlockStateProperty> properties = new HashSet<>();
        String blockName = blockIdentifier;

        // Check if we have any properties (identified by having = in the string)
        if (blockIdentifier.contains("=")) {
            // Find the position of the first property indicator (=)
            int propertyStartPos = blockIdentifier.indexOf("=");

            // Move back to find the colon that separates blockName from property
            int colonBeforeProperty = blockIdentifier.lastIndexOf(":", propertyStartPos);

            if (colonBeforeProperty != -1) {
                // Extract the block name part (everything before the properties)
                blockName = blockIdentifier.substring(0, colonBeforeProperty);

                // Extract the properties part
                String propertiesStr = blockIdentifier.substring(colonBeforeProperty + 1);
                properties = parsePropertyString(propertiesStr);
            }
        }

        // Debug log only if there's something interesting to report
        if (!properties.isEmpty()) {
            EuphoriaCompanion.LOGGER.debug("Parsed block '{}' with {} properties", blockName, properties.size());
        }

        return new ParsedBlockIdentifier(blockName, properties);
    }

    /**
     * Parses a property string into a set of BlockStateProperty objects
     *
     * @param propertyString The property string (format: prop1=val1:prop2=val2)
     * @return A set of BlockStateProperty objects
     */
    public static Set<BlockStateProperty> parsePropertyString(String propertyString) {
        Set<BlockStateProperty> properties = new HashSet<>();

        // Split the property string by colons
        String[] propertyParts = propertyString.split(":");

        for (String part : propertyParts) {
            // Each part should be in the format key=value
            if (part.contains("=")) {
                String[] keyValue = part.split("=", 2);
                if (keyValue.length == 2) {
                    String propName = keyValue[0].trim();
                    String propValue = keyValue[1].trim().toLowerCase(); // Normalize to lowercase for consistent matching
                    properties.add(new BlockStateProperty(propName, propValue));
                }
            }
        }

        return properties;
    }

    /**
     * Extracts all possible state properties for a block
     *
     * @param block The block to extract properties from
     * @return A set of all possible BlockStateProperty combinations
     */
    public static Set<BlockStateProperty> extractBlockProperties(Block block) {
        // Check cache first
        if (blockPropertyCache.containsKey(block)) {
            return blockPropertyCache.get(block);
        }

        Set<BlockStateProperty> properties = new HashSet<>();

        try {
            // Get all possible block states
            Collection<BlockState> states = block.getStateManager().getStates();
            EuphoriaCompanion.LOGGER.debug("Block {} has {} possible states", Registries.BLOCK.getId(block), states.size());

            // Extract properties from each possible state
            for (BlockState state : states) {
                // Get properties for this state
                Collection<Property<?>> stateProperties = state.getProperties();

                for (Property<?> property : stateProperties) {
                    String name = property.getName();
                    Comparable<?> valueObj = state.get(property);
                    String value = valueObj.toString().toLowerCase(); // Normalize to lowercase for consistent matching

                    // Add property to set
                    properties.add(new BlockStateProperty(name, value));
                }
            }
        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.debug("Error extracting properties for block {}: {}", Registries.BLOCK.getId(block), e.getMessage());
        }

        // Cache the result
        blockPropertyCache.put(block, properties);
        return properties;
    }

    /**
     * Compares a block's properties with properties from a properties file
     *
     * @param block          The block to compare
     * @param fileProperties Properties extracted from a properties file
     * @return true if all properties in fileProperties match the block's properties
     */
    public static boolean matchesFileProperties(Block block, Set<BlockStateProperty> fileProperties) {
        if (fileProperties == null || fileProperties.isEmpty()) {
            // No specific properties to match means any block state matches
            return true;
        }

        Set<BlockStateProperty> blockProperties = extractBlockProperties(block);

        // Check if the block supports all required properties
        for (BlockStateProperty fileProperty : fileProperties) {
            boolean found = false;
            for (BlockStateProperty blockProperty : blockProperties) {
                // Case-insensitive comparison for property values
                if (blockProperty.name().equals(fileProperty.name()) && blockProperty.value().equalsIgnoreCase(fileProperty.value())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }

        return true;
    }

    /**
     * Clears all caches
     */
    public static void clearCaches() {
        filePropertyCache.clear();
        blockPropertyCache.clear();
        blockPropertyValuesCache.clear();
    }

    /**
     * Get property values using reflection - works across different Minecraft versions
     *
     * @param property The block property
     * @return A collection of possible values
     */
    @SuppressWarnings("unchecked")
    public static Collection<? extends Comparable<?>> getPropertyValuesViaReflection(Property<?> property) {
        try {
            // Try different approaches to get the values

            // First attempt: Try using the obfuscated method name directly
            try {
                // This is the method name in obfuscated code
                java.lang.reflect.Method obfuscatedMethod = property.getClass().getMethod("method_11898");
                obfuscatedMethod.setAccessible(true);
                Object result = obfuscatedMethod.invoke(property);
                if (result instanceof Collection) {
                    return (Collection<? extends Comparable<?>>) result;
                }
            } catch (Exception ignore) {
                // Silently continue to the next approach
            }

            // Second attempt: Try known deobfuscated method names
            for (String methodName : new String[]{"getValues", "values", "getAllowedValues"}) {
                try {
                    java.lang.reflect.Method method = property.getClass().getMethod(methodName);
                    method.setAccessible(true);
                    Object result = method.invoke(property);
                    if (result instanceof Collection) {
                        return (Collection<? extends Comparable<?>>) result;
                    }
                } catch (Exception ignore) {
                    // Silently continue to the next method name
                }
            }

            // Third attempt: Search all public methods for one that returns a Collection or List
            for (java.lang.reflect.Method method : property.getClass().getMethods()) {
                if (method.getParameterCount() == 0 && (Collection.class.isAssignableFrom(method.getReturnType()) || List.class.isAssignableFrom(method.getReturnType()))) {
                    try {
                        method.setAccessible(true);
                        Object result = method.invoke(property);
                        if (result instanceof Collection) {
                            return (Collection<? extends Comparable<?>>) result;
                        }
                    } catch (Exception ignore) {
                        // Try the next method
                    }
                }
            }

            // Last resort: Try to get values from a sample BlockState
            try {
                // Get the name of the property
                String propertyName = property.getName();

                // Get the block that owns this property
                Block block = null;
                // Find a block that has this property
                for (Block testBlock : Registries.BLOCK) {
                    boolean hasProperty;
                    try {
                        hasProperty = testBlock.getStateManager().getProperties().contains(property);
                    } catch (Exception e) {
                        continue;
                    }

                    if (hasProperty) {
                        block = testBlock;
                        break;
                    }
                }

                if (block != null) {
                    // Get all values by examining all block states
                    Set<Comparable<?>> values = new HashSet<>();
                    for (BlockState state : block.getStateManager().getStates()) {
                        try {
                            for (Property<?> p : state.getProperties()) {
                                if (p.getName().equals(propertyName)) {
                                    Comparable<?> value = state.get(p);
                                    // Store the normalized lowercase value
                                    values.add(value.toString().toLowerCase());
                                }
                            }
                        } catch (Exception e) {
                            // Skip problematic states
                        }
                    }

                    if (!values.isEmpty()) {
                        return values;
                    }
                }
            } catch (Exception e) {
                // Last resort failed, continue to error handling
            }

            // If we get here, we've failed to find the values
            EuphoriaCompanion.LOGGER.error("Failed to get property values via any method");
            return Collections.emptyList();
        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Error getting property values: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Represents a block state property with name and value
     */
    public record BlockStateProperty(String name, String value) {

        @Override
        public String toString() {
            return name + "=" + value;
        }
    }

    /**
     * Helper class to store parsed block identifier information
     */
    public record ParsedBlockIdentifier(String blockName, Set<BlockStateProperty> properties) {
    }
}