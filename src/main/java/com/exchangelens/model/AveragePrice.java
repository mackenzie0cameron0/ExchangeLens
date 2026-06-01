package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AveragePrice
{
    int itemId;
    Integer avgHighPrice;
    Integer highPriceVolume;
    Integer avgLowPrice;
    Integer lowPriceVolume;
}
