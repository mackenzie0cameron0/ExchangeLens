package com.exchangelens.service;

public final class SafeMath
{
    private SafeMath() {}

    public static double divide(double numerator, double denominator)
    {
        if (denominator == 0) return 0.0;
        return numerator / denominator;
    }

    public static double clamp(double value, double min, double max)
    {
        return Math.max(min, Math.min(max, value));
    }
}
