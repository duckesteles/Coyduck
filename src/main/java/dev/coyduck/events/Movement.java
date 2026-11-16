package dev.coyduck.events;

import net.minecraft.util.math.Vec3d;

public abstract class Movement {
    private Vec3d original;
    private double x;
    private double y;
    private double z;
    private boolean changed;

    final void reset(Vec3d movement) {
        original = movement;
        x = movement.x;
        y = movement.y;
        z = movement.z;
        changed = false;
    }

    public final double x() {
        return x;
    }

    public final double y() {
        return y;
    }

    public final double z() {
        return z;
    }

    public final void set(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        changed = true;
    }

    public final void setXZ(double x, double z) {
        set(x, y, z);
    }

    public final void setY(double y) {
        set(x, y, z);
    }

    public final Vec3d result() {
        Vec3d out = changed ? new Vec3d(x, y, z) : original;
        original = null;
        return out;
    }
}
