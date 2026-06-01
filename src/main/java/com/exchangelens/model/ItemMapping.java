package com.exchangelens.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ItemMapping
{
    int id;
    String name;
    boolean members;
    int limit;
    int lowAlch;
    int highAlch;
    String examine;
}
