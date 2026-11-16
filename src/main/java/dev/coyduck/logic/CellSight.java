package dev.coyduck.logic;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

public final class CellSight {
    private static final int MAX_SPAN = 8;

    private CellSight() {}

    public static boolean sees(BlockView view, BlockPos cell, int eyeX, int eyeY, int eyeZ) {
        int dx = Integer.signum(eyeX - cell.getX());
        int dy = Integer.signum(eyeY - cell.getY());
        int dz = Integer.signum(eyeZ - cell.getZ());
        int nx = Math.abs(eyeX - cell.getX());
        int ny = Math.abs(eyeY - cell.getY());
        int nz = Math.abs(eyeZ - cell.getZ());
        if (nx > MAX_SPAN || ny > MAX_SPAN || nz > MAX_SPAN) return false;

        boolean[] reach = new boolean[(nx + 1) * (ny + 1) * (nz + 1)];
        BlockPos.Mutable at = new BlockPos.Mutable();
        for (int i = 0; i <= nx; i++) {
            for (int j = 0; j <= ny; j++) {
                for (int k = 0; k <= nz; k++) {
                    boolean start = i == 0 && j == 0 && k == 0;
                    boolean from = start
                        || i > 0 && reach[index(i - 1, j, k, ny, nz)]
                        || j > 0 && reach[index(i, j - 1, k, ny, nz)]
                        || k > 0 && reach[index(i, j, k - 1, ny, nz)];
                    if (!from) continue;
                    boolean end = i == nx && j == ny && k == nz;
                    at.set(cell.getX() + dx * i, cell.getY() + dy * j, cell.getZ() + dz * k);
                    reach[index(i, j, k, ny, nz)] = start || end || view.getBlockState(at).getCollisionShape(view, at).isEmpty();
                }
            }
        }
        return reach[index(nx, ny, nz, ny, nz)];
    }

    private static int index(int i, int j, int k, int ny, int nz) {
        return (i * (ny + 1) + j) * (nz + 1) + k;
    }
}
