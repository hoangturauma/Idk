package eclipse.euphoriacompanion.util;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * Helper class for working with the block registry.
 */
public class BlockRegistryHelper {
    /**
     * Gets all registered blocks and organizes them by mod namespace.
     *
     * @param blocksByMod A map that will be populated with blocks organized by mod namespace
     * @return A set of all block identifiers in the registry
     */
    public static Set<String> getGameBlocks(final Map<String, List<String>> blocksByMod) {
        final Set<String> gameBlocks = new HashSet<>();

        // Try to load from cache first
        Map<String, List<String>> cachedBlocks = BlockRegistryCacheManager.loadBlockCache();
        if (cachedBlocks != null && !cachedBlocks.isEmpty()) {
            EuphoriaCompanion.LOGGER.info("Using cached block registry data");

            // Copy the cached data to the output map
            blocksByMod.putAll(cachedBlocks);

            // Convert to game blocks format (full identifiers)
            for (Map.Entry<String, List<String>> entry : cachedBlocks.entrySet()) {
                String namespace = entry.getKey();
                for (String path : entry.getValue()) {
                    gameBlocks.add(namespace + ":" + path);
                }
            }

            return gameBlocks;
        }

        // If cache is not available, use the registry directly
        EuphoriaCompanion.LOGGER.info("Cache not available, using live registry data");

        // Using the Fabric registry system for 1.19.3+
        Registries.BLOCK.forEach(block -> {
            Identifier id = Registries.BLOCK.getId(block);

            String registryId = id.toString();
            gameBlocks.add(registryId);

            blocksByMod.computeIfAbsent(id.getNamespace(), k -> new ArrayList<>()).add(id.getPath());
        });

        return gameBlocks;
    }
}