package dev.coyduck.events;

import net.minecraft.entity.Entity;

public final class AttackEntityEvent extends Cancellable {
    private static final AttackEntityEvent INSTANCE = new AttackEntityEvent();

    public Entity entity;

    private AttackEntityEvent() {}

    public static AttackEntityEvent get(Entity entity) {
        INSTANCE.setCancelled(false);
        INSTANCE.entity = entity;
        return INSTANCE;
    }
}
