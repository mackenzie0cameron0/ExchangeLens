package com.exchangelens;

import com.exchangelens.model.FlipRecord;
import com.exchangelens.model.OfferSnapshot;
import com.exchangelens.tracker.FlipAnalytics;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class FlipAnalyticsTest
{
    // ── Helpers ───────────────────────────────────────────────────────────────

    private static FlipRecord complete(int itemId, String name,
                                       long profit, double roi, long completedAt)
    {
        OfferSnapshot sell = OfferSnapshot.builder()
            .slot(0).itemId(itemId).itemName(name)
            .isBuy(false).postedPrice(0).quantity(1)
            .postedAt(completedAt - 10).completedAt(completedAt).totalSpent(0)
            .build();
        return FlipRecord.builder()
            .id("t").itemId(itemId).itemName(name).quantity(1)
            .buyPrice(1000).sellPrice(1000 + (int) profit)
            .totalNetProfit(profit).netProfitPerItem((int) profit)
            .roi(roi).tax(50L)
            .buyFillSeconds(10).sellFillSeconds(10).totalFlipSeconds(20)
            .startedAt(completedAt - 20).completedAt(completedAt)
            .accountName("acct").suggestionSource("manual")
            .sellOffer(sell)
            .build();
    }

    private static FlipRecord incomplete(int itemId, String name)
    {
        return FlipRecord.builder()
            .id("inc").itemId(itemId).itemName(name).quantity(1)
            .buyPrice(1000).totalNetProfit(9999L).roi(0.99)
            .build();
    }

    // ── totalProfit ───────────────────────────────────────────────────────────

    @Test
    public void totalProfitEmptyListIsZero()
    {
        assertEquals(0L, FlipAnalytics.totalProfit(Collections.emptyList()));
    }

    @Test
    public void totalProfitSumsCompleteFlips()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(4151, "Whip", 1000L, 0.1, 100),
            complete(4151, "Whip", 2000L, 0.2, 200));
        assertEquals(3000L, FlipAnalytics.totalProfit(flips));
    }

    @Test
    public void totalProfitSkipsIncompleteFlips()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(4151, "Whip", 1000L, 0.1, 100),
            incomplete(4151, "Whip"));
        assertEquals(1000L, FlipAnalytics.totalProfit(flips));
    }

    // ── totalTax ──────────────────────────────────────────────────────────────

    @Test
    public void totalTaxSumsCompleteFlips()
    {
        // complete() helper sets tax = 50L per flip
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 100L, 0.1, 100),
            complete(2, "B", 200L, 0.2, 200));
        assertEquals(100L, FlipAnalytics.totalTax(flips));
    }

    @Test
    public void totalTaxSkipsIncompleteFlips()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 100L, 0.1, 100),
            incomplete(1, "A"));
        assertEquals(50L, FlipAnalytics.totalTax(flips));
    }

    // ── winRate ───────────────────────────────────────────────────────────────

    @Test
    public void winRateEmptyIsZero()
    {
        assertEquals(0.0, FlipAnalytics.winRate(Collections.emptyList()), 0.001);
    }

    @Test
    public void winRateAllProfitableIs100()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 500L, 0.1, 100),
            complete(2, "B", 200L, 0.05, 200));
        assertEquals(100.0, FlipAnalytics.winRate(flips), 0.001);
    }

    @Test
    public void winRateHalfProfitableIs50()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A",  500L, 0.1, 100),
            complete(1, "A", -100L, -0.1, 200));
        assertEquals(50.0, FlipAnalytics.winRate(flips), 0.001);
    }

    @Test
    public void winRateIgnoresIncompleteFlips()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 500L, 0.1, 100),
            incomplete(1, "A"));  // not counted
        assertEquals(100.0, FlipAnalytics.winRate(flips), 0.001);
    }

    // ── bestFlip / worstFlip ──────────────────────────────────────────────────

    @Test
    public void bestFlipReturnsHighestProfit()
    {
        FlipRecord best = complete(1, "A", 9000L, 0.9, 300);
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 1000L, 0.1, 100),
            best,
            complete(1, "A", 5000L, 0.5, 200));
        assertEquals(best, FlipAnalytics.bestFlip(flips));
    }

    @Test
    public void worstFlipReturnsLowestProfit()
    {
        FlipRecord worst = complete(1, "A", -500L, -0.05, 100);
        List<FlipRecord> flips = Arrays.asList(
            worst,
            complete(1, "A", 2000L, 0.2, 200));
        assertEquals(worst, FlipAnalytics.worstFlip(flips));
    }

    @Test
    public void bestFlipNullOnEmptyList()
    {
        assertNull(FlipAnalytics.bestFlip(Collections.emptyList()));
    }

    // ── buildCumulativeSeries ─────────────────────────────────────────────────

    @Test
    public void cumulativeSeriesEmptyOnEmptyList()
    {
        assertEquals(0, FlipAnalytics.buildCumulativeSeries(Collections.emptyList()).length);
    }

    @Test
    public void cumulativeSeriesAccumulatesInOrder()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 1000L, 0.1, 100),
            complete(1, "A", 2000L, 0.2, 200));
        long[][] series = FlipAnalytics.buildCumulativeSeries(flips);
        assertEquals(2,     series.length);
        assertEquals(100L,  series[0][0]);  // timestamp
        assertEquals(1000L, series[0][1]);  // cumulative profit
        assertEquals(200L,  series[1][0]);
        assertEquals(3000L, series[1][1]);
    }

    @Test
    public void cumulativeSeriesSortsByTimestamp()
    {
        // Inserted in reverse order — series should be sorted ascending by completedAt
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 500L, 0.1, 300),
            complete(1, "A", 200L, 0.1, 100),
            complete(1, "A", 300L, 0.1, 200));
        long[][] series = FlipAnalytics.buildCumulativeSeries(flips);
        assertEquals(100L, series[0][0]);
        assertEquals(200L, series[0][1]);
        assertEquals(200L, series[1][0]);
        assertEquals(500L, series[1][1]);
        assertEquals(300L, series[2][0]);
        assertEquals(1000L, series[2][1]);
    }

    @Test
    public void cumulativeSeriesSkipsIncompleteFlips()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "A", 1000L, 0.1, 100),
            incomplete(1, "A"));
        long[][] series = FlipAnalytics.buildCumulativeSeries(flips);
        assertEquals(1, series.length);
        assertEquals(1000L, series[0][1]);
    }

    // ── perItemSummary ────────────────────────────────────────────────────────

    @Test
    public void perItemSummaryGroupsByItemId()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(4151, "Whip",    1000L, 0.1, 100),
            complete(4151, "Whip",    2000L, 0.2, 200),
            complete(1234, "Rune bar", 500L, 0.05, 300));
        List<FlipAnalytics.ItemSummary> summaries = FlipAnalytics.perItemSummary(flips);
        assertEquals(2, summaries.size());
    }

    @Test
    public void perItemSummarySortedByProfitDescending()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(1, "Low",  500L, 0.05, 100),
            complete(2, "High", 5000L, 0.5, 200));
        List<FlipAnalytics.ItemSummary> summaries = FlipAnalytics.perItemSummary(flips);
        assertEquals(2,    summaries.get(0).itemId);
        assertEquals(5000L, summaries.get(0).totalProfit);
    }

    @Test
    public void perItemSummaryAggregatesCorrectly()
    {
        List<FlipRecord> flips = Arrays.asList(
            complete(4151, "Whip", 1000L, 0.10, 100),
            complete(4151, "Whip", 3000L, 0.30, 200));
        List<FlipAnalytics.ItemSummary> summaries = FlipAnalytics.perItemSummary(flips);
        assertEquals(1,     summaries.size());
        FlipAnalytics.ItemSummary s = summaries.get(0);
        assertEquals(4151,  s.itemId);
        assertEquals("Whip", s.itemName);
        assertEquals(2,     s.flipCount);
        assertEquals(4000L, s.totalProfit);
        assertEquals(0.20,  s.avgRoi, 0.001);
    }
}
