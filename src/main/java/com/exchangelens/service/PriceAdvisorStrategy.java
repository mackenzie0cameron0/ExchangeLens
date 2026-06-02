package com.exchangelens.service;

import com.exchangelens.model.FlipRecommendation;

public interface PriceAdvisorStrategy
{
    int suggestBuyPrice(FlipRecommendation rec);
    int suggestSellPrice(FlipRecommendation rec);
}
