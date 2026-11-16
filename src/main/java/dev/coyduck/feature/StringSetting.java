package dev.coyduck.feature;

public final class StringSetting extends Setting<String> {
    public final boolean wide;

    private StringSetting(Builder builder) {
        super(builder);
        wide = builder.wide;
    }

    public static final class Builder extends Setting.Builder<Builder, String, StringSetting> {
        private boolean wide;

        public Builder() {
            super("");
        }

        public Builder wide() {
            wide = true;
            return this;
        }

        @Override
        public StringSetting build() {
            return new StringSetting(this);
        }
    }
}
