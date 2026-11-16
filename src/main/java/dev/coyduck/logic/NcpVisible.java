package dev.coyduck.logic;

public final class NcpVisible {
    public interface Solid {
        boolean at(int x, int y, int z);
    }

    public static final int X = 0;
    public static final int Y = 1;
    public static final int Z = 2;
    private static final int NONE = -1;

    private static final int MAX_STEPS = 30;
    private static final int MAX_WALK = 64;

    private final int[] visited = new int[MAX_WALK * 3];
    private final int[] neighbors = new int[9];
    private int visitedCount;
    private int priority;
    private int excludeAxis;
    private int excludeSign;
    private int steps;

    public boolean sees(Solid solid, int blockX, int blockY, int blockZ, int faceAxis, double eyeX, double eyeY, double eyeZ) {
        int eyeBlockX = floor(eyeX);
        int eyeBlockY = floor(eyeY);
        int eyeBlockZ = floor(eyeZ);
        if (blockX == eyeBlockX && blockY == eyeBlockY && blockZ == eyeBlockZ) return true;

        boolean collides = trace(solid, blockX, blockY, blockZ, eyeX, eyeY, eyeZ);
        if (!collides) return steps <= MAX_STEPS;

        visitedCount = 0;
        priority = faceAxis;
        excludeAxis = NONE;
        int curX = blockX;
        int curY = blockY;
        int curZ = blockZ;
        boolean moved;
        do {
            moved = false;
            int count = neighbors(curX, curY, curZ, eyeX, eyeY, eyeZ);
            for (int i = 0; i < count; i++) {
                int nx = neighbors[i * 3];
                int ny = neighbors[i * 3 + 1];
                int nz = neighbors[i * 3 + 2];
                if (!canPass(solid, curX, curY, curZ, nx, ny, nz) || !correctDir(ny, blockY, eyeBlockY) || visited(nx, ny, nz)) continue;
                if (nx == eyeBlockX && ny == eyeBlockY && nz == eyeBlockZ) return true;
                if (visitedCount == MAX_WALK) return false;
                visit(nx, ny, nz);
                collides = trace(solid, nx, ny, nz, eyeX, eyeY, eyeZ);
                curX = nx;
                curY = ny;
                curZ = nz;
                moved = true;
                break;
            }
        } while (collides && moved);
        return !collides;
    }

    private int neighbors(int x, int y, int z, double eyeX, double eyeY, double eyeZ) {
        double dx = eyeX - x;
        double dy = eyeY - y;
        double dz = eyeZ - z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double dirY = length == 0 ? 0 : dy / length;
        int stepX = (int) Math.signum(dx);
        int stepY = (int) Math.signum(dy);
        int stepZ = (int) Math.signum(dz);

        int lead = priority;
        priority = NONE;
        int axis = excludeAxis;
        int sign = excludeSign;
        excludeAxis = NONE;
        boolean allowX = !(axis == X && sign == stepX && stepX != 0);
        boolean allowY = !(axis == Y && sign == stepY && stepY != 0);
        boolean allowZ = !(axis == Z && sign == stepZ && stepZ != 0);

        int count = 0;
        if (lead == X) {
            count = add(count, x + stepX, y, z);
            if (allowZ) count = add(count, x, y, z + stepZ);
            if (allowY) count = add(count, x, y + stepY, z);
            return count;
        }
        if (lead == Y) {
            count = add(count, x, y + stepY, z);
            if (allowX) count = add(count, x + stepX, y, z);
            if (allowZ) count = add(count, x, y, z + stepZ);
            return count;
        }
        if (lead == Z) {
            count = add(count, x, y, z + stepZ);
            if (allowX) count = add(count, x + stepX, y, z);
            if (allowY) count = add(count, x, y + stepY, z);
            return count;
        }

        double toY = manhattan(x, y + stepY, z, eyeX, eyeY, eyeZ);
        double toZ = manhattan(x, y, z + stepZ, eyeX, eyeY, eyeZ);
        double toX = manhattan(x + stepX, y, z, eyeX, eyeY, eyeZ);
        boolean xFirst = toX < toZ;
        if (toY <= toX && toY <= toZ && Math.abs(dirY) >= 0.5) {
            if (allowY) count = add(count, x, y + stepY, z);
            return horizontals(count, x, y, z, stepX, stepZ, allowX, allowZ, xFirst);
        }
        count = horizontals(count, x, y, z, stepX, stepZ, allowX, allowZ, xFirst);
        if (allowY) count = add(count, x, y + stepY, z);
        return count;
    }

