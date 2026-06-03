package com.exchangelens.ui;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain data holder passed to {@link PriceChartComponent}.
 * Panels build a ChartModel; the component renders it. No business logic here.
 */
public class ChartModel
{
    /** A single line series — timestamped (x, value) pairs. */
    public static class Series
    {
        public String  label;
        public long[]  timestamps;   // epoch seconds, parallel with values[]
        public double[] values;      // 0.0 treated as "no data" — skip this point
        public Color   color;
        public float   strokeWidth = 1.5f;
    }

    /** A single trade marker rendered as a triangle (▲ buy, ▼ sell). */
    public static class Marker
    {
        public long   timestamp;   // epoch seconds
        public double value;
        public Color  color;
        public boolean isUp;       // true = buy (▲), false = sell (▼)
        public String tooltip;
    }

    /** Connects a buy marker to its matched sell with a faint dashed line. */
    public static class Connection
    {
        public int buyMarkerIndex;
        public int sellMarkerIndex;
    }

    public List<Series>     series      = new ArrayList<>();
    public List<Marker>     markers     = new ArrayList<>();
    public List<Connection> connections = new ArrayList<>();

    /** Visible time window (epoch seconds). */
    public long xMin;
    public long xMax;

    /**
     * Value range. If both are 0.0, {@link PriceChartComponent} auto-scales
     * from all series values and marker values.
     */
    public double yMin;
    public double yMax;

    /** Optional volume bars — same length as the first series, or null. */
    public long[]   volumeTimestamps;
    public double[] highVolumes;
    public double[] lowVolumes;
}
