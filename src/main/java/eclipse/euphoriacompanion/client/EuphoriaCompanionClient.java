package eclipse.euphoriacompanion.client;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import eclipse.euphoriacompanion.util.BlockRegistryCacheManager;
import eclipse.euphoriacompanion.util.BlockRenderHelper;
import eclipse.euphoriacompanion.util.RegistryUtil;
import eclipse.euphoriacompanion.util.WorldReadyHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.MinecraftClient;

import java.util.Objects;

/**
 * Client initializer for the Euphoria Companion mod.
 * Keybinding is handled through mixins to ensure compatibility across Fabric versions.
 */
public class EuphoriaCompanionClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EuphoriaCompanion.LOGGER.info("Initializing Euphoria Companion Client");

        try {
            // Initialize the world ready handler
            WorldReadyHandler.initialize();

            // Register client lifecycle events
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                // Handle registry caching
                setupRegistryCache();

                // Register for world ready event for block categorization
                setupWorldReadyHandlers();
            });

            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
                eclipse.euphoriacompanion.shader.ShaderPackProcessor.shutdown();
            });
        } catch (Exception e) {
            EuphoriaCompanion.LOGGER.error("Failed to register client lifecycle events", e);
        }
    }

    /**
     * Sets up the block registry cache if needed
     */
    private void setupRegistryCache() {
        MinecraftClient.getInstance().execute(() -> {
            // Only run if block registry is frozen
            if (!RegistryUtil.isBlockRegistryFrozen()) {
                return;
            }

            EuphoriaCompanion.LOGGER.info("Client started, checking block registry cache");
            if (!BlockRegistryCacheManager.cacheExists()) {
                EuphoriaCompanion.LOGGER.info("Creating initial block registry cache");
                BlockRegistryCacheManager.cacheBlockRegistry();

                // Skip block categorization here - we'll do it when a world is loaded
                EuphoriaCompanion.LOGGER.info("Block categorization will be performed when a world is loaded");

                // Clear any existing caches
                clearBlockRenderCaches();
            }
        });
    }

    /**
     * Clears the BlockRenderHelper caches
     */
    private void clearBlockRenderCaches() {
        BlockRenderHelper.clearCaches();
    }

    /**
     * Sets up handlers that will be called when the world is ready
     */
    private void setupWorldReadyHandlers() {
        // Register for world ready event to perform block categorization
        WorldReadyHandler.onWorldReady(client -> {
            EuphoriaCompanion.LOGGER.info("World is ready, player at position {}, now categorizing blocks", Objects.requireNonNull(client.player).getBlockPos());

            // Perform block categorization on the main thread
            client.execute(() -> {
                // Double-check that world is still available
                if (client.world != null) {
                    // First categorize all blocks
                    BlockRenderHelper.categorizeAllBlocks();

                    // Export block categories for shader developers
                    EuphoriaCompanion.exportBlockCategories();

                    // Then process shader packs once categorization is done
                    EuphoriaCompanion.LOGGER.info("Block categorization complete, processing shader packs");
                    EuphoriaCompanion.processShaderPacks();
                } else {
                    EuphoriaCompanion.LOGGER.warn("World became null before categorization could start");
                }
            });
        });
    }
}
