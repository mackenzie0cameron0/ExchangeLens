package com.exchangelens;

import com.exchangelens.ui.SessionStatsPanel;
import org.junit.Test;

import static org.junit.Assert.*;

/** Pure tests for the hh:mm:ss formatter used by the live session clock. */
public class SessionDurationFormatTest
{
    @Test
    public void zeroSeconds()
    {
        assertEquals("00:00:00", SessionStatsPanel.formatDuration(0));
    }

    @Test
    public void mixedHoursMinutesSeconds()
    {
        assertEquals("01:01:01", SessionStatsPanel.formatDuration(3661));
        assertEquals("02:02:02", SessionStatsPanel.formatDuration(7322));
    }

    @Test
    public void negativeClampsToZero()
    {
        assertEquals("00:00:00", SessionStatsPanel.formatDuration(-5));
    }

    @Test
    public void largeDurationDoesNotWrapHours()
    {
        // 100 hours stays as 100, not modulo'd
        assertEquals("100:00:00", SessionStatsPanel.formatDuration(360_000));
    }
}
