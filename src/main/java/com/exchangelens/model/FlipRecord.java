package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;

/**
 * A matched buy+sell pair — one complete flip, and the primary training example.
 *
 * <p>Built incrementally: the buy side is recorded at BOUGHT, the sell side and all
 * outcome fields are filled when the sell completes (SOLD). Because {@code @Value}
 * makes every field final, partial construction uses {@code @Builder(toBuilder = true)}:
 * <pre>FlipRecord updated = existing.toBuilder().sellOffer(sellSnap).build();</pre>
 *
 * <p>{@code sellOffer} is null until the sell side completes. {@code suggestionSource}
 * is "exchange-lens" or "manual" (Copilot suggestions cannot be reliably detected).
 * Timestamps are epoch seconds for clean Gson round-tripping.
 */
@Value
@Builder(toBuilder = true)
public class FlipRecord
{
    String id;                // UUID
    int    itemId;
    String itemName;
    int    quantity;

    OfferSnapshot buyOffer;
    OfferSnapshot sellOffer;  // null until sell completes

    // Outcomes (filled when sellOffer completes)
    int    buyPrice;          // actual price paid per item
    int    sellPrice;         // actual price received per item
    long   tax;               // total tax across the flip (per-item × qty)
    int    netProfitPerItem;
    long   totalNetProfit;    // may be negative; long because qty × per-item can exceed int
    double roi;
    long   buyFillSeconds;    // time from buy posted to buy completed
    long   sellFillSeconds;   // time from sell posted to sell completed
    long   totalFlipSeconds;  // buyFillSeconds + sellFillSeconds

    // Metadata
    long   startedAt;         // buy postedAt
    long   completedAt;       // sell completedAt
    String accountName;
    String suggestionSource;  // "exchange-lens" | "manual"
}
