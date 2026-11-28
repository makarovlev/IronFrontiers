package com.gachicraft.ironfrontiers.sector;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

import java.util.OptionalDouble;

/**
 * Bukkit surface resolver used by teleport and particle preview. It never loads
 * chunks and returns the highest collision-shape Y, including partial blocks.
 */
public final class SurfaceResolver {
    private SurfaceResolver() { }

    public static OptionalDouble top(World world, int x, int z, int referenceY) {
        if (world == null) {
            return OptionalDouble.empty();
        }
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return OptionalDouble.empty();
        }

        int startY = Math.min(world.getMaxHeight() - 1, referenceY + 8);
        for (int y = startY; y >= world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.isPassable() || block.isLiquid() || block.getType().isAir()) {
                continue;
            }

            VoxelShape shape = block.getCollisionShape();
            double maxY = Double.NEGATIVE_INFINITY;
            for (BoundingBox box : shape.getBoundingBoxes()) {
                maxY = Math.max(maxY, box.getMaxY());
            }
            if (Double.isFinite(maxY)) {
                return OptionalDouble.of(y + maxY);
            }
        }
        return OptionalDouble.empty();
    }
}
