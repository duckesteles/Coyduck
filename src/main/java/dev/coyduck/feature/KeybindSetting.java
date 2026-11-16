package dev.coyduck.feature;

public final class KeybindSetting extends Setting<Bind> {
    public final Runnable action;

    private KeybindSetting(Builder builder) {
        super(builder);
        action = builder.action;
    }

    public static final class Builder extends Setting.Builder<Builder, Bind, KeybindSetting> {
        private Runnable action;

        public Builder() {
            super(Bind.NONE);
        }

        public Builder action(Runnable value) {
            action = value;
            return this;
        }

        @Override
        public KeybindSetting build() {
            return new KeybindSetting(this);
        }
    }
}
