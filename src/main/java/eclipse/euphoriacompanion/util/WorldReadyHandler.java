package eclipse.euphoriacompanion.util;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Handles world ready detection in a more elegant way using events.
 * This class allows registering callbacks that will be triggered when the world is ready.
 */
public class WorldReadyHandler {
    // The number of ticks to wait after world connection before considering the world "ready"
    private static final int READY_TICK_THRESHOLD = 40; // About 2 seconds at 20 TPS

    // Flag to track if we're currently waiting for world ready
    private static final AtomicBoolean waitingForWorld = new AtomicBoolean(false);

    // Flag to track if we're currently counting ticks
    private static final AtomicBoolean countingTicks = new AtomicBoolean(false);
    // List of pending callbacks to execute when world is ready
    private static final List<Consumer<MinecraftClient>> pendingCallbacks = new ArrayList<>();
    // Tick counter
    private static int tickCounter = 0;
    // Flag to track if the handler has been initialized
    private static boolean initialized = false;

    /**
     * Initializes the world ready handler
     */
    public static void initialize() {
        if (initialized) {
            return;
        }

        // Register connection event to detect when player joins a world
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            EuphoriaCompanion.LOGGER.debug("Player joined world, starting ready detection");
            waitingForWorld.set(true);
            countingTicks.set(true);
            tickCounter = 0;
        });

        // Register disconnect event to cancel any pending callbacks
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            EuphoriaCompanion.LOGGER.debug("Player disconnected, canceling world ready detection");
            waitingForWorld.set(false);
            countingTicks.set(false);
            tickCounter = 0;
        });

        // Register tick event to count ticks after world connection
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (countingTicks.get() && client.world != null && client.player != null) {
                tickCounter++;

                // Check if we've reached the threshold
                if (tickCounter >= READY_TICK_THRESHOLD) {
                    countingTicks.set(false);

                    // World is ready, execute callbacks
                    if (waitingForWorld.getAndSet(false)) {
                        EuphoriaCompanion.LOGGER.debug("World ready detected after {} ticks, executing {} callbacks", tickCounter, pendingCallbacks.size());

                        executePendingCallbacks(client);
                    }
                }
            }
        });

        initialized = true;
        EuphoriaCompanion.LOGGER.debug("WorldReadyHandler initialized");
    }

    /**
     * Registers a callback to be executed when the world is ready
     *
     * @param callback The callback to execute
     */
    public static void onWorldReady(Consumer<MinecraftClient> callback) {
        // Ensure handler is initialized
        initialize();

        CompletableFuture<Void> future = new CompletableFuture<>();

        // If world is already available, and we're not waiting, execute immediately
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null && client.world != null && client.player != null && !waitingForWorld.get() && !countingTicks.get()) {
            EuphoriaCompanion.LOGGER.debug("World already ready, executing callback immediately");
            try {
                callback.accept(client);
                future.complete(null);
            } catch (Exception e) {
                EuphoriaCompanion.LOGGER.error("Error executing world ready callback", e);
                future.completeExceptionally(e);
            }
            return;
        }

        // Otherwise, queue for later execution
        synchronized (pendingCallbacks) {
            pendingCallbacks.add(mc -> {
                try {
                    callback.accept(mc);
                    future.complete(null);
                } catch (Exception e) {
                    EuphoriaCompanion.LOGGER.error("Error executing world ready callback", e);
                    future.completeExceptionally(e);
                }
            });
        }

        EuphoriaCompanion.LOGGER.debug("Registered world ready callback, total pending: {}", pendingCallbacks.size());
    }

    /**
     * Executes all pending callbacks
     */
    private static void executePendingCallbacks(MinecraftClient client) {
        List<Consumer<MinecraftClient>> callbacks;

        // Get and clear the pending callbacks
        synchronized (pendingCallbacks) {
            callbacks = new ArrayList<>(pendingCallbacks);
            pendingCallbacks.clear();
        }

        // Execute all callbacks
        for (Consumer<MinecraftClient> callback : callbacks) {
            try {
                callback.accept(client);
            } catch (Exception e) {
                EuphoriaCompanion.LOGGER.error("Error executing world ready callback", e);
            }
        }
    }
}