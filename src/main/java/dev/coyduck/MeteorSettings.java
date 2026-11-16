package dev.coyduck;

import dev.coyduck.feature.Bind;
import dev.coyduck.feature.BlockListSetting;
import dev.coyduck.feature.BoolSetting;
import dev.coyduck.feature.ColorSetting;
import dev.coyduck.feature.DoubleSetting;
import dev.coyduck.feature.EnumSetting;
import dev.coyduck.feature.IntSetting;
import dev.coyduck.feature.ItemListSetting;
import dev.coyduck.feature.KeybindSetting;
import dev.coyduck.feature.ModuleListSetting;
import dev.coyduck.feature.Rgba;
import dev.coyduck.feature.Setting;
import dev.coyduck.feature.SettingGroup;
import dev.coyduck.feature.Settings;
import dev.coyduck.feature.StringListSetting;
import dev.coyduck.feature.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;

public final class MeteorSettings {
    private MeteorSettings() {}

    public static void mirror(Settings ours, meteordevelopment.meteorclient.settings.Settings theirs) {
        for (SettingGroup group : ours.groups()) {
            var target = group.name.equals(Settings.GENERAL) ? theirs.getDefaultGroup() : theirs.createGroup(group.name);
            for (Setting<?> setting : group.settings()) target.add(mirror(setting));
        }
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
            case BlockListSetting s -> link(s, new meteordevelopment.meteorclient.settings.BlockListSetting.Builder()
                .name(s.name).description(s.description).defaultValue(s.getDefaultValue()).visible(s::isVisible).onChanged(s.onChanged()).filter(s.filter).build());
            case ItemListSetting s -> link(s, new meteordevelopment.meteorclient.settings.ItemListSetting.Builder()
                .name(s.name).description(s.description).defaultValue(s.getDefaultValue()).visible(s::isVisible).onChanged(s.onChanged()).filter(s.filter).build());
            case ModuleListSetting s -> mirrorModules(s);
            case ColorSetting s -> mirrorColor(s);
            case KeybindSetting s -> mirrorKeybind(s);
        };
    }

    private static meteordevelopment.meteorclient.settings.Setting<SettingColor> mirrorColor(ColorSetting s) {
        var theirs = new meteordevelopment.meteorclient.settings.ColorSetting.Builder()
            .name(s.name).description(s.description).defaultValue(meteor(s.getDefaultValue())).visible(s::isVisible)
            .onChanged(color -> {
                if (s.onChanged() != null) s.onChanged().accept(rgba(color));
            })
            .build();
        s.bind(new ColorSetting.Live() {
            private Rgba last;
            private int lastPacked;

            @Override
            public Rgba get() {
                SettingColor color = theirs.get();
                int packed = color.getPacked();
                if (last == null || packed != lastPacked || color.rainbow != last.rainbow()) {
                    last = rgba(color);
                    lastPacked = packed;
                }
                return last;
            }

            @Override
            public boolean set(Rgba value) {
                return theirs.set(meteor(value));
            }

            @Override
            public void reset() {
                theirs.reset();
            }

            @Override
            public int argb() {
                return theirs.get().getPacked();
            }
        });
        return theirs;
    }

    private static meteordevelopment.meteorclient.settings.Setting<Keybind> mirrorKeybind(KeybindSetting s) {
        var theirs = new meteordevelopment.meteorclient.settings.KeybindSetting.Builder()
            .name(s.name).description(s.description).defaultValue(meteor(s.getDefaultValue())).visible(s::isVisible).action(s.action)
            .onChanged(keybind -> {
                if (s.onChanged() != null) s.onChanged().accept(bind(keybind));
            })
            .build();
        s.bind(new Setting.Backend<>() {
            @Override
            public Bind get() {
                return bind(theirs.get());
            }

            @Override
            public boolean set(Bind value) {
                return theirs.set(meteor(value));
            }

            @Override
            public void reset() {
                theirs.reset();
            }
        });
        return theirs;
    }

    private static meteordevelopment.meteorclient.settings.Setting<List<Module>> mirrorModules(ModuleListSetting s) {
        var theirs = new meteordevelopment.meteorclient.settings.ModuleListSetting.Builder()
            .name(s.name).description(s.description).defaultValue(modules(s.getDefaultValue())).visible(s::isVisible)
            .onChanged(list -> {
                if (s.onChanged() != null) s.onChanged().accept(s.get());
            })
            .build();
        List<String> names = new AbstractList<>() {
            @Override
            public String get(int index) {
                return theirs.get().get(index).name;
            }

            @Override
            public int size() {
                return theirs.get().size();
            }

            @Override
            public int indexOf(Object name) {
                List<Module> modules = theirs.get();
                for (int i = 0; i < modules.size(); i++) {
                    if (modules.get(i).name.equals(name)) return i;
                }
                return -1;
            }

            @Override
            public boolean contains(Object name) {
                return indexOf(name) != -1;
            }
        };
        s.bind(new Setting.Backend<>() {
            @Override
            public List<String> get() {
                return names;
            }

            @Override
            public boolean set(List<String> value) {
                return theirs.set(modules(value));
            }

            @Override
            public void reset() {
                theirs.reset();
            }
        });
        return theirs;
    }

    private static List<Module> modules(List<String> names) {
        List<Module> out = new ArrayList<>(names.size());
        for (String name : names) {
            Module module = Modules.get().get(name);
            if (module != null) out.add(module);
        }
        return out;
    }

    private static SettingColor meteor(Rgba color) {
        return new SettingColor(color.r(), color.g(), color.b(), color.a(), color.rainbow());
    }

    private static Rgba rgba(SettingColor color) {
        return new Rgba(color.r, color.g, color.b, color.a, color.rainbow);
    }

    private static Keybind meteor(Bind bind) {
        if (!bind.isSet()) return Keybind.none();
        return bind.key() ? Keybind.fromKeys(bind.value(), bind.modifiers()) : Keybind.fromButton(bind.value());
    }

    private static Bind bind(Keybind keybind) {
        if (!keybind.isSet()) return Bind.NONE;
        return new Bind(keybind.isKey(), keybind.getValue(), keybind.toTag().getInt("modifiers", 0));
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
