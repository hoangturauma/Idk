package eclipse.euphoriacompanion.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

public class BlockRegistryCacheManager {
    private static final String CACHE_FILENAME = "block_registry_cache.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type BLOCK_CACHE_TYPE = new TypeToken<Map<String, List<String>>>() {
    }.getType();

    private static Path getCacheDir() {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        Path cacheDir = modsDir.resolve(EuphoriaCompanion.MODID);
        try {
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir);
                EuphoriaCompanion.LOGGER.info("Created cache directory at {}", cacheDir);
            }
        } catch (IOException e) {
            EuphoriaCompanion.LOGGER.error("Failed to create cache directory", e);
        }
        return cacheDir;
    }

    private static Path getCacheFile() {
        return getCacheDir().resolve(CACHE_FILENAME);
    }

    /**
     * Generates a hash of the current mod list to detect when mods have been added or removed
     */
    private static String generateModHash() {
        try {
            // Get a sorted list of all mods with their versions
            List<String> modStrings = FabricLoader.getInstance().getAllMods().stream().map(ModContainer::getMetadata).sorted(Comparator.comparing(ModMetadata::getId)).map(meta -> meta.getId() + "@" + meta.getVersion().getFriendlyString()).collect(Collectors.toList());

            // Add Minecraft version to the hash
            String mcVersion = FabricLoader.getInstance().getModContainer("minecraft").map(mc -> mc.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
            modStrings.add("minecraft@" + mcVersion);

            // Create a hash of the mod list
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String modListString = String.join(";", modStrings);
            byte[] hash = digest.digest(modListString.getBytes());

            // Convert hash to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            EuphoriaCompanion.LOGGER.error("Failed to generate mod hash", e);
            return "unknown-" + System.currentTimeMillis();
        }
    }

    public static void cacheBlockRegistry() {
        EuphoriaCompanion.LOGGER.info("Caching block registry data...");

        // Generate a hash of the current mods
        String modHash = generateModHash();
        EuphoriaCompanion.LOGGER.debug("Generated mod hash: {}", modHash);

        // Collect block data
        Map<String, List<String>> blocksByMod = new HashMap<>();

        // Collect block data from the registry
        Registries.BLOCK.forEach(block -> {
            Identifier id = Registries.BLOCK.getId(block);
            blocksByMod.computeIfAbsent(id.getNamespace(), k -> new ArrayList<>()).add(id.getPath());
        });

        // Sort all lists for consistency
        for (List<String> blocks : blocksByMod.values()) {
            Collections.sort(blocks);
        }

        // Create cache object with metadata
        JsonObject cacheRoot = new JsonObject();
        cacheRoot.addProperty("modHash", modHash);
        cacheRoot.addProperty("timestamp", System.currentTimeMillis());
        cacheRoot.addProperty("blockCount", getBlockCount(blocksByMod));
        cacheRoot.add("blocks", GSON.toJsonTree(blocksByMod));

        // Save to cache file
        Path cacheFile = getCacheFile();
        try (Writer writer = Files.newBufferedWriter(cacheFile)) {
            GSON.toJson(cacheRoot, writer);
            EuphoriaCompanion.LOGGER.info("Successfully cached {} blocks from {} mods to {}", getBlockCount(blocksByMod), blocksByMod.size(), cacheFile);
        } catch (IOException e) {
            EuphoriaCompanion.LOGGER.error("Failed to write block registry cache", e);
        }
    }

    private static int getBlockCount(Map<String, List<String>> blocksByMod) {
        return blocksByMod.values().stream().mapToInt(List::size).sum();
    }

    public static Map<String, List<String>> loadBlockCache() {
        Path cacheFile = getCacheFile();
        if (!Files.exists(cacheFile)) {
            EuphoriaCompanion.LOGGER.info("Block registry cache not found at {}", cacheFile);
            return null;
        }

        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            // Read the cache file
            JsonObject cacheRoot = GSON.fromJson(reader, JsonObject.class);

            // Check if mod hash matches
            String cachedModHash = cacheRoot.get("modHash").getAsString();
            String currentModHash = generateModHash();

            if (!currentModHash.equals(cachedModHash)) {
                EuphoriaCompanion.LOGGER.info("Block registry cache is stale (mod list has changed)");
                EuphoriaCompanion.LOGGER.debug("Current hash: {}, Cached hash: {}", currentModHash, cachedModHash);
                return null;
            }

            // Extract the blocks data
            JsonElement blocksElement = cacheRoot.get("blocks");
            Map<String, List<String>> blocksByMod = GSON.fromJson(blocksElement, BLOCK_CACHE_TYPE);

            EuphoriaCompanion.LOGGER.info("Loaded {} blocks from {} mods from cache", getBlockCount(blocksByMod), blocksByMod.size());
            return blocksByMod;
        } catch (IOException | com.google.gson.JsonSyntaxException e) {
            EuphoriaCompanion.LOGGER.error("Failed to read block registry cache", e);
            return null;
        }
    }

    public static boolean cacheExists() {
        return Files.exists(getCacheFile());
    }
}