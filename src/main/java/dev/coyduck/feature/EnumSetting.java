package dev.coyduck.feature;

public final class EnumSetting<E extends Enum<?>> extends Setting<E> {
    private EnumSetting(Builder<E> builder) {
        super(builder);
    }

    public static final class Builder<E extends Enum<?>> extends Setting.Builder<Builder<E>, E, EnumSetting<E>> {
        public Builder() {
            super(null);
        }

        @Override
        public EnumSetting<E> build() {
            return new EnumSetting<>(this);
        }
    }
}
