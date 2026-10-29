package dev.coyduck.utils;

import net.minecraft.entity.Entity;

import java.lang.reflect.Method;

/** Keeps an entity drawn when the EntityCulling mod would hide it behind blocks; does nothing without that mod. */
public final class Culling {
    private static final String CULLABLE = "dev.tr7zw.entityculling.access.Cullable";

    private static Method keepVisible;
    private static boolean checked;

    private Culling() {}

    /** Holds the entity visible for about a second, by the mod's own timeout; call it every tick. */
    public static void keep(Entity entity) {
        Method method = method();
        if (method == null) return;
        try {
            method.invoke(entity);
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
            keepVisible = null;
        }
    }

    private static Method method() {
        if (checked) return keepVisible;
        checked = true;
        try {
            keepVisible = Class.forName(CULLABLE).getMethod("setTimeout");
        } catch (ReflectiveOperationException ignored) {
            keepVisible = null;
        }
        return keepVisible;
    }
}
