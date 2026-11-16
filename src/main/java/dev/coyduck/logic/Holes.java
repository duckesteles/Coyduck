package dev.coyduck.logic;

public final class Holes {
    public static final int OPEN = 0;
    public static final int SOLID = 1;
    public static final int BEDROCK = 2;

    public interface Terrain {
        boolean room(int x, int y, int z);

        int wall(int x, int y, int z);
    }

    public enum Kind {
        SINGLE(1, 1),
        DOUBLE_X(2, 1),
        DOUBLE_Z(1, 2),
        QUAD(2, 2);

        public final int sizeX;
        public final int sizeZ;

        Kind(int sizeX, int sizeZ) {
            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
        }
    }

    public record Shapes(boolean singles, boolean doubles, boolean quads) {}

    public record Hole(Kind kind, int x, int y, int z, boolean safe) {
        public int maxX() {
            return x + kind.sizeX;
        }

        public int maxZ() {
            return z + kind.sizeZ;
        }

        public double middleX() {
            return x + kind.sizeX / 2.0;
        }

        public double middleZ() {
            return z + kind.sizeZ / 2.0;
        }

        public boolean contains(int cellX, int cellY, int cellZ) {
            return cellY == y && cellX >= x && cellX < maxX() && cellZ >= z && cellZ < maxZ();
        }
    }

    private Holes() {}

    public static Hole at(Terrain terrain, int x, int y, int z, Shapes shapes) {
        if (!terrain.room(x, y, z)) return null;
        int floor = terrain.wall(x, y - 1, z);
        int west = terrain.wall(x - 1, y, z);
        int north = terrain.wall(x, y, z - 1);
        if (floor == OPEN || west == OPEN || north == OPEN) return null;

        Walls walls = new Walls(terrain, y);
        walls.note(floor);
        walls.note(west);
        walls.note(north);

        int east = terrain.wall(x + 1, y, z);
        int south = terrain.wall(x, y, z + 1);
        if (east != OPEN && south != OPEN) {
            if (!shapes.singles) return null;
            walls.note(east);
            walls.note(south);
            return walls.hole(Kind.SINGLE, x, z);
        }

        boolean eastCell = east == OPEN && walls.cell(x + 1, z);
        boolean southCell = south == OPEN && walls.cell(x, z + 1);
        if (eastCell && southCell) {
            if (!shapes.quads || !walls.cell(x + 1, z + 1)) return null;
            if (!walls.closed(x + 1, z - 1) || !walls.closed(x + 2, z) || !walls.closed(x + 2, z + 1)) return null;
            if (!walls.closed(x + 1, z + 2) || !walls.closed(x, z + 2) || !walls.closed(x - 1, z + 1)) return null;
            return walls.hole(Kind.QUAD, x, z);
        }

        if (!shapes.doubles) return null;
        if (eastCell && south != OPEN) {
            walls.note(south);
            if (!walls.closed(x + 1, z - 1) || !walls.closed(x + 2, z) || !walls.closed(x + 1, z + 1)) return null;
            return walls.hole(Kind.DOUBLE_X, x, z);
        }
        if (southCell && east != OPEN) {
            walls.note(east);
            if (!walls.closed(x - 1, z + 1) || !walls.closed(x, z + 2) || !walls.closed(x + 1, z + 1)) return null;
            return walls.hole(Kind.DOUBLE_Z, x, z);
        }
        return null;
    }

    public static Hole containing(Terrain terrain, int x, int y, int z, Shapes shapes) {
        for (int dx = 0; dx <= 1; dx++) {
            for (int dz = 0; dz <= 1; dz++) {
                Hole hole = at(terrain, x - dx, y, z - dz, shapes);
                if (hole != null && hole.contains(x, y, z)) return hole;
            }
        }
        return null;
    }

    private static final class Walls {
        private final Terrain terrain;
        private final int y;
        private boolean safe = true;

        Walls(Terrain terrain, int y) {
            this.terrain = terrain;
            this.y = y;
        }

        boolean cell(int x, int z) {
            return terrain.room(x, y, z) && note(terrain.wall(x, y - 1, z));
        }

        boolean closed(int x, int z) {
            return note(terrain.wall(x, y, z));
        }

        boolean note(int kind) {
            safe &= kind == BEDROCK;
            return kind != OPEN;
        }

        Hole hole(Kind kind, int x, int z) {
            return new Hole(kind, x, y, z, safe);
        }
    }
}
