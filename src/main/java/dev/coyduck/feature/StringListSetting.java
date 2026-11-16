package dev.coyduck.feature;

public final class StringListSetting extends ListSetting<String> {
    private StringListSetting(Builder builder) {
        super(builder);
    }

    public static final class Builder extends ListSetting.Builder<Builder, String, StringListSetting> {
        @Override
        public StringListSetting build() {
            return new StringListSetting(this);
        }
    }
}
