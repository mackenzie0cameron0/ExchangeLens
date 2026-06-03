package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;

/**
 * One side (buy or sell) of a GE transaction, plus the market signals captured when
 * the offer was first seen.
 *
 * <p>{@code postedAt} is the plugin's own clock at first sighting — the RuneLite
 * {@code GrandExchangeOffer} API exposes no post timestamp. {@code completedAt} stays
 * 0 until the offer reaches BOUGHT/SOLD. {@code totalSpent} comes from
 * {@code offer.getSpent()} (actual GP transacted, accounting for partial fills), not
 * price × quantity. {@code marketSnapshot} is null if no market data was available.
 */
@Value
@Builder(toBuilder = true)
public class OfferSnapshot
{
    int     slot;
    int     itemId;
    String  itemName;
    boolean isBuy;
    int     postedPrice;      // price the offer was posted at
    int     quantity;
    long    postedAt;         // epoch seconds when offer first appeared
    long    completedAt;      // epoch seconds when offer reached BOUGHT/SOLD (0 until then)
    int     totalSpent;       // offer.getSpent(): actual GP spent/received

    MarketSnapshot marketSnapshot;  // signals at posting time; null if unavailable
}
