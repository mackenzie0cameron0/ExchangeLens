package com.exchangelens;

import com.exchangelens.model.*;
import com.exchangelens.service.RecommendationEngine;
import org.junit.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.Assert.*;

public class RecommendationEngineTest
{
    private static MarketItem buildItem(int id, String name, int buyPrice, int sellPrice,
                                        int buyLimit, int oneHourVol, int fiveMinVol,
                                        Integer avgHigh5m, Integer avgLow5m)
    {
        return MarketItem.builder()
            .itemId(id).name(name).members(false).limit(buyLimit)
            .latestLow(buyPrice).latestHigh(sellPrice)
            .latestLowTime(System.currentTimeMillis() / 1000L)
            .latestHighTime(System.currentTimeMillis() / 1000L)
            .fiveMinLow(avgLow5m).fiveMinHigh(avgHigh5m)
            .fiveMinLowVolume(fiveMinVol / 2).fiveMinHighVolume(fiveMinVol / 2)
            .oneHourLow(buyPrice).oneHourHigh(sellPrice)
            .oneHourLowVolume(oneHourVol / 2).oneHourHighVolume(oneHourVol / 2)
            .lastUpdated(Instant.now())
            .build();
    }

    @Test
    public void itemWithNegativeNetMarginIsExcluded()
    {
        // sell=100, buy=100 → spread=0, tax=2 → netMargin=-2
        MarketItem item = buildItem(1, "Bad Item", 100, 100, 100, 1000, 100, 100, 100);
        List<FlipRecommendation> recs = RecommendationEngine.rank(
            Collections.singletonList(item), 0, 0.0, 0, 100_000_000, false, true, Collections.emptySet());
        assertTrue(recs.isEmpty());
    }

    @Test
    public void itemWithPositiveMarginIsIncluded()
    {
        // buy=1000, sell=1200 → spread=200, tax=24 → netMargin=176
        MarketItem item = buildItem(2, "Good Item", 1000, 1200, 100, 5000, 500, 1200, 1000);
        List<FlipRecommendation> recs = RecommendationEngine.rank(
            Collections.singletonList(item), 0, 0.0, 0, 100_000_000, false, true, Collections.emptySet());
        assertEquals(1, recs.size());
        assertEquals(176, recs.get(0).getNetMargin());
    }

    @Test
    public void blockedItemIsExcluded()
    {
        MarketItem item = buildItem(3, "Blocked", 1000, 1200, 100, 5000, 500, 1200, 1000);
        List<FlipRecommendation> recs = RecommendationEngine.rank(
            Collections.singletonList(item), 0, 0.0, 0, 100_000_000, false, true, Collections.singleton(3));
        assertTrue(recs.isEmpty());
    }

    @Test
    public void resultsSortedByFinalScoreDescending()
    {
        MarketItem highScore = buildItem(4, "High", 1000, 1200, 100, 50000, 5000, 1200, 1000);
        // sell=1030 → tax=20, netMargin=10 (positive, low score due to tiny volume)
        MarketItem lowScore  = buildItem(5, "Low",  1000, 1030, 100, 50, 5, 1030, 1000);
        List<FlipRecommendation> recs = RecommendationEngine.rank(
            Arrays.asList(lowScore, highScore), 0, 0.0, 0, 100_000_000, false, true, Collections.emptySet());
        assertEquals(2, recs.size());
        assertTrue(recs.get(0).getFinalScore() >= recs.get(1).getFinalScore());
        assertEquals(4, recs.get(0).getItemId());
    }

    @Test
    public void taxIsCalculatedCorrectly()
    {
        // sell=1000, tax=floor(1000*0.02)=20, buy=900, netMargin=80
        MarketItem item = buildItem(6, "Tax Test", 900, 1000, 100, 5000, 500, 1000, 900);
        List<FlipRecommendation> recs = RecommendationEngine.rank(
            Collections.singletonList(item), 0, 0.0, 0, 100_000_000, false, true, Collections.emptySet());
        assertEquals(1, recs.size());
        assertEquals(20, recs.get(0).getTax());
        assertEquals(80, recs.get(0).getNetMargin());
    }
}
