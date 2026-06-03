package com.exchangelens;

import com.exchangelens.ui.ChartScale;
import org.junit.Test;

import static org.junit.Assert.*;

public class ChartScaleTest
{
    // ── timeToPixelX ─────────────────────────────────────────────────────────

    @Test
    public void timeToPixelX_minTimeMapsToLeft()
    {
        assertEquals(50, ChartScale.timeToPixelX(100L, 100L, 200L, 50, 250));
    }

    @Test
    public void timeToPixelX_maxTimeMapsToRight()
    {
        assertEquals(250, ChartScale.timeToPixelX(200L, 100L, 200L, 50, 250));
    }

    @Test
    public void timeToPixelX_midpointMapsToCenter()
    {
        assertEquals(150, ChartScale.timeToPixelX(150L, 100L, 200L, 50, 250));
    }

    @Test
    public void timeToPixelX_equalMinMaxReturnsLeft()
    {
        assertEquals(50, ChartScale.timeToPixelX(100L, 100L, 100L, 50, 250));
    }

    // ── valueToPixelY ─────────────────────────────────────────────────────────

    @Test
    public void valueToPixelY_maxValueMapsToTop()
    {
        // Screen Y is inverted: higher value → smaller Y
        assertEquals(10, ChartScale.valueToPixelY(200.0, 0.0, 200.0, 10, 210));
    }

    @Test
    public void valueToPixelY_minValueMapsToBottom()
    {
        assertEquals(210, ChartScale.valueToPixelY(0.0, 0.0, 200.0, 10, 210));
    }

    @Test
    public void valueToPixelY_midpointMapsToCenter()
    {
        assertEquals(110, ChartScale.valueToPixelY(100.0, 0.0, 200.0, 10, 210));
    }

    @Test
    public void valueToPixelY_equalMinMaxReturnsCenter()
    {
        assertEquals(110, ChartScale.valueToPixelY(100.0, 100.0, 100.0, 10, 210));
    }

    // ── autoScaleBounds ───────────────────────────────────────────────────────

    @Test
    public void autoScaleBounds_padsAboveAndBelow()
    {
        double[] bounds = ChartScale.autoScaleBounds(new double[]{100.0, 200.0}, 0.1);
        assertTrue("min should be padded below 100", bounds[0] < 100.0);
        assertTrue("max should be padded above 200", bounds[1] > 200.0);
    }

    @Test
    public void autoScaleBounds_emptyArrayReturnsUnitRange()
    {
        double[] bounds = ChartScale.autoScaleBounds(new double[]{}, 0.1);
        assertEquals(2, bounds.length);
        assertTrue(bounds[1] > bounds[0]);
    }

    @Test
    public void autoScaleBounds_singleValueHasRange()
    {
        double[] bounds = ChartScale.autoScaleBounds(new double[]{1000.0}, 0.1);
        assertTrue(bounds[1] > bounds[0]);
    }

    @Test
    public void autoScaleBounds_zeroPadReturnsTightBounds()
    {
        double[] bounds = ChartScale.autoScaleBounds(new double[]{100.0, 200.0}, 0.0);
        assertEquals(100.0, bounds[0], 0.001);
        assertEquals(200.0, bounds[1], 0.001);
    }
}
