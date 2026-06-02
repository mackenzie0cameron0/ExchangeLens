package com.exchangelens.service;

import com.exchangelens.model.FlipRecommendation;

public class PlusOneStrategy implements PriceAdvisorStrategy
{
    @Override
    public int suggestBuyPrice(FlipRecommendation rec)
    {
        return rec.getBuyPrice() + 1;
    }

    @Override
    public int suggestSellPrice(FlipRecommendation rec)
    {
        return rec.getSellPrice() - 1;
    }
}
