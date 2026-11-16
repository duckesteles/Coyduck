package dev.coyduck.logic;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NcpVisibleTest {
    private record Cell(String name, int x, int z) {}

    private static final List<Cell> CELLS = List.of(new Cell("E", 1, 0), new Cell("W", -1, 0), new Cell("N", 0, -1), new Cell("S", 0, 1));
    private static final int FEET = 64;
    private static final double EYE = 65.62;

    private static long key(int x, int y, int z) {
        return ((long) x & 0xFFFF) << 32 | ((long) y & 0xFFFF) << 16 | (long) z & 0xFFFF;
    }

    private static Set<Long> world(Cell open, boolean ring, boolean corner) {
        Set<Long> solid = new HashSet<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) solid.add(key(x, FEET - 1, z));
        }
        for (Cell cell : CELLS) {
            if (cell != open) solid.add(key(cell.x, FEET, cell.z));
            if (ring) solid.add(key(cell.x, FEET + 1, cell.z));
        }
        if (corner) solid.add(key(1, FEET, 1));
        return solid;
    }

    private static int count(Set<Long> solid, int x, int y, int z) {
        NcpVisible visible = new NcpVisible();
        int passed = 0;
        for (int i = 0; i < 10; i++) {
            for (int j = 0; j < 10; j++) {
                double eyeX = 0.31 + 0.38 * i / 9;
                double eyeZ = 0.31 + 0.38 * j / 9;
                if (visible.sees((bx, by, bz) -> solid.contains(key(bx, by, bz)), x, y, z, NcpVisible.Y, eyeX, EYE, eyeZ)) passed++;
            }
        }
        return passed;
    }

    private static void expect(boolean ring, boolean corner, int[] below, int[] above, int[] air) {
        for (int i = 0; i < CELLS.size(); i++) {
            Cell cell = CELLS.get(i);
            Set<Long> solid = world(cell, ring, corner);
            assertEquals(below[i], count(solid, cell.x, FEET - 1, cell.z), cell.name + " below");
            if (above != null) assertEquals(above[i], count(solid, cell.x, FEET + 1, cell.z), cell.name + " above");
            assertEquals(air[i], count(solid, cell.x, FEET, cell.z), cell.name + " air");
        }
    }

    @Test
    void openRingIsAlwaysSeen() {
        expect(false, false, new int[]{100, 100, 100, 100}, null, new int[]{100, 100, 100, 100});
    }

    @Test
    void headRingHidesEastAndSouthSupports() {
        expect(true, false, new int[]{45, 100, 100, 55}, new int[]{100, 100, 0, 0}, new int[]{100, 100, 5, 0});
    }

    @Test
    void cornerBlockOpensEveryCell() {
        expect(true, true, new int[]{100, 100, 100, 100}, new int[]{100, 100, 0, 0}, new int[]{100, 100, 5, 100});
    }

    @Test
    void headBlockSeesItself() {
        assertTrue(new NcpVisible().sees((x, y, z) -> true, 0, 65, 0, NcpVisible.Y, 0.5, EYE, 0.5));
    }

    @Test
    void walledEyeSeesNothing() {
        assertFalse(new NcpVisible().sees((x, y, z) -> !(x == 0 && z == 0 && (y == 64 || y == 65)), 3, 63, 3, NcpVisible.Y, 0.5, EYE, 0.5));
    }
}
