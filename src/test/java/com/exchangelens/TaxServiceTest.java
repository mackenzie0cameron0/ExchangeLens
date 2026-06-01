package com.exchangelens;

import com.exchangelens.service.TaxService;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaxServiceTest
{
    @Test
    public void taxIsFloorOf2Percent()
    {
        // 1000 * 0.02 = 20.0 → 20
        assertEquals(20, TaxService.calculate(1000));
    }

    @Test
    public void taxTruncatesDecimal()
    {
        // 999 * 0.02 = 19.98 → 19
        assertEquals(19, TaxService.calculate(999));
    }

    @Test
    public void taxOnZeroIsZero()
    {
        assertEquals(0, TaxService.calculate(0));
    }

    @Test
    public void taxOnLargeValue()
    {
        // 10_000_000 * 0.02 = 200_000
        assertEquals(200_000, TaxService.calculate(10_000_000));
    }
}
