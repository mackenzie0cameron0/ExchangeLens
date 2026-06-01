package com.exchangelens.service;

import com.exchangelens.model.FlipRecommendation;
import com.exchangelens.model.MarketItem;
import com.exchangelens.model.RiskLevel;
import java.util.*;
import java.util.stream.Collectors;

public final class RecommendationEngine
{
    private static final int MARGIN_CAP = 50_000;

    private RecommendationEngine() {}

    public static List<FlipRecommendation> rank(
        List<MarketItem> items,
        int minimumNetMargin,
        double minimumRoi,
        int minimumHourlyVolume,
        int maximumCapital,
        boolean hideHighRisk,
        boolean includeMembersItems,
        Set<Integer> blocklist)
    {
        return items.stream()
            .filter(item -> !blocklist.contains(item.getItemId()))
            .filter(item -> includeMembersItems || !item.isMembers())
            .filter(item -> item.getLatestLow() != null && item.getLatestLow() > 0)
            .filter(item -> item.getLatestHigh() != null && item.getLatestHigh() > 0)
            .map(RecommendationEngine::score)
            .filter(Objects::nonNull)
            .filter(r -> r.getNetMargin() > minimumNetMargin)
            .filter(r -> r.getRoi() >= minimumRoi)
            .filter(r -> r.getOneHourVolume() >= minimumHourlyVolume)
            .filter(r -> !hideHighRisk || r.getRiskLevel() != RiskLevel.HIGH)
            .sorted(Comparator.comparingDouble(FlipRecommendation::getFinalScore).reversed())
            .collect(Collectors.toList());
    }

    private static FlipRecommendation score(MarketItem item)
    {
        int buyPrice  = item.getLatestLow();
        int sellPrice = item.getLatestHigh();
        int tax       = TaxService.calculate(sellPrice);
        int spread    = sellPrice - buyPrice;
        int netMargin = spread - tax;

        if (netMargin <= 0 || buyPrice <= 0) return null;

        double roi = SafeMath.divide(netMargin, buyPrice);

        int buyLimit          = item.getLimit();
        long capitalRequired  = (long) buyPrice * buyLimit;
        long profitPerLimit   = (long) netMargin * buyLimit;

        // Liquidity score
        int fiveMinVol  = nullToZero(item.getFiveMinLowVolume()) + nullToZero(item.getFiveMinHighVolume());
        int oneHourVol  = nullToZero(item.getOneHourLowVolume()) + nullToZero(item.getOneHourHighVolume());
        double hourlyActivity    = SafeMath.clamp(SafeMath.divide(oneHourVol, Math.max(1, buyLimit)), 0, 1);
        double expectedFiveMin   = Math.max(1.0, buyLimit / 12.0);
        double shortTermActivity = SafeMath.clamp(SafeMath.divide(fiveMinVol, expectedFiveMin), 0, 1);
        double liquidityScore    = (shortTermActivity * 0.6) + (hourlyActivity * 0.4);

        // Velocity score
        double estimatedFillMinutes = SafeMath.divide((double) buyLimit, Math.max(1, oneHourVol)) * 60.0;
        double velocityScore        = SafeMath.clamp(1.0 - (estimatedFillMinutes / 120.0), 0, 1);

        // Stability score
        double stabilityScore;
        Integer avgHigh5m = item.getFiveMinHigh();
        Integer avgLow5m  = item.getFiveMinLow();
        if (avgHigh5m == null || avgLow5m == null || avgHigh5m == 0 || avgLow5m == 0)
        {
            stabilityScore = 0.5;
        }
        else
        {
            double highDev  = Math.abs(sellPrice - avgHigh5m) / (double) avgHigh5m;
            double lowDev   = Math.abs(buyPrice  - avgLow5m)  / (double) avgLow5m;
            double avgDev   = (highDev + lowDev) / 2.0;
            stabilityScore  = SafeMath.clamp(1.0 - (avgDev / 0.10), 0, 1);
        }

        // Normalised margin
        double marginNorm = SafeMath.clamp(SafeMath.divide(netMargin, MARGIN_CAP), 0, 1);

        // Final score
        double roiCapped  = SafeMath.clamp(roi, 0, 1);
        double finalScore = (roiCapped      * 0.30)
                          + (liquidityScore * 0.25)
                          + (velocityScore  * 0.20)
                          + (stabilityScore * 0.15)
                          + (marginNorm     * 0.10);

        RiskLevel risk = RiskModel.assess(liquidityScore, stabilityScore);

        return FlipRecommendation.builder()
            .itemId(item.getItemId())
            .itemName(item.getName())
            .members(item.isMembers())
            .buyPrice(buyPrice)
            .sellPrice(sellPrice)
            .spread(spread)
            .tax(tax)
            .netMargin(netMargin)
            .roi(roi)
            .buyLimit(buyLimit)
            .capitalRequired(capitalRequired)
            .estimatedProfitPerLimit(profitPerLimit)
            .affordableQuantity(buyLimit)
            .affordableProfit(profitPerLimit)
            .fiveMinVolume(fiveMinVol)
            .oneHourVolume(oneHourVol)
            .liquidityScore(liquidityScore)
            .velocityScore(velocityScore)
            .stabilityScore(stabilityScore)
            .finalScore(finalScore)
            .estimatedFillMinutes(estimatedFillMinutes)
            .riskLevel(risk)
            .lastUpdated(item.getLastUpdated())
            .build();
    }

    private static int nullToZero(Integer value)
    {
        return value == null ? 0 : value;
    }
}
