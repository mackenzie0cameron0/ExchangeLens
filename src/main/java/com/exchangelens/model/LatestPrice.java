package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LatestPrice
{
    int itemId;
    Integer high;
    Long highTime;
    Integer low;
    Long lowTime;
}