    private int horizontals(int count, int x, int y, int z, int stepX, int stepZ, boolean allowX, boolean allowZ, boolean xFirst) {
        if (xFirst) {
            if (allowX) count = add(count, x + stepX, y, z);
            if (allowZ) count = add(count, x, y, z + stepZ);
        } else {
            if (allowZ) count = add(count, x, y, z + stepZ);
            if (allowX) count = add(count, x + stepX, y, z);
        }
        return count;
    }

    private int add(int count, int x, int y, int z) {
        neighbors[count * 3] = x;
        neighbors[count * 3 + 1] = y;
        neighbors[count * 3 + 2] = z;
        return count + 1;
    }

    private boolean canPass(Solid solid, int lastX, int lastY, int lastZ, int x, int y, int z) {
        if (!solid.at(x, y, z)) return true;
        if (solid.at(lastX, lastY, lastZ)) {
            excludeAxis = z != lastZ ? X : Z;
            excludeSign = -1;
        }
        return false;
    }

    private static boolean correctDir(int neighbor, int block, int eyeBlock) {
        int d = eyeBlock - block;
        if (d > 0) return neighbor <= eyeBlock;
        if (d < 0) return neighbor >= eyeBlock;
        return neighbor == eyeBlock;
    }

    private boolean visited(int x, int y, int z) {
        for (int i = 0; i < visitedCount; i++) {
            if (visited[i * 3] == x && visited[i * 3 + 1] == y && visited[i * 3 + 2] == z) return true;
        }
        return false;
    }

    private void visit(int x, int y, int z) {
        visited[visitedCount * 3] = x;
        visited[visitedCount * 3 + 1] = y;
        visited[visitedCount * 3 + 2] = z;
        visitedCount++;
    }

    private boolean trace(Solid solid, double x0, double y0, double z0, double x1, double y1, double z1) {
        steps = 0;
        if (axis(solid, Y, x0, y0, z0, y1)) return true;
        if (axis(solid, X, x0, y1, z0, x1)) return true;
        return axis(solid, Z, x1, y1, z0, z1);
    }

    private boolean axis(Solid solid, int axis, double x, double y, double z, double end) {
        double start = axis == X ? x : axis == Y ? y : z;
        if (start == end) return false;
        int increment = start < end ? 1 : -1;
        int last = floor(end) + increment;
        int fixedA = floor(axis == X ? y : x);
        int fixedB = floor(axis == Z ? y : z);
        double low = Math.min(start, end);
        double high = Math.max(start, end);
        for (int i = floor(start); i != last; i += increment) {
            if (++steps > MAX_STEPS) return false;
            int bx = axis == X ? i : fixedA;
            int by = axis == Y ? i : axis == X ? fixedA : fixedB;
            int bz = axis == Z ? i : fixedB;
            if (!solid.at(bx, by, bz)) continue;
            double minX = axis == X ? low : x;
            double maxX = axis == X ? high : x;
            double minY = axis == Y ? low : y;
            double maxY = axis == Y ? high : y;
            double minZ = axis == Z ? low : z;
            double maxZ = axis == Z ? high : z;
            if (minX > bx + 1 || maxX < bx || minY > by + 1 || maxY < by || minZ > bz + 1 || maxZ < bz) continue;
            return true;
        }
        return false;
    }

    private static double manhattan(int x, int y, int z, double eyeX, double eyeY, double eyeZ) {
        return Math.abs(x - eyeX) + Math.abs(y - eyeY) + Math.abs(z - eyeZ);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
