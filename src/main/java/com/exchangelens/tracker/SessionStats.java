package com.exchangelens.tracker;

import lombok.Builder;
import lombok.Value;

/**
 * Aggregated statistics for the current flipping session, computed by
 * {@link SessionStatsCalculator} and rendered in the Session sidebar view.
 */
@Value
@Builder
public class SessionStats
{
    long   totalProfit;       // sum across all session flips — long to avoid overflow
    int    flipsCompleted;
    double averageRoi;
    long   sessionDurationSeconds;
    long   hourlyProfit;
}
