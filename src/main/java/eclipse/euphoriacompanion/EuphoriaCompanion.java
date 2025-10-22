package eclipse.euphoriacompanion;

import eclipse.euphoriacompanion.shader.ShaderPackProcessor;
import eclipse.euphoriacompanion.util.BlockCategorizer;
import eclipse.euphoriacompanion.util.BlockRegistryCacheManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class EuphoriaCompanion implements ModInitializer {
    public static final String MODID = "euphoriacompanion";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);
    public static KeyBinding ANALYZE_KEY;

    /**
     * Process all shader packs in the game directory.
     */
    public static void processShaderPacks() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        ShaderPackProcessor.processShaderPacksAsync(gameDir);
    }


    /**
     * Export the current block categorization to a JSON file.
     * This is useful for shader pack developers.
     */
    public static void exportBlockCategories() {
        BlockCategorizer.exportBlockCategories();
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing Euphoria Companion");

        try {
            // Register the keybinding using Fabric API
            // In Minecraft 1.21.9+, categories are strongly typed with Identifier
            KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("euphoriacompanion", "keys"));
            ANALYZE_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.euphoriacompanion.analyze", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, category));

            LOGGER.info("Successfully registered keybinding");
        } catch (Exception e) {
            LOGGER.error("Failed to register keybinding", e);
        }

        // Log cache status on startup
        if (BlockRegistryCacheManager.cacheExists()) {
            LOGGER.info("Block registry cache exists and will be used when analyzing shaders");
        } else {
            LOGGER.info("No block registry cache found - it will be created when the registry freezes");
        }
    }
}
