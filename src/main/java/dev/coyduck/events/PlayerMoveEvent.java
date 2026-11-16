package dev.coyduck.events;

import net.minecraft.entity.MovementType;
import net.minecraft.util.math.Vec3d;

public final class PlayerMoveEvent extends Movement {
    private static final PlayerMoveEvent INSTANCE = new PlayerMoveEvent();

    public MovementType type;

    private PlayerMoveEvent() {}

    public static PlayerMoveEvent get(MovementType type, Vec3d movement) {
        INSTANCE.type = type;
        INSTANCE.reset(movement);
        return INSTANCE;
    }
}
