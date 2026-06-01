package com.exchangelens.service;

public final class TaxService
{
    private TaxService() {}

    public static int calculate(int sellPrice)
    {
        return (int) Math.floor(sellPrice * 0.02);
    }
}
