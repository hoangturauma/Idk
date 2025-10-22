package eclipse.euphoriacompanion.report;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import eclipse.euphoriacompanion.util.BlockPropertyExtractor;
import eclipse.euphoriacompanion.util.BlockPropertyRegistry;
import eclipse.euphoriacompanion.util.BlockRenderCategory;
import eclipse.euphoriacompanion.util.BlockRenderHelper;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class BlockReporter {
    public static void processShaderBlocks(String shaderpackName, Set<String> shaderBlocks, Set<String> gameBlocks, Path logsDir, Map<String, List<String>> blocksByMod, Map<String, Set<BlockPropertyExtractor.BlockStateProperty>> blockPropertiesMap) {
        // Process blocks with properties
        Set<String> processedShaderBlocks = new HashSet<>();
        // Use the passed-in blockPropertiesMap to track extracted properties

        // Process each shader block and handle those with properties
        for (String shaderBlock : shaderBlocks) {
            if (shaderBlock.contains(":") && shaderBlock.contains("=")) {
                // This is likely a block with state properties
                try {
                    // Use our BlockPropertyExtractor to parse the block identifier
                    BlockPropertyExtractor.ParsedBlockIdentifier parsed = BlockPropertyExtractor.parseBlockIdentifier(shaderBlock);

                    String blockId = parsed.blockName();
                    Set<BlockPropertyExtractor.BlockStateProperty> properties = parsed.properties();

                    // Add minecraft namespace only if the block doesn't already have one
                    if (!blockId.contains(":")) {
                        blockId = "minecraft:" + blockId;
                        EuphoriaCompanion.LOGGER.debug("Added minecraft namespace to vanilla block: {}", blockId);
                    } else {
                        EuphoriaCompanion.LOGGER.debug("Block already has namespace, keeping as is: {}", blockId);
                    }

                    // Add to processed blocks
                    processedShaderBlocks.add(blockId);

                    // Store properties if any
                    if (!properties.isEmpty()) {
                        blockPropertiesMap.put(blockId, properties);
                    }
                } catch (Exception e) {
                    // If parsing fails, just add the block as is
                    processedShaderBlocks.add(shaderBlock);
                }
            } else {
                // Regular block without properties
                processedShaderBlocks.add(shaderBlock);
            }
        }

        // Find blocks missing from the shader
        Set<String> missingFromShader = new HashSet<>(gameBlocks);
        missingFromShader.removeAll(processedShaderBlocks);

        // Find blocks in the shader but not in the game
        Set<String> missingFromGame = new HashSet<>(processedShaderBlocks);
        missingFromGame.removeAll(gameBlocks);

        // Create a safe filename
        String safeName = shaderpackName.replaceAll("[^a-zA-Z0-9.-]", "_");
        Path comparisonPath = logsDir.resolve("block_comparison_" + safeName + ".txt");

        // Create categorized blocks
        Map<BlockRenderCategory, Map<String, List<String>>> categorizedBlocksByMod = getCategorizedBlocksByMod(blocksByMod);

        // Create categorized missing blocks
        Map<BlockRenderCategory, Set<String>> categorizedMissingBlocks = categorizeMissingBlocks(missingFromShader);

        // Write the comparison file
        writeComparisonFile(comparisonPath, shaderpackName, gameBlocks, processedShaderBlocks, missingFromShader, missingFromGame, categorizedBlocksByMod, categorizedMissingBlocks, blockPropertiesMap);
    }

    private static void writeComparisonFile(Path outputPath, String shaderpackName, Set<String> gameBlocks, Set<String> shaderBlocks, Set<String> missingFromShader, Set<String> missingFromGame, Map<BlockRenderCategory, Map<String, List<String>>> categorizedBlocksByMod, Map<BlockRenderCategory, Set<String>> categorizedMissingBlocks, Map<String, Set<BlockPropertyExtractor.BlockStateProperty>> blockPropertiesMap) {

        // The logs directory is the parent of the output path
        Path logsDir = outputPath.getParent();
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            writer.write("=========================================\n");
            writer.write("== BLOCK COMPARISON SUMMARY FOR " + shaderpackName.toUpperCase() + " ==\n");
            writer.write("=========================================\n");
            writer.write(String.format("Total blocks in game: %d\n", gameBlocks.size()));
            writer.write(String.format("Total blocks in shader: %d\n", shaderBlocks.size()));
            writer.write(String.format("Unused blocks from shader: %d\n", missingFromGame.size()));
            writer.write(String.format("Blocks missing from shader: %d\n\n", missingFromShader.size()));

            // Find and write missing property states
            EuphoriaCompanion.LOGGER.debug("Checking for missing property states in {} shader blocks", shaderBlocks.size());

            // Check if we have a missing_property_states.txt file
            Path missingPropertiesPath = logsDir.resolve("missing_property_states.txt");
            if (Files.exists(missingPropertiesPath)) {
                EuphoriaCompanion.LOGGER.debug("Using consolidated missing property states from {}", missingPropertiesPath);
                writeMissingPropertyStatesFromFile(writer, missingPropertiesPath);
            } else {
                // For safety during transition, still use the old method if file not found
                writeMissingPropertyStates(writer, shaderBlocks, blockPropertiesMap);
            }

            // Write category counts
            writeCategoryCounts(writer, categorizedBlocksByMod);

            if (missingFromShader.isEmpty() && !gameBlocks.isEmpty()) {
                writeCongratulationMessage(writer);
            }

            writeMissingBlocksByCategoryAndMod(writer, categorizedMissingBlocks);
            writeFullBlockListByCategoryAndMod(writer, categorizedBlocksByMod);
            writeUnusedShaderBlocks(writer, missingFromGame);

            EuphoriaCompanion.LOGGER.info("Report written to {}", outputPath);
        } catch (IOException e) {
            EuphoriaCompanion.LOGGER.error("Failed to write report", e);
        }
    }

    private static void writeCategoryCounts(BufferedWriter writer, Map<BlockRenderCategory, Map<String, List<String>>> categorizedBlocks) throws IOException {
        writer.write("============ BLOCK COUNTS BY CATEGORY ============\n");
        for (BlockRenderCategory category : BlockRenderCategory.values()) {
            int count = 0;
            Map<String, List<String>> modBlocks = categorizedBlocks.get(category);
            if (modBlocks != null) {
                for (List<String> blocks : modBlocks.values()) {
                    count += blocks.size();
                }
            }
            // Show "non_full_blocks" instead of "solid" in the report
            String displayName = category == BlockRenderCategory.SOLID ? "non_full_blocks" : category.name();
            writer.write(String.format("%s: %d blocks\n", displayName, count));
        }
        writer.write("\n");
    }

    private static void writeCongratulationMessage(BufferedWriter writer) throws IOException {
        writer.write("\n");
        writer.write("Nice! All blocks are added!\n\n");
    }

    /**
     * Checks for and writes any missing property states of blocks with properties
     * For example, if redstone_torch:lit=false is in the shader blocks but redstone_torch:lit=true is not,
     * this will detect and list the missing state.
     * <p>
     * Uses the improved BlockPropertyRegistry to track and compare properties.
     *
     * @param writer       BufferedWriter to write to
     * @param shaderBlocks Set of blocks in the shader
     * @throws IOException If an error occurs while writing
     */
    private static void writeMissingPropertyStates(BufferedWriter writer, Set<String> shaderBlocks, Map<String, Set<BlockPropertyExtractor.BlockStateProperty>> blockPropertiesMap) throws IOException {
        // Use the improved BlockPropertyRegistry
        BlockPropertyRegistry registry = BlockPropertyRegistry.getInstance(MinecraftClient.getInstance().runDirectory.toPath());

        // Clear any existing data to ensure a fresh start
        registry.clearAll();

        // Process shader blocks to identify properties
        Set<String> relevantBlocks = new HashSet<>();

        // First, add blocks from blockPropertiesMap
        for (Map.Entry<String, Set<BlockPropertyExtractor.BlockStateProperty>> entry : blockPropertiesMap.entrySet()) {
            String baseBlockId = entry.getKey();
            Set<BlockPropertyExtractor.BlockStateProperty> properties = entry.getValue();

            if (properties.isEmpty()) {
                continue;
            }

            // Get normalized block ID
            if (!baseBlockId.contains(":")) {
                baseBlockId = "minecraft:" + baseBlockId;
            }

            // Register each property
            for (BlockPropertyExtractor.BlockStateProperty prop : properties) {
                registry.registerUsedProperty(baseBlockId, prop.name(), prop.value());

                // Add reconstructed block ID with properties
                String blockWithProps = baseBlockId + ":" + prop.name() + "=" + prop.value();
                relevantBlocks.add(blockWithProps);
            }
        }

        // Then add blocks with properties from shader blocks
        for (String blockId : shaderBlocks) {
            if (blockId.contains("=")) {
                registry.registerBlockWithProperties(blockId);
                relevantBlocks.add(blockId);
            }
        }

        // No blocks with properties found
        if (relevantBlocks.isEmpty()) {
            return;
        }

        // Get missing property states
        List<String> missingPropertyStates = registry.findAllMissingPropertyStates();

        // Log missing property states count with debug level
        EuphoriaCompanion.LOGGER.debug("Found {} missing property states", missingPropertyStates.size());

        // Write the missing property states if any
        if (!missingPropertyStates.isEmpty()) {
            writer.write("============ MISSING PROPERTY STATES ============\n");
            writer.write("The following property states are missing from the shader:\n");

            for (String missingState : missingPropertyStates) {
                writer.write(missingState + "\n");
            }

            writer.write("\n");
        } else {
            EuphoriaCompanion.LOGGER.debug("No missing property states to write to report");
        }
    }

    /**
     * Writes missing property states from a file instead of detecting them
     *
     * @param writer                BufferedWriter to write to
     * @param missingPropertiesPath Path to the file containing missing property states
     * @throws IOException If an error occurs while reading or writing
     */
    private static void writeMissingPropertyStatesFromFile(BufferedWriter writer, Path missingPropertiesPath) throws IOException {
        List<String> missingPropertyStates = new ArrayList<>();

        try {
            // Read all lines after the header (first 3 lines)
            List<String> allLines = Files.readAllLines(missingPropertiesPath);
            if (allLines.size() > 3) {
                for (int i = 3; i < allLines.size(); i++) {
                    String line = allLines.get(i).trim();
                    if (!line.isEmpty()) {
                        missingPropertyStates.add(line);
                    }
                }
            }
        } catch (IOException e) {
            EuphoriaCompanion.LOGGER.error("Error reading missing property states file: {}", e.getMessage());
            return;
        }

        // Log how many we found
        EuphoriaCompanion.LOGGER.info("Read {} missing property states from file", missingPropertyStates.size());

        // Write them to the report
        if (!missingPropertyStates.isEmpty()) {
            writer.write("============ MISSING PROPERTY STATES ============\n");
            writer.write("The following property states are missing from the shader:\n");

            for (String missingState : missingPropertyStates) {
                writer.write("  " + missingState + "\n");
            }

            writer.write("\n");
        } else {
            EuphoriaCompanion.LOGGER.info("No missing property states found in file");
        }
    }

    private static void writeMissingBlocksByCategoryAndMod(BufferedWriter writer, Map<BlockRenderCategory, Set<String>> categorizedMissingBlocks) throws IOException {
        writer.write("============ MISSING BLOCKS ============\n");

        // Group by mod, then subgroup by category
        Map<String, Map<BlockRenderCategory, List<String>>> missingByModAndCategory = new TreeMap<>();

        // First, organize blocks by mod and then by category
        for (BlockRenderCategory category : BlockRenderCategory.values()) {
            Set<String> missingBlocks = categorizedMissingBlocks.get(category);
            if (missingBlocks == null || missingBlocks.isEmpty()) {
                continue;
            }

            for (String block : missingBlocks) {
                String[] parts = block.split(":", 2);
                if (parts.length == 2) {
                    String modId = parts[0];
                    String blockName = parts[1];

                    // Create nested map structure: modId -> category -> block list
                    missingByModAndCategory.computeIfAbsent(modId, k -> new HashMap<>()).computeIfAbsent(category, k -> new ArrayList<>()).add(blockName);
                }
            }
        }

        // Now write it organized by mod first, then by category
        for (Map.Entry<String, Map<BlockRenderCategory, List<String>>> modEntry : missingByModAndCategory.entrySet()) {
            String modId = modEntry.getKey();
            Map<BlockRenderCategory, List<String>> categorizedBlocks = modEntry.getValue();

            // Count total blocks for this mod across all categories
            int totalModBlocks = 0;
            for (List<String> blockList : categorizedBlocks.values()) {
                totalModBlocks += blockList.size();
            }

            writer.write("--- " + modId + " (" + totalModBlocks + ") ---\n");

            // For each category in this mod
            for (BlockRenderCategory category : BlockRenderCategory.values()) {
                List<String> blocks = categorizedBlocks.get(category);
                if (blocks == null || blocks.isEmpty()) {
                    continue;
                }

                // Show "non_full_blocks" instead of "solid" in the report
                String displayName = category == BlockRenderCategory.SOLID ? "non_full_blocks" : category.name();
                writer.write("-- " + displayName + " (" + blocks.size() + ") --\n");
                Collections.sort(blocks);
                for (String block : blocks) {
                    writer.write(modId + ":" + block + "\n");
                }
                writer.write("\n");
            }
            writer.write("\n");
        }
    }

    private static void writeFullBlockListByCategoryAndMod(BufferedWriter writer, Map<BlockRenderCategory, Map<String, List<String>>> categorizedBlocksByMod) throws IOException {
        writer.write("============ ALL BLOCKS ============\n");

        // Organize by mod instead of category
        Map<String, List<String>> allBlocksByMod = new TreeMap<>();

        // Combine all blocks from all categories by mod
        for (BlockRenderCategory category : BlockRenderCategory.values()) {
            Map<String, List<String>> blocksByMod = categorizedBlocksByMod.get(category);
            if (blocksByMod == null || blocksByMod.isEmpty()) {
                continue;
            }

            // Merge into the combined map
            for (Map.Entry<String, List<String>> entry : blocksByMod.entrySet()) {
                allBlocksByMod.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).addAll(entry.getValue());
            }
        }

        // Write blocks by mod
        for (Map.Entry<String, List<String>> entry : allBlocksByMod.entrySet()) {
            writer.write("--- " + entry.getKey() + " (" + entry.getValue().size() + ") ---\n");
            Collections.sort(entry.getValue());
            for (String block : entry.getValue()) {
                writer.write(entry.getKey() + ":" + block + "\n");
            }
            writer.write("\n");
        }
    }

    private static void writeUnusedShaderBlocks(BufferedWriter writer, Set<String> missingFromGame) throws IOException {
        if (missingFromGame.isEmpty()) {
            return;
        }

        Map<String, List<String>> unusedByNamespace = new TreeMap<>();
        for (String block : missingFromGame) {
            String[] parts = block.split(":", 2);
            if (parts.length == 2) {
                unusedByNamespace.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(parts[1]);
            } else {
                // Handle case where there's no namespace
                unusedByNamespace.computeIfAbsent("unknown", k -> new ArrayList<>()).add(block);
            }
        }

        writer.write("============ UNUSED SHADER BLOCKS ============\n");
        for (Map.Entry<String, List<String>> entry : unusedByNamespace.entrySet()) {
            writer.write("--- " + entry.getKey() + " (" + entry.getValue().size() + ") ---\n");
            Collections.sort(entry.getValue());
            for (String block : entry.getValue()) {
                writer.write(entry.getKey() + ":" + block + "\n");
            }
            writer.write("\n");
        }
    }

    /**
     * Categorizes blocks by their render layers and mod.
     *
     * @param blocksByMod Map of blocks organized by mod
     * @return Map of render categories to maps of mods to block lists
     */
    private static Map<BlockRenderCategory, Map<String, List<String>>> getCategorizedBlocksByMod(Map<String, List<String>> blocksByMod) {
        // Ensure blocks are categorized
        if (BlockRenderHelper.getBlocksInCategory(BlockRenderCategory.SOLID).isEmpty()) {
            BlockRenderHelper.categorizeAllBlocks();
        }

        Map<BlockRenderCategory, Map<String, List<String>>> result = new HashMap<>();

        // Initialize the maps for each category
        for (BlockRenderCategory category : BlockRenderCategory.values()) {
            result.put(category, new TreeMap<>());
        }

        // Process each mod's blocks
        for (Map.Entry<String, List<String>> entry : blocksByMod.entrySet()) {
            String modId = entry.getKey();

            for (String blockPath : entry.getValue()) {
                String fullId = modId + ":" + blockPath;
                // Split into namespace and path for 1.21.4 compatibility
                String[] parts = fullId.split(":", 2);
                if (parts.length == 2) {
                    // Use get for 1.21+ compatibility (returns default if not found)
                    Block block = Registries.BLOCK.get(Identifier.of(parts[0], parts[1]));
                    if (block == null || block == Blocks.AIR) {
                        continue;
                    }

                    BlockRenderCategory category = BlockRenderHelper.getRenderCategory(block);
                    result.get(category).computeIfAbsent(modId, k -> new ArrayList<>()).add(blockPath);
                }
            }
        }

        return result;
    }

    /**
     * Categorizes missing blocks by their render layers.
     *
     * @param missingBlocks Set of missing block identifiers
     * @return Map of render categories to sets of missing block identifiers
     */
    private static Map<BlockRenderCategory, Set<String>> categorizeMissingBlocks(Set<String> missingBlocks) {
        // Ensure blocks are categorized
        if (BlockRenderHelper.getBlocksInCategory(BlockRenderCategory.SOLID).isEmpty()) {
            BlockRenderHelper.categorizeAllBlocks();
        }

        Map<BlockRenderCategory, Set<String>> result = new HashMap<>();

        // Initialize sets for each category
        for (BlockRenderCategory category : BlockRenderCategory.values()) {
            result.put(category, new HashSet<>());
        }

        // Categorize each missing block
        for (String blockId : missingBlocks) {
            // Parse the block identifier using our improved parser
            String baseBlockId;
            Set<BlockPropertyExtractor.BlockStateProperty> properties = new HashSet<>();

            // Check if the block might have properties
            boolean hasProperties = blockId.contains(":") && blockId.contains("=");

            if (hasProperties) {
                // Use the improved BlockPropertyExtractor to parse the block identifier
                BlockPropertyExtractor.ParsedBlockIdentifier parsed = BlockPropertyExtractor.parseBlockIdentifier(blockId);

                baseBlockId = parsed.blockName();
                properties = parsed.properties();

                // If we couldn't find any properties, treat it as a regular block
                if (properties.isEmpty()) {
                    baseBlockId = blockId;
                }
            } else {
                // Regular block without properties
                baseBlockId = blockId;
            }

            // Split into namespace and path for compatibility
            String[] parts = baseBlockId.split(":", 2);
            if (parts.length != 2) {
                // Block doesn't have a namespace, add minecraft: namespace
                baseBlockId = "minecraft:" + baseBlockId;
                parts = baseBlockId.split(":", 2);
                if (parts.length != 2) {
                    continue; // Skip invalid identifiers
                }
                EuphoriaCompanion.LOGGER.debug("Added minecraft namespace: {}", baseBlockId);
            }

            // Use get for 1.21+ compatibility (returns default if not found)
            Block block = Registries.BLOCK.get(Identifier.of(parts[0], parts[1]));
            if (block == null || block == Blocks.AIR) {
                continue;
            }

            // If there are properties, verify the block supports them
            if (!properties.isEmpty() && !BlockPropertyExtractor.matchesFileProperties(block, properties)) {
                EuphoriaCompanion.LOGGER.debug("Block {} doesn't support properties: {}", blockId, properties);
                continue;
            }

            // Get render category and add to result
            BlockRenderCategory category = BlockRenderHelper.getRenderCategory(block);
            result.get(category).add(blockId);
        }

        return result;
    }
}
