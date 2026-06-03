package com.exchangelens.tracker;

import com.exchangelens.model.FlipRecord;

import java.time.Instant;
import java.util.List;

/**
 * Pure function: a list of completed {@link FlipRecord}s + the session start instant
 * → aggregated {@link SessionStats}. No I/O and no RuneLite dependencies, so it is
 * fully unit-testable.
 */
public final class SessionStatsCalculator
{
    private SessionStatsCalculator() {}

    public static SessionStats calculate(List<FlipRecord> flips, Instant sessionStart)
    {
        long sessionSecs = Instant.now().getEpochSecond() - sessionStart.getEpochSecond();
        long totalProfit = flips.stream().mapToLong(FlipRecord::getTotalNetProfit).sum();
        int  count       = flips.size();
        double avgRoi    = flips.stream().mapToDouble(FlipRecord::getRoi).average().orElse(0.0);
        long hourly      = sessionSecs > 0
            ? (long) (totalProfit / (sessionSecs / 3600.0)) : 0;

        return SessionStats.builder()
            .totalProfit(totalProfit)
            .flipsCompleted(count)
            .averageRoi(avgRoi)
            .sessionDurationSeconds(sessionSecs)
            .hourlyProfit(hourly)
            .build();
    }
}
