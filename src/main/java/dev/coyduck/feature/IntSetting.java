package dev.coyduck.feature;

public final class IntSetting extends Setting<Integer> {
    public final int min;
    public final int max;
    public final int sliderMin;
    public final int sliderMax;
    public final boolean noSlider;

    private IntSetting(Builder builder) {
        super(builder);
        min = builder.min;
        max = builder.max;
        sliderMin = Math.max(builder.sliderMin, builder.min);
        sliderMax = Math.min(builder.sliderMax, builder.max);
        noSlider = builder.noSlider;
    }

    @Override
    protected boolean isValueValid(Integer candidate) {
        return candidate != null && candidate >= min && candidate <= max;
    }

    public static final class Builder extends Setting.Builder<Builder, Integer, IntSetting> {
        private int min = Integer.MIN_VALUE;
        private int max = Integer.MAX_VALUE;
        private int sliderMin = 0;
        private int sliderMax = 10;
        private boolean noSlider;

        public Builder() {
            super(0);
        }

        public Builder min(int value) {
            min = value;
            return this;
        }

        public Builder max(int value) {
            max = value;
            return this;
        }

        public Builder range(int low, int high) {
            min = Math.min(low, high);
            max = Math.max(low, high);
            return this;
        }

        public Builder sliderMin(int value) {
            sliderMin = value;
            return this;
        }

        public Builder sliderMax(int value) {
            sliderMax = value;
            return this;
        }

        public Builder sliderRange(int low, int high) {
            sliderMin = low;
            sliderMax = high;
            return this;
        }

        public Builder noSlider() {
            noSlider = true;
            return this;
        }

        @Override
        public IntSetting build() {
            return new IntSetting(this);
        }
    }
}
