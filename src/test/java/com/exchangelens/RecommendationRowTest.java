package com.exchangelens;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.model.RiskLevel;
import com.exchangelens.ui.RecommendationRow;
import org.junit.Test;

import static org.junit.Assert.*;

public class RecommendationRowTest
{
    private static FlipRecommendation rec()
    {
        return FlipRecommendation.builder()
            .itemId(4151).itemName("Abyssal whip")
            .netMargin(233).roi(0.0061)
            .riskLevel(RiskLevel.LOW).finalScore(0.61)
            .estimatedFillMinutes(2.0)
            .buyPrice(37900).sellPrice(38911)
            .build();
    }

    @Test
    public void mapsRawFieldsFromRecommendation()
    {
        RecommendationRow row = RecommendationRow.from(rec());
        assertEquals(4151, row.itemId);
        assertEquals("Abyssal whip", row.itemName);
        assertEquals(233, row.margin);
        assertEquals(0.61, row.roiPercent, 0.0001);   // roi (0.0061) × 100
        assertEquals(RiskLevel.LOW, row.risk);
        assertEquals(0.61, row.score, 0.0001);
        assertEquals(2.0, row.fillMinutes, 0.0001);
    }

    @Test
    public void riskLabelAbbreviatesMedium()
    {
        assertEquals("LOW",  RecommendationRow.riskLabel(RiskLevel.LOW));
        assertEquals("MED",  RecommendationRow.riskLabel(RiskLevel.MEDIUM));
        assertEquals("HIGH", RecommendationRow.riskLabel(RiskLevel.HIGH));
    }

    @Test
    public void scoreShownToTwoDecimals()
    {
        assertEquals("0.61", RecommendationRow.scoreText(0.61));
        assertEquals("1.00", RecommendationRow.scoreText(1.0));
    }

    @Test
    public void roiShownToTwoDecimalsWithPercent()
    {
        assertEquals("0.61%", RecommendationRow.roiText(0.61));
        assertEquals("12.50%", RecommendationRow.roiText(12.5));
    }
}
