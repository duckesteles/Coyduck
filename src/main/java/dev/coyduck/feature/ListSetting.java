package dev.coyduck.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public abstract class ListSetting<E> extends Setting<List<E>> {
    public final Predicate<E> filter;

    protected ListSetting(Builder<?, E, ?> builder) {
        super(builder);
        filter = builder.filter;
    }

    @Override
    protected List<E> fresh(List<E> base) {
        return new ArrayList<>(base);
    }

    @SuppressWarnings("unchecked")
    public abstract static class Builder<B extends Builder<B, E, S>, E, S extends ListSetting<E>> extends Setting.Builder<B, List<E>, S> {
        private Predicate<E> filter;

        protected Builder() {
            super(new ArrayList<>());
        }

        @SafeVarargs
        public final B defaultValue(E... values) {
            return defaultValue(List.of(values));
        }

        public B filter(Predicate<E> value) {
            filter = value;
            return (B) this;
        }
    }
}
