package com.exchangelens;

import com.exchangelens.api.WikiApiModels;
import com.google.gson.Gson;
import org.junit.Test;

import static org.junit.Assert.*;

public class TimeseriesParsingTest
{
    private final Gson gson = new Gson();

    @Test
    public void parsesTimeseriesResponse()
    {
        String json = "{\"data\":[{\"timestamp\":1748908800,\"avgHighPrice\":1234,"
            + "\"avgLowPrice\":1200,\"highPriceVolume\":500,\"lowPriceVolume\":300}]}";
        WikiApiModels.TimeseriesResponse resp =
            gson.fromJson(json, WikiApiModels.TimeseriesResponse.class);

        assertNotNull(resp.data);
        assertEquals(1, resp.data.size());
        WikiApiModels.TimeseriesPoint pt = resp.data.get(0);
        assertEquals(1748908800L, pt.timestamp);
        assertEquals(Integer.valueOf(1234), pt.avgHighPrice);
        assertEquals(Integer.valueOf(1200), pt.avgLowPrice);
        assertEquals(Integer.valueOf(500),  pt.highPriceVolume);
        assertEquals(Integer.valueOf(300),  pt.lowPriceVolume);
    }

    @Test
    public void nullPriceFieldsParsedAsNull()
    {
        String json = "{\"data\":[{\"timestamp\":1748908800,\"avgHighPrice\":null,"
            + "\"avgLowPrice\":null,\"highPriceVolume\":null,\"lowPriceVolume\":null}]}";
        WikiApiModels.TimeseriesResponse resp =
            gson.fromJson(json, WikiApiModels.TimeseriesResponse.class);

        WikiApiModels.TimeseriesPoint pt = resp.data.get(0);
        assertNull(pt.avgHighPrice);
        assertNull(pt.avgLowPrice);
        assertNull(pt.highPriceVolume);
        assertNull(pt.lowPriceVolume);
    }

    @Test
    public void parsesMultiplePoints()
    {
        String json = "{\"data\":["
            + "{\"timestamp\":1000,\"avgHighPrice\":100,\"avgLowPrice\":90,"
            + "\"highPriceVolume\":10,\"lowPriceVolume\":5},"
            + "{\"timestamp\":2000,\"avgHighPrice\":110,\"avgLowPrice\":95,"
            + "\"highPriceVolume\":20,\"lowPriceVolume\":8}"
            + "]}";
        WikiApiModels.TimeseriesResponse resp =
            gson.fromJson(json, WikiApiModels.TimeseriesResponse.class);
        assertEquals(2, resp.data.size());
        assertEquals(1000L, resp.data.get(0).timestamp);
        assertEquals(2000L, resp.data.get(1).timestamp);
    }
}
