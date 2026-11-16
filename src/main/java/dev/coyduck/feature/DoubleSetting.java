package dev.coyduck.feature;

public final class DoubleSetting extends Setting<Double> {
    public final double min;
    public final double max;
    public final double sliderMin;
    public final double sliderMax;
    public final int decimalPlaces;
    public final boolean noSlider;

    private DoubleSetting(Builder builder) {
        super(builder);
        min = builder.min;
        max = builder.max;
        sliderMin = builder.sliderMin;
        sliderMax = builder.sliderMax;
        decimalPlaces = builder.decimalPlaces;
        noSlider = builder.noSlider;
    }

    @Override
    protected boolean isValueValid(Double candidate) {
        return candidate != null && candidate >= min && candidate <= max;
    }

    public static final class Builder extends Setting.Builder<Builder, Double, DoubleSetting> {
        private double min = Double.NEGATIVE_INFINITY;
        private double max = Double.POSITIVE_INFINITY;
        private double sliderMin = 0;
        private double sliderMax = 10;
        private int decimalPlaces = 3;
        private boolean noSlider;

        public Builder() {
            super(0.0);
        }

        public Builder defaultValue(double value) {
            return defaultValue(Double.valueOf(value));
        }

        public Builder min(double value) {
            min = value;
            return this;
        }

        public Builder max(double value) {
            max = value;
            return this;
        }

        public Builder range(double low, double high) {
            min = Math.min(low, high);
            max = Math.max(low, high);
            return this;
        }

        public Builder sliderMin(double value) {
            sliderMin = value;
            return this;
        }

        public Builder sliderMax(double value) {
            sliderMax = value;
            return this;
        }

        public Builder sliderRange(double low, double high) {
            sliderMin = low;
            sliderMax = high;
            return this;
        }

        public Builder decimalPlaces(int value) {
            decimalPlaces = value;
            return this;
        }

        public Builder noSlider() {
            noSlider = true;
            return this;
        }

        @Override
        public DoubleSetting build() {
            return new DoubleSetting(this);
        }
    }
}
