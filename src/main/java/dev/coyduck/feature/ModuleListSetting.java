package dev.coyduck.feature;

public final class ModuleListSetting extends ListSetting<String> {
    private ModuleListSetting(Builder builder) {
        super(builder);
    }

    public static final class Builder extends ListSetting.Builder<Builder, String, ModuleListSetting> {
        @Override
        public ModuleListSetting build() {
            return new ModuleListSetting(this);
        }
    }
}
