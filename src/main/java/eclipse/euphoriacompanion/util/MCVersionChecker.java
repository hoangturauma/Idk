package eclipse.euphoriacompanion.util;

import eclipse.euphoriacompanion.EuphoriaCompanion;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Optional;

public class MCVersionChecker {
    public static final int MC_1_21_2 = 12102;
    public static final int MC_1_21_5 = 12105;

    // Cache the version for performance
    private static int cachedVersion = -1;

    public static int getMCVersion() {
        // Use cached version if available
        if (cachedVersion != -1) {
            return cachedVersion;
        }

        Optional<ModContainer> minecraftContainer = FabricLoader.getInstance().getModContainer("minecraft");
        if (minecraftContainer.isPresent()) {
            String version = minecraftContainer.get().getMetadata().getVersion().getFriendlyString();

            try {
                String[] parts = version.split("\\.");
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);

                // Handle patch version with potential pre-release suffix
                int patch = 0;
                if (parts.length > 2) {
                    // Extract just the numeric part by splitting at first non-digit character
                    String patchStr = parts[2];
                    if (patchStr.contains("-")) {
                        // If there's a pre-release suffix (like "5-rc"), take just the number part
                        String[] patchParts = patchStr.split("-", 2);
                        patchStr = patchParts[0];

                        // Log error if we detect a snapshot/pre-release
                        if (patchParts.length > 1) {
                            EuphoriaCompanion.LOGGER.error("Detected snapshot/pre-release version: {}. Using only the numeric part.", version);
                        }
                    }
                    try {
                        patch = Integer.parseInt(patchStr);
                    } catch (NumberFormatException e) {
                        EuphoriaCompanion.LOGGER.error("Couldn't parse patch version from {}, using 0", patchStr);
                    }
                }

                cachedVersion = major * 10000 + minor * 100 + patch;
                return cachedVersion;
            } catch (Exception e) {
                EuphoriaCompanion.LOGGER.error("Failed to parse Minecraft version: {}. Assuming latest version.", version, e);
                // Return very high version number to assume it's the latest version
                return 99999; // This will pass any version check
            }
        } else {
            throw new RuntimeException("Could not get Minecraft version");
        }
    }

    /**
     * Checks if the current Minecraft version is at least the specified version
     *
     * @param minVersion The minimum version to check against
     * @return true if current version is greater than or equal to minVersion
     */
    public static boolean isAtLeast(int minVersion) {
        return getMCVersion() >= minVersion;
    }

    /**
     * Checks if the current Minecraft version is 1.21.2 or later
     *
     * @return true if running on Minecraft 1.21.2+
     */
    public static boolean isMinecraft1212OrLater() {
        return isAtLeast(MC_1_21_2);
    }
    
    /**
     * Checks if the current Minecraft version is 1.21.5 or later
     *
     * @return true if running on Minecraft 1.21.5+
     */
    public static boolean isMinecraft1215OrLater() {
        return isAtLeast(MC_1_21_5);
    }

    public static boolean evaluateCondition(String condition, int mcVersion) {
        condition = condition.trim();
        if (condition.startsWith("MC_VERSION")) {
            condition = condition.substring("MC_VERSION".length()).trim();
            String[] parts = condition.split("\\s+");
            if (parts.length < 2) return false;
            String operator = parts[0];
            String valueStr = parts[1];
            try {
                int value = Integer.parseInt(valueStr);
                if (">=".equals(operator)) {
                    return mcVersion >= value;
                } else if (">".equals(operator)) {
                    return mcVersion > value;
                } else if ("<=".equals(operator)) {
                    return mcVersion <= value;
                } else if ("<".equals(operator)) {
                    return mcVersion < value;
                } else if ("==".equals(operator)) {
                    return mcVersion == value;
                } else if ("!=".equals(operator)) {
                    return mcVersion != value;
                } else {
                    return false;
                }
            } catch (NumberFormatException e) {
                EuphoriaCompanion.LOGGER.error("Invalid version in condition: {}", valueStr, e);
                return false;
            }
        }
        return false;
    }
}