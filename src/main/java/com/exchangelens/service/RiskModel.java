package com.exchangelens.service;

import com.exchangelens.model.RiskLevel;

public final class RiskModel
{
    private RiskModel() {}

    public static RiskLevel assess(double liquidityScore, double stabilityScore)
    {
        if (liquidityScore >= 0.8 && stabilityScore >= 0.8) return RiskLevel.LOW;
        if (liquidityScore < 0.5 && stabilityScore < 0.5) return RiskLevel.HIGH;
        return RiskLevel.MEDIUM;
    }
}
