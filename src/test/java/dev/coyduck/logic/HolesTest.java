package dev.coyduck.logic;

import dev.coyduck.logic.Holes.Hole;
import dev.coyduck.logic.Holes.Kind;
import dev.coyduck.logic.Holes.Shapes;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HolesTest {
    private static final Shapes ALL = new Shapes(true, true, true);

    private static class Grid implements Holes.Terrain {
        final Map<Long, Integer> walls = new HashMap<>();
        int fill = Holes.SOLID;

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFF) << 32 | ((long) y & 0xFFFF) << 16 | (long) z & 0xFFFF;
        }

        Grid dig(int x, int z) {
            walls.put(key(x, 0, z), Holes.OPEN);
            return this;
        }

        Grid set(int x, int y, int z, int kind) {
            walls.put(key(x, y, z), kind);
            return this;
        }

        @Override
        public boolean room(int x, int y, int z) {
            return wall(x, y, z) == Holes.OPEN;
        }

        @Override
        public int wall(int x, int y, int z) {
            Integer kind = walls.get(key(x, y, z));
            if (kind != null) return kind;
            return y == 0 ? fill : y < 0 ? fill : Holes.OPEN;
        }
    }

    @Test
    void findsASingle() {
        Hole hole = Holes.at(new Grid().dig(0, 0), 0, 0, 0, ALL);
        assertEquals(Kind.SINGLE, hole.kind());
        assertEquals(0.5, hole.middleX());
        assertEquals(0.5, hole.middleZ());
        assertFalse(hole.safe());
    }

    @Test
    void bedrockEverywhereIsSafe() {
        Grid grid = new Grid();
        grid.fill = Holes.BEDROCK;
        assertTrue(Holes.at(grid.dig(0, 0), 0, 0, 0, ALL).safe());
        assertFalse(Holes.at(grid.set(1, 0, 0, Holes.SOLID), 0, 0, 0, ALL).safe());
    }

    @Test
    void findsDoublesFromTheMinCornerOnly() {
        Grid x = new Grid().dig(0, 0).dig(1, 0);
        assertEquals(Kind.DOUBLE_X, Holes.at(x, 0, 0, 0, ALL).kind());
        assertNull(Holes.at(x, 1, 0, 0, ALL));
        Grid z = new Grid().dig(0, 0).dig(0, 1);
        assertEquals(Kind.DOUBLE_Z, Holes.at(z, 0, 0, 0, ALL).kind());
        assertNull(Holes.at(z, 0, 0, 0, new Shapes(true, false, true)));
    }

    @Test
    void findsAQuad() {
        Grid grid = new Grid().dig(0, 0).dig(1, 0).dig(0, 1).dig(1, 1);
        Hole hole = Holes.at(grid, 0, 0, 0, ALL);
        assertEquals(Kind.QUAD, hole.kind());
        assertEquals(1.0, hole.middleX());
        assertNull(Holes.at(grid, 0, 0, 0, new Shapes(true, true, false)));
    }

    @Test
    void anOpenSideIsNoHole() {
        assertNull(Holes.at(new Grid().dig(0, 0).dig(1, 0).dig(2, 0), 0, 0, 0, ALL));
        assertNull(Holes.at(new Grid().dig(0, 0).set(0, -1, 0, Holes.OPEN), 0, 0, 0, ALL));
        assertNull(Holes.at(new Grid().dig(0, 0).dig(1, 0).dig(1, -1), 0, 0, 0, ALL));
    }

    @Test
    void anUnstandableNeighbourIsNoHole() {
        Grid grid = new Grid() {
            @Override
            public boolean room(int x, int y, int z) {
                return !(x == 1 && z == 0) && super.room(x, y, z);
            }
        }.dig(0, 0).dig(1, 0);
        assertNull(Holes.at(grid, 0, 0, 0, ALL));
    }

    @Test
    void containingFindsEveryCell() {
        Grid grid = new Grid().dig(0, 0).dig(1, 0).dig(0, 1).dig(1, 1);
        for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 1; z++) assertEquals(Kind.QUAD, Holes.containing(grid, x, 0, z, ALL).kind());
        }
        assertNull(Holes.containing(grid, 2, 0, 0, ALL));
    }
}
