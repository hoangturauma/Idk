package eclipse.euphoriacompanion.util;

import net.minecraft.registry.Registries;

/**
 * Utility class to interact with Minecraft's registries.
 */
public class RegistryUtil {
    /**
     * Checks if the block registry is frozen (all blocks have been registered).
     *
     * @return true if the registry is frozen, false otherwise.
     */
    public static boolean isBlockRegistryFrozen() {
        try {
            // Attempt to check frozen state using reflection
            // Different Minecraft versions handle this differently
            Object rawRegistry = Registries.BLOCK;

            try {
                // Try direct approach first
                java.lang.reflect.Method isFrozenMethod = rawRegistry.getClass().getMethod("isFrozen");
                return (boolean) isFrozenMethod.invoke(rawRegistry);
            } catch (NoSuchMethodException e) {
                // Fall back to field check
                try {
                    java.lang.reflect.Field frozenField = findFrozenField(rawRegistry.getClass());
                    if (frozenField != null) {
                        frozenField.setAccessible(true);
                        return (boolean) frozenField.get(rawRegistry);
                    }
                } catch (Exception ignored) {
                    // Continue to next approach
                }

                // Fallback: check if we can modify the registry
                // Not ideal but better than nothing
                return true; // Assume frozen by default if we can't determine
            }
        } catch (Exception e) {
            return true; // If we can't check, assume it's frozen
        }
    }

    private static java.lang.reflect.Field findFrozenField(Class<?> clazz) {
        // Try common field names for frozen state
        String[] possibleNames = {"frozen", "isFrozen", "locked", "isLocked"};

        for (String name : possibleNames) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                if (field.getType() == boolean.class) {
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
                // Try next name
            }
        }

        // Check superclass if available
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null && superClass != Object.class) {
            return findFrozenField(superClass);
        }

        return null;
    }
}