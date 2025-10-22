package eclipse.euphoriacompanion.util;

import java.util.Arrays;
import java.util.List;

/**
 * A simple class to represent block render categories.
 * We focus on key rendering attributes that affect shaders.
 */
public record BlockRenderCategory(String name) {
    // Main categories
    public static final BlockRenderCategory SOLID = new BlockRenderCategory("solid");
    public static final BlockRenderCategory TRANSLUCENT = new BlockRenderCategory("translucent");
    public static final BlockRenderCategory LIGHT_EMITTING = new BlockRenderCategory("light_emitting");
    public static final BlockRenderCategory FULL_CUBE = new BlockRenderCategory("full_cube");
    public static final BlockRenderCategory BLOCK_ENTITY = new BlockRenderCategory("block_entity");

    // Static list for values() method
    private static final List<BlockRenderCategory> VALUES = Arrays.asList(SOLID, TRANSLUCENT, LIGHT_EMITTING, FULL_CUBE, BLOCK_ENTITY);

    /**
     * Gets all render categories.
     * This replaces the enum's values() method.
     *
     * @return List of all render categories
     */
    public static List<BlockRenderCategory> values() {
        return VALUES;
    }

    @Override
    public String toString() {
        return name;
    }
}