package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;
import java.time.Instant;

@Value
@Builder
public class MarketItem
{
    int itemId;
    String name;
    boolean members;
    int limit;

    Integer latestLow;
    Integer latestHigh;
    Long latestLowTime;
    Long latestHighTime;

    Integer fiveMinLow;
    Integer fiveMinHigh;
    Integer fiveMinLowVolume;
    Integer fiveMinHighVolume;

    Integer oneHourLow;
    Integer oneHourHigh;
    Integer oneHourLowVolume;
    Integer oneHourHighVolume;

    Instant lastUpdated;
}
