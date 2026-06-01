package com.exchangelens;

import com.exchangelens.model.RiskLevel;
import com.exchangelens.service.RiskModel;
import org.junit.Test;
import static org.junit.Assert.*;

public class RiskModelTest
{
    @Test
    public void highLiquidityAndStabilityIsLow()
    {
        assertEquals(RiskLevel.LOW, RiskModel.assess(0.85, 0.85));
    }

    @Test
    public void liquidityAndStabilityBothBelowThresholdIsHigh()
    {
        assertEquals(RiskLevel.HIGH, RiskModel.assess(0.4, 0.4));
    }

    @Test
    public void mixedScoresIsMedium()
    {
        assertEquals(RiskLevel.MEDIUM, RiskModel.assess(0.9, 0.3));
    }

    @Test
    public void exactlyAtLowThresholdIsLow()
    {
        assertEquals(RiskLevel.LOW, RiskModel.assess(0.8, 0.8));
    }

    @Test
    public void exactlyAtHighThresholdIsMedium()
    {
        // 0.5 liquidity is not < 0.5, so not HIGH
        assertEquals(RiskLevel.MEDIUM, RiskModel.assess(0.5, 0.4));
    }
}
