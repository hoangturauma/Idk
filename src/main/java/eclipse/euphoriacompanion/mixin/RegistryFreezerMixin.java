package eclipse.euphoriacompanion.mixin;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import eclipse.euphoriacompanion.util.BlockRegistryCacheManager;
import eclipse.euphoriacompanion.util.BlockRenderHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to create a block registry cache when the game is fully loaded.
 * By the time the title screen appears, all registries are frozen.
 */
@Mixin(TitleScreen.class)
public class RegistryFreezerMixin {
    @Unique
    private static boolean hasRun = false;

    @Inject(method = "init", at = @At("TAIL"))
    private void onTitleScreenInit(CallbackInfo ci) {
        // Only run once per game session
        if (!hasRun) {
            MinecraftClient.getInstance().execute(() -> {
                EuphoriaCompanion.LOGGER.info("Game initialized, caching block registry data");
                // Create block registry cache
                BlockRegistryCacheManager.cacheBlockRegistry();

                // Categorize blocks by their render layers
                BlockRenderHelper.categorizeAllBlocks();
            });
            hasRun = true;
        }
    }
}