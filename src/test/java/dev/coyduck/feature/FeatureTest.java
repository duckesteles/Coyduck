package dev.coyduck.feature;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureTest {
    enum Mode { Fast, Safe }

    static final class Sample extends Feature {
        final SettingGroup general = settings.getDefaultGroup();
        final SettingGroup extra = settings.createGroup("Extra");
        final List<Boolean> changes = new ArrayList<>();
        final Setting<Boolean> flag = general.add(new BoolSetting.Builder().name("flag").description("A flag.").defaultValue(true).onChanged(changes::add).build());
        final Setting<Integer> count = general.add(new IntSetting.Builder().name("count").defaultValue(3).range(1, 5).sliderRange(1, 4).build());
        final Setting<Double> reach = extra.add(new DoubleSetting.Builder().name("reach").defaultValue(4.5).range(0, 6).build());
        final Setting<Mode> mode = extra.add(new EnumSetting.Builder<Mode>().name("mode").defaultValue(Mode.Safe).visible(flag::get).build());
        final Setting<List<String>> names = extra.add(new StringListSetting.Builder().name("names").defaultValue("a", "b").build());

        Sample() {
            super("sample", "A sample.");
        }
    }

    static final class Recorder implements Shell {
        boolean active;
        final List<String> said = new ArrayList<>();

        @Override
        public boolean active() {
            return active;
        }

        @Override
        public void toggle() {
            active = !active;
        }

        @Override
        public void info(String message, Object... args) {
            said.add("info " + String.format(message, args));
        }

        @Override
        public void error(String message, Object... args) {
            said.add("error " + String.format(message, args));
        }
    }

    @Test
    void defaultsAndGroupsKeepDeclarationOrder() {
        Sample sample = new Sample();
        assertEquals(List.of(Settings.GENERAL, "Extra"), sample.settings.groups().stream().map(group -> group.name).toList());
        assertSame(sample.general, sample.settings.getDefaultGroup());
        assertEquals(List.of("flag", "count"), sample.general.settings().stream().map(setting -> setting.name).toList());
        assertTrue(sample.flag.get());
        assertEquals(3, sample.count.get());
        assertEquals(4.5, sample.reach.get());
        assertEquals(Mode.Safe, sample.mode.get());
    }

    @Test
    void setNotifiesAndResetRestores() {
        Sample sample = new Sample();
        assertTrue(sample.flag.set(false));
        sample.flag.reset();
        assertEquals(List.of(false, true), sample.changes);
        assertTrue(sample.flag.get());
    }

    @Test
    void numbersRejectValuesOutsideTheirRange() {
        Sample sample = new Sample();
        assertFalse(sample.count.set(6));
        assertFalse(sample.count.set(0));
        assertTrue(sample.count.set(5));
        assertEquals(5, sample.count.get());
        assertFalse(sample.reach.set(6.01));
        assertEquals(4.5, sample.reach.get());
        IntSetting count = (IntSetting) sample.count;
        assertEquals(1, count.sliderMin);
        assertEquals(4, count.sliderMax);
    }

    @Test
    void visibilityFollowsItsSupplier() {
        Sample sample = new Sample();
        assertTrue(sample.mode.isVisible());
        sample.flag.set(false);
        assertFalse(sample.mode.isVisible());
        assertTrue(sample.flag.isVisible());
    }

    @Test
    void listsNeverShareTheDefault() {
        Sample sample = new Sample();
        sample.names.get().add("c");
        assertEquals(List.of("a", "b"), sample.names.getDefaultValue());
        sample.names.reset();
        assertEquals(List.of("a", "b"), sample.names.get());
        assertNotSame(sample.names.getDefaultValue(), sample.names.get());
    }

    @Test
    void boundSettingsReadAndWriteThroughTheBackend() {
        Sample sample = new Sample();
        boolean[] stored = {false};
        int[] resets = {0};
        sample.flag.bind(new Setting.Backend<>() {
            @Override
            public Boolean get() {
                return stored[0];
            }

            @Override
            public boolean set(Boolean value) {
                stored[0] = value;
                return true;
            }

            @Override
            public void reset() {
                resets[0]++;
            }
        });
        assertFalse(sample.flag.get());
        sample.flag.set(true);
        assertTrue(stored[0]);
        sample.flag.reset();
        assertEquals(1, resets[0]);
        assertTrue(sample.changes.isEmpty());
    }

    @Test
    void shellDrivesStateAndMessages() {
        Sample sample = new Sample();
        assertFalse(sample.isActive());
        sample.toggle();
        assertFalse(sample.isActive());
        assertNull(sample.getInfoString());

        Recorder shell = new Recorder();
        sample.bind(shell);
        sample.toggle();
        assertTrue(sample.isActive());
        sample.info("hit %d", 3);
        sample.error("broke");
        sample.warning("ignored");
        assertEquals(List.of("info hit 3", "error broke"), shell.said);
    }

    @Test
    void registryFindsFeaturesByType() {
        final class Unique extends Feature {
            Unique() {
                super("unique", "");
            }
        }
        Unique unique = Features.add(new Unique());
        assertSame(unique, Features.get(Unique.class));
        assertFalse(Features.isActive(Unique.class));
        assertTrue(Features.all().contains(unique));
        assertThrows(IllegalStateException.class, () -> Features.add(new Unique()));
    }
}
