package com.exchangelens;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.model.RiskLevel;
import com.exchangelens.service.PlusOneStrategy;
import org.junit.Test;
import java.time.Instant;
import static org.junit.Assert.*;

public class PlusOneStrategyTest
{
    private FlipRecommendation buildRec(int buyPrice, int sellPrice)
    {
        return FlipRecommendation.builder()
            .itemId(1).itemName("Test").members(false)
            .buyPrice(buyPrice).sellPrice(sellPrice)
            .spread(sellPrice - buyPrice).tax(0).netMargin(0).roi(0)
            .buyLimit(100).capitalRequired(0).estimatedProfitPerLimit(0)
            .affordableQuantity(0).affordableProfit(0)
            .fiveMinVolume(0).oneHourVolume(0)
            .liquidityScore(0).velocityScore(0).stabilityScore(0).finalScore(0)
            .estimatedFillMinutes(0).riskLevel(RiskLevel.LOW).lastUpdated(Instant.now())
            .build();
    }

    @Test
    public void buyPriceIsPlusOne()
    {
        FlipRecommendation rec = buildRec(1000, 1200);
        assertEquals(1001, new PlusOneStrategy().suggestBuyPrice(rec));
    }

    @Test
    public void sellPriceIsMinusOne()
    {
        FlipRecommendation rec = buildRec(1000, 1200);
        assertEquals(1199, new PlusOneStrategy().suggestSellPrice(rec));
    }
}
