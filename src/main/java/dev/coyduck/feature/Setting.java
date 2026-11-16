package dev.coyduck.feature;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public abstract sealed class Setting<T> permits BoolSetting, IntSetting, DoubleSetting, EnumSetting, StringSetting, ListSetting, ColorSetting, KeybindSetting {
    public interface Backend<T> {
        T get();

        boolean set(T value);

        void reset();

        default Object handle() {
            return null;
        }
    }

    public final String name;
    public final String description;
    private final T defaultValue;
    private final BooleanSupplier visible;
    private final Consumer<T> onChanged;
    private T value;
    private Backend<T> backend;

    protected Setting(Builder<?, T, ?> builder) {
        name = builder.name;
        description = builder.description;
        defaultValue = Objects.requireNonNull(builder.defaultValue, builder.name);
        visible = builder.visible;
        onChanged = builder.onChanged;
        value = fresh(defaultValue);
    }

    public T get() {
        return backend != null ? backend.get() : value;
    }

    public boolean set(T next) {
        if (backend != null) return backend.set(next);
        if (!isValueValid(next)) return false;
        value = next;
        changed();
        return true;
    }

    public void reset() {
        if (backend != null) {
            backend.reset();
            return;
        }
        value = fresh(defaultValue);
        changed();
    }

    public T getDefaultValue() {
        return defaultValue;
    }

    public boolean isVisible() {
        return visible == null || visible.getAsBoolean();
    }

    public Consumer<T> onChanged() {
        return onChanged;
    }

    public Object handle() {
        return backend == null ? null : backend.handle();
    }

    protected final Backend<T> backend() {
        return backend;
    }

    public void bind(Backend<T> target) {
        backend = target;
        if (!Objects.equals(value, defaultValue)) target.set(value);
    }

    protected boolean isValueValid(T candidate) {
        return candidate != null;
    }

    protected T fresh(T base) {
        return base;
    }

    private void changed() {
        if (onChanged != null) onChanged.accept(value);
    }

    @SuppressWarnings("unchecked")
    public abstract static class Builder<B extends Builder<B, V, S>, V, S extends Setting<V>> {
        String name = "undefined";
        String description = "";
        V defaultValue;
        BooleanSupplier visible;
        Consumer<V> onChanged;

        protected Builder(V defaultValue) {
            this.defaultValue = defaultValue;
        }

        public B name(String value) {
            name = value;
            return (B) this;
        }

        public B description(String value) {
            description = value;
            return (B) this;
        }

        public B defaultValue(V value) {
            defaultValue = value;
            return (B) this;
        }

        public B visible(BooleanSupplier value) {
            visible = value;
            return (B) this;
        }

        public B onChanged(Consumer<V> value) {
            onChanged = value;
            return (B) this;
        }

        public abstract S build();
    }
}
