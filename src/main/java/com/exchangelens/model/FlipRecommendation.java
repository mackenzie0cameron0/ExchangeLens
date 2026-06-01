package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;
import java.time.Instant;

@Value
@Builder
public class FlipRecommendation
{
    int itemId;
    String itemName;
    boolean members;

    int buyPrice;
    int sellPrice;
    int spread;
    int tax;
    int netMargin;
    double roi;

    int buyLimit;
    long capitalRequired;
    long estimatedProfitPerLimit;
    int affordableQuantity;
    long affordableProfit;

    int fiveMinVolume;
    int oneHourVolume;
    double liquidityScore;
    double velocityScore;
    double stabilityScore;
    double finalScore;
    double estimatedFillMinutes;

    RiskLevel riskLevel;
    Instant lastUpdated;
}
