package dev.coyduck.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class Features {
    private static final List<Feature> all = new ArrayList<>();
    private static final Map<Class<?>, Feature> byType = new IdentityHashMap<>();

    private Features() {}

    public static <F extends Feature> F add(F feature) {
        if (byType.putIfAbsent(feature.getClass(), feature) != null) throw new IllegalStateException("Duplicate feature " + feature.name);
        all.add(feature);
        return feature;
    }

    public static <F extends Feature> F get(Class<F> type) {
        return type.cast(byType.get(type));
    }

    public static boolean isActive(Class<? extends Feature> type) {
        Feature feature = byType.get(type);
        return feature != null && feature.isActive();
    }

    public static List<Feature> all() {
        return Collections.unmodifiableList(all);
    }
}
