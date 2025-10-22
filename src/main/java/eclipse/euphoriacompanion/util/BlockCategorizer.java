package eclipse.euphoriacompanion.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Helper utility class for exporting block render categories to JSON.
 */
public class BlockCategorizer {
    private static final String CATEGORIES_FILENAME = "block_render_categories.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Gets the directory to store categorized block data.
     */
    private static Path getCategoriesDir() {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        Path categoriesDir = modsDir.resolve(EuphoriaCompanion.MODID);
        try {
            if (!Files.exists(categoriesDir)) {
                Files.createDirectories(categoriesDir);
                EuphoriaCompanion.LOGGER.info("Created categories directory at {}", categoriesDir);
            }
        } catch (IOException e) {
            EuphoriaCompanion.LOGGER.error("Failed to create categories directory", e);
        }
        return categoriesDir;
    }

    /**
     * Gets the path to the categories JSON file.
     */
    private static Path getCategoriesFile() {
        return getCategoriesDir().resolve(CATEGORIES_FILENAME);
    }

    /**
     * Exports all blocks categorized by their render layers to a JSON file.
     * Each category lists blocks with their identifiers.
     */
    public static void exportBlockCategories() {
        EuphoriaCompanion.LOGGER.info("Exporting block render categories to JSON...");

        // Create the JSON object
        JsonObject rootObject = new JsonObject();
        rootObject.addProperty("timestamp", System.currentTimeMillis());

        // Add category counts - use our getCategoryCounts method
        JsonObject countsObject = new JsonObject();
        getCategoryCounts().forEach(countsObject::addProperty);
        rootObject.add("counts", countsObject);

        // Add block lists by category
        JsonObject categoriesObject = new JsonObject();
        Map<String, List<String>> blockIdsByCategory = BlockRenderHelper.getAllBlockIdsByCategory();

        for (Map.Entry<String, List<String>> entry : blockIdsByCategory.entrySet()) {
            String category = entry.getKey();
            List<String> blockIds = entry.getValue();

            JsonObject categoryObject = new JsonObject();

            // Group blocks by namespace
            Map<String, List<String>> blocksByNamespace = new HashMap<>();
            for (String fullId : blockIds) {
                String[] parts = fullId.split(":", 2);
                if (parts.length == 2) {
                    blocksByNamespace.computeIfAbsent(parts[0], k -> new ArrayList<>()).add(parts[1]);
                }
            }

            // Add namespace groups to category object
            for (Map.Entry<String, List<String>> namespaceEntry : blocksByNamespace.entrySet()) {
                // Sort blocks within each namespace
                Collections.sort(namespaceEntry.getValue());
                categoryObject.add(namespaceEntry.getKey(), GSON.toJsonTree(namespaceEntry.getValue()));
            }

            categoriesObject.add(category, categoryObject);
        }
        rootObject.add("categories", categoriesObject);

        // Write to file
        Path categoriesFile = getCategoriesFile();
        try (Writer writer = Files.newBufferedWriter(categoriesFile)) {
            GSON.toJson(rootObject, writer);
            EuphoriaCompanion.LOGGER.info("Successfully exported block categories to {}", categoriesFile);
        } catch (IOException e) {
            EuphoriaCompanion.LOGGER.error("Failed to write block categories to JSON", e);
        }
    }

    /**
     * Gets a map of category names to counts of blocks in each category.
     * Useful for displaying statistics.
     *
     * @return Map of category names to block counts
     */
    public static Map<String, Integer> getCategoryCounts() {
        Map<String, Integer> counts = new HashMap<>();
        Map<BlockRenderCategory, List<Block>> blocksByCategory = BlockRenderHelper.getAllBlocksByCategory();
        for (Map.Entry<BlockRenderCategory, List<Block>> entry : blocksByCategory.entrySet()) {
            counts.put(entry.getKey().name(), entry.getValue().size());
        }
        return counts;
    }
}
