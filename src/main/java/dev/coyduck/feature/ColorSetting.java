package dev.coyduck.feature;

public final class ColorSetting extends Setting<Rgba> {
    public interface Live extends Backend<Rgba> {
        int argb();
    }

    private ColorSetting(Builder builder) {
        super(builder);
    }

    public int argb() {
        return backend() instanceof Live live ? live.argb() : get().packed();
    }

    @Override
    protected boolean isValueValid(Rgba candidate) {
        return candidate != null && within(candidate.r()) && within(candidate.g()) && within(candidate.b()) && within(candidate.a());
    }

    private static boolean within(int channel) {
        return channel >= 0 && channel <= 255;
    }

    public static final class Builder extends Setting.Builder<Builder, Rgba, ColorSetting> {
        public Builder() {
            super(new Rgba(255, 255, 255, 255));
        }

        @Override
        public ColorSetting build() {
            return new ColorSetting(this);
        }
    }
}
