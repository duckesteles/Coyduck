package dev.coyduck.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Settings {
    public static final String GENERAL = "General";

    private final List<SettingGroup> groups = new ArrayList<>();
    private SettingGroup defaultGroup;

    public SettingGroup getDefaultGroup() {
        if (defaultGroup == null) defaultGroup = createGroup(GENERAL);
        return defaultGroup;
    }

    public SettingGroup createGroup(String name) {
        SettingGroup group = new SettingGroup(name);
        groups.add(group);
        return group;
    }

    public List<SettingGroup> groups() {
        return Collections.unmodifiableList(groups);
    }
}
