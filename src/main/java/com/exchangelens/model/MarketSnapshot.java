package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable snapshot of Wiki API market signals captured at the moment a GE offer
 * is posted. This is the feature vector for the Phase 4 ML training pipeline.
 *
 * <p>All price/volume/derived fields are boxed (Integer/Double/Long) and may be null:
 * raw price fields mirror {@link MarketItem}'s boxed nullable fields, and the
 * recommendation-derived scores only exist when a {@link FlipRecommendation} was
 * present for the item at capture time. The CSV export writes literal {@code null}
 * for absent fields, which the training pipeline filters via {@code df.dropna()}.
 *
 * <p>Timestamps are stored as {@code long}/{@code Long} epoch seconds so the project's
 * plain {@code new Gson()} round-trips them cleanly (no java.time adapter required).
 */
@Value
@Builder
public class MarketSnapshot
{
    int  itemId;
    long capturedAt;          // epoch seconds; always set

    // Latest prices (from MarketItem, boxed-nullable)
    Integer latestLow;
    Integer latestHigh;
    Long    latestLowTime;
    Long    latestHighTime;

    // 5-minute averages + volumes
    Integer fiveMinAvgLow;
    Integer fiveMinAvgHigh;
    Integer fiveMinLowVolume;
    Integer fiveMinHighVolume;

    // 1-hour averages + volumes
    Integer oneHourAvgLow;
    Integer oneHourAvgHigh;
    Integer oneHourLowVolume;
    Integer oneHourHighVolume;

    // Derived signals (pre-computed for training convenience; null when uncomputable)
    Integer spread;           // latestHigh - latestLow
    Integer tax;              // TaxService.calculate(latestHigh)
    Integer netMargin;        // spread - tax
    Double  roi;              // netMargin / latestLow

    // ML scores — only present when a FlipRecommendation existed for this item
    Double  liquidityScore;
    Double  velocityScore;
    Double  stabilityScore;
    Double  finalScore;

    Integer buyLimit;
    Integer oneHourVolume;    // combined high+low
    Integer fiveMinVolume;    // combined high+low
}
