package com.exchangelens;

import com.exchangelens.model.FlipRecord;
import com.exchangelens.tracker.SessionStats;
import com.exchangelens.tracker.SessionStatsCalculator;
import org.junit.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class SessionStatsCalculatorTest
{
    private static FlipRecord flipWithProfit(int netProfit)
    {
        return FlipRecord.builder()
            .id("test")
            .totalNetProfit(netProfit)
            .roi(0.0)
            .build();
    }

    @Test
    public void emptyFlipListReturnsZeroStats()
    {
        SessionStats stats = SessionStatsCalculator.calculate(
            Collections.emptyList(), Instant.now().minusSeconds(300));
        assertEquals(0, stats.getTotalProfit());
        assertEquals(0, stats.getFlipsCompleted());
        assertEquals(0.0, stats.getAverageRoi(), 0.0001);
    }

    @Test
    public void totalProfitSumsAllFlips()
    {
        List<FlipRecord> flips = Arrays.asList(
            flipWithProfit(5000), flipWithProfit(3000), flipWithProfit(-1000));
        SessionStats stats = SessionStatsCalculator.calculate(flips, Instant.now().minusSeconds(3600));
        assertEquals(7000, stats.getTotalProfit());
        assertEquals(3, stats.getFlipsCompleted());
    }

    @Test
    public void hourlyProfitScalesWithSessionDuration()
    {
        // 10000 gp in 30 minutes ≈ 20000 gp/hr
        List<FlipRecord> flips = Collections.singletonList(flipWithProfit(10000));
        Instant start = Instant.now().minusSeconds(1800);
        SessionStats stats = SessionStatsCalculator.calculate(flips, start);
        assertTrue("hourly was " + stats.getHourlyProfit(),
            Math.abs(stats.getHourlyProfit() - 20000) < 500);
    }

    @Test
    public void totalProfitDoesNotOverflowIntAcrossManySessionFlips()
    {
        // 50 flips × 50,000,000 = 2,500,000,000 — exceeds Integer.MAX_VALUE (2,147,483,647).
        // Must accumulate in long, not wrap negative.
        java.util.List<FlipRecord> flips = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) flips.add(flipWithProfit(50_000_000));
        SessionStats stats = SessionStatsCalculator.calculate(flips, Instant.now().minusSeconds(3600));
        assertEquals(2_500_000_000L, stats.getTotalProfit());
        assertTrue("hourly should be positive, was " + stats.getHourlyProfit(),
            stats.getHourlyProfit() > 0);
    }

    @Test
    public void averageRoiAveragesAcrossFlips()
    {
        List<FlipRecord> flips = Arrays.asList(
            FlipRecord.builder().id("a").totalNetProfit(0).roi(0.10).build(),
            FlipRecord.builder().id("b").totalNetProfit(0).roi(0.20).build());
        SessionStats stats = SessionStatsCalculator.calculate(flips, Instant.now().minusSeconds(60));
        assertEquals(0.15, stats.getAverageRoi(), 0.0001);
    }
}
