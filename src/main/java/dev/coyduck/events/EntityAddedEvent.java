package dev.coyduck.events;

import net.minecraft.entity.Entity;

public final class EntityAddedEvent {
    private static final EntityAddedEvent INSTANCE = new EntityAddedEvent();

    public Entity entity;

    private EntityAddedEvent() {}

    public static EntityAddedEvent get(Entity entity) {
        INSTANCE.entity = entity;
        return INSTANCE;
    }
}
