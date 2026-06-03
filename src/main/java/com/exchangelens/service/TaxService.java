package com.exchangelens.service;

public final class TaxService
{
    /** The live GE caps tax at 5,000,000 gp per item (reached around a 250M sale price). */
    private static final long TAX_CAP = 5_000_000L;

    private TaxService() {}

    public static int calculate(int sellPrice)
    {
        if (sellPrice <= 0) return 0;
        // 2% rounded DOWN (so items selling under 50 gp are naturally untaxed), capped at 5M.
        long tax = (long) Math.floor(sellPrice * 0.02);
        return (int) Math.min(tax, TAX_CAP);
    }
}
