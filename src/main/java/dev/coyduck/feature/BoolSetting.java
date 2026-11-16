package dev.coyduck.feature;

public final class BoolSetting extends Setting<Boolean> {
    private BoolSetting(Builder builder) {
        super(builder);
    }

    public static final class Builder extends Setting.Builder<Builder, Boolean, BoolSetting> {
        public Builder() {
            super(false);
        }

        @Override
        public BoolSetting build() {
            return new BoolSetting(this);
        }
    }
}
