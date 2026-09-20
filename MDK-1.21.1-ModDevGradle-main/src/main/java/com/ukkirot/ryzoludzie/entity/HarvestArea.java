package com.ukkirot.ryzoludzie.entity;

import net.minecraft.core.BlockPos;

/**
 * Prostopadłościan w blokach, w którym jednostka ma pracować (granice włącznie).
 * Zawsze znormalizowany: min <= max na każdej osi.
 */
public record HarvestArea(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static HarvestArea of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new HarvestArea(
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(BlockPos p) {
        return p.getX() >= minX && p.getX() <= maxX
                && p.getY() >= minY && p.getY() <= maxY
                && p.getZ() >= minZ && p.getZ() <= maxZ;
    }

    public int[] toArray() {
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Zwraca null, jeśli tablica nie ma dokładnie 6 elementów. */
    public static HarvestArea fromArray(int[] a) {
        return a.length == 6 ? of(a[0], a[1], a[2], a[3], a[4], a[5]) : null;
    }
}
