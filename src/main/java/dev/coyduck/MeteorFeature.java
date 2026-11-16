package dev.coyduck;

import dev.coyduck.feature.BlockListSetting;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.Feature;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.ItemListSetting;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.feature.Settings;
import dev.coyduck.feature.Shell;
import dev.coyduck.feature.StringListSetting;
import dev.coyduck.feature.StringSetting;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.Module;

public final class MeteorFeature extends Module {
    private final Feature feature;

    public MeteorFeature(Feature feature) {
        super(Coyduck.CATEGORY, feature.name, feature.description);
        this.feature = feature;
        for (SettingGroup group : feature.settings.groups()) {
            var target = group.name.equals(Settings.GENERAL) ? settings.getDefaultGroup() : settings.createGroup(group.name);
            for (Setting<?> setting : group.settings()) target.add(mirror(setting));
        }
        feature.bind(new Shell() {
            @Override
            public boolean active() {
                return isActive();
            }

            @Override
            public void toggle() {
                MeteorFeature.this.toggle();
            }

            @Override
            public void info(String message, Object... args) {
                MeteorFeature.this.info(message, args);
            }

            @Override
            public void warning(String message, Object... args) {
                MeteorFeature.this.warning(message, args);
            }

            @Override
            public void error(String message, Object... args) {
                MeteorFeature.this.error(message, args);
            }
        });
    }

    @Override
    public void onActivate() {
        MeteorClient.EVENT_BUS.subscribe(feature);
        feature.onActivate();
    }

    @Override
    public void onDeactivate() {
        MeteorClient.EVENT_BUS.unsubscribe(feature);
        feature.onDeactivate();
    }

    @Override
    public String getInfoString() {
        return feature.getInfoString();
    }

    private static meteordevelopment.meteorclient.settings.Setting<?> mirror(Setting<?> setting) {
        return switch (setting) {
            case BoolSetting s -> link(s, new meteordevelopment.meteorclient.settings.BoolSetting.Builder()
                .name(s.name).description(s.description).defaultValue(s.getDefaultValue()).visible(s::isVisible).onChanged(s.onChanged()).build());
            case IntSetting s -> {
                var builder = new meteordevelopment.meteorclient.settings.IntSetting.Builder()
                    .name(s.name).description(s.description).defaultValue(s.getDefaultValue()).visible(s::isVisible).onChanged(s.onChanged())
                    .range(s.min, s.max).sliderRange(s.sliderMin, s.sliderMax);
                yield link(s, s.noSlider ? builder.noSlider().build() : builder.build());
            }
            case DoubleSetting s -> {
                var builder = new meteordevelopment.meteorclient.settings.DoubleSetting.Builder()
                    .name(s.name).description(s.description).defaultValue(s.getDefaultValue()).visible(s::isVisible).onChanged(s.onChanged())
                    .range(s.min, s.max).sliderRange(s.sliderMin, s.sliderMax).decimalPlaces(s.decimalPlaces);
                yield link(s, s.noSlider ? builder.noSlider().build() : builder.build());
            }
            case EnumSetting<?> s -> mirrorEnum(s);
            case StringSetting s -> {
                var builder = new meteordevelopment.meteorclient.settings.StringSetting.Builder()
                    .name(s.name).description(s.description).defaultValue(s.getDefaultValue()).visible(s::isVisible).onChanged(s.onChanged());
                yield link(s, s.wide ? builder.wide().build() : builder.build());
            }
            case StringListSetting s -> link(s, new meteordevelopment.meteorclient.settings.StringListSetting.Builder()
                .name(s.name).description(s.description).defaultValue(s.getDefaultValue()).visible(s::isVisible).onChanged(s.onChanged()).build());
            case BlockListSetting s -> {
                var builder = new meteordevelopment.meteorclient.settings.BlockListSetting.Builder()
                    .name(s.name).description(s.description).defaultValue(s.getDefaultValue()).visible(s::isVisible).onChanged(s.onChanged());
                yield link(s, s.filter != null ? builder.filter(s.filter).build() : builder.build());
            }
            case ItemListSetting s -> {
                var builder = new meteordevelopment.meteorclient.settings.ItemListSetting.Builder()
                    .name(s.name).description(s.description).defaultValue(s.getDefaultValue()).visible(s::isVisible).onChanged(s.onChanged());
                yield link(s, s.filter != null ? builder.filter(s.filter).build() : builder.build());
            }
            default -> throw new IllegalArgumentException("No Meteor mirror for " + setting.getClass().getSimpleName());
        };
    }

    private static <E extends Enum<?>> meteordevelopment.meteorclient.settings.Setting<E> mirrorEnum(EnumSetting<E> s) {
        return link(s, new meteordevelopment.meteorclient.settings.EnumSetting.Builder<E>()
            .name(s.name).description(s.description).defaultValue(s.getDefaultValue()).visible(s::isVisible).onChanged(s.onChanged()).build());
    }

    private static <T> meteordevelopment.meteorclient.settings.Setting<T> link(Setting<T> ours, meteordevelopment.meteorclient.settings.Setting<T> theirs) {
        ours.bind(new Setting.Backend<>() {
            @Override
            public T get() {
                return theirs.get();
            }

            @Override
            public boolean set(T value) {
                return theirs.set(value);
            }

            @Override
            public void reset() {
                theirs.reset();
            }
        });
        return theirs;
    }
}
