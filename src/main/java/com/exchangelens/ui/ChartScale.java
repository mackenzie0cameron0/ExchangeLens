package com.exchangelens.ui;

public final class ChartScale
{
    private ChartScale() {}

    /**
     * Maps a timestamp (epoch seconds) to a pixel x-coordinate within [left, right].
     */
    public static int timeToPixelX(long timestamp, long minTime, long maxTime, int left, int right)
    {
        if (maxTime == minTime) return left;
        double ratio = (double) (timestamp - minTime) / (maxTime - minTime);
        return left + (int) Math.round(ratio * (right - left));
    }

    /**
     * Maps a value to a pixel y-coordinate within [top, bottom].
     * Screen Y is inverted: higher value → smaller Y (closer to top).
     */
    public static int valueToPixelY(double value, double minVal, double maxVal, int top, int bottom)
    {
        if (maxVal == minVal) return (top + bottom) / 2;
        double ratio = (value - minVal) / (maxVal - minVal);
        return bottom - (int) Math.round(ratio * (bottom - top));
    }

    /**
     * Returns [min, max] with symmetric padding applied as a fraction of the range.
     * If values is empty, returns [0.0, 1.0]. If all values are equal, uses the value
     * itself as the range base to avoid zero-range.
     */
    public static double[] autoScaleBounds(double[] values, double padFraction)
    {
        if (values.length == 0) return new double[]{0.0, 1.0};
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : values)
        {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double range = max - min;
        if (range == 0) range = Math.abs(min) > 0 ? Math.abs(min) : 1.0;
        double pad = range * padFraction;
        return new double[]{min - pad, max + pad};
    }
}
