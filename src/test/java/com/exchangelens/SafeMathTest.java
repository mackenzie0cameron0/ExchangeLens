package com.exchangelens;

import com.exchangelens.service.SafeMath;
import org.junit.Test;
import static org.junit.Assert.*;

public class SafeMathTest
{
    @Test
    public void divideReturnsCorrectResult()
    {
        assertEquals(0.5, SafeMath.divide(1, 2), 0.0001);
    }

    @Test
    public void divideByZeroReturnsZero()
    {
        assertEquals(0.0, SafeMath.divide(100, 0), 0.0001);
    }

    @Test
    public void clampBelowMin()
    {
        assertEquals(0.0, SafeMath.clamp(-1.0, 0.0, 1.0), 0.0001);
    }

    @Test
    public void clampAboveMax()
    {
        assertEquals(1.0, SafeMath.clamp(2.0, 0.0, 1.0), 0.0001);
    }

    @Test
    public void clampWithinRange()
    {
        assertEquals(0.5, SafeMath.clamp(0.5, 0.0, 1.0), 0.0001);
    }
}
