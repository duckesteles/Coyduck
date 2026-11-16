package dev.coyduck.events;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

public final class EntityMoveEvent extends Movement {
    private static final EntityMoveEvent INSTANCE = new EntityMoveEvent();

    public Entity entity;

    private EntityMoveEvent() {}

    public static EntityMoveEvent get(Entity entity, Vec3d movement) {
        INSTANCE.entity = entity;
        INSTANCE.reset(movement);
        return INSTANCE;
    }
}
