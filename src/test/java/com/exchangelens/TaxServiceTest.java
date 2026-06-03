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

    @Test
    public void taxIsCappedAtFiveMillion()
    {
        // 300M * 0.02 = 6M, but the GE caps per-item tax at 5M.
        assertEquals(5_000_000, TaxService.calculate(300_000_000));
        // Just below the cap is uncapped.
        assertEquals(4_000_000, TaxService.calculate(200_000_000));
    }

    @Test
    public void taxUnderFiftyGpIsZero()
    {
        // floor(49 * 0.02) = floor(0.98) = 0 — matches the GE's sub-50 exemption.
        assertEquals(0, TaxService.calculate(49));
        assertEquals(1, TaxService.calculate(50));
    }
}
