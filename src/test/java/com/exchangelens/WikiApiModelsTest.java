package com.exchangelens;

import com.exchangelens.api.WikiApiModels;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.junit.Test;
import java.lang.reflect.Type;
import java.util.List;
import static org.junit.Assert.*;

public class WikiApiModelsTest
{
    private final Gson gson = new Gson();

    @Test
    public void parsesLatestResponse()
    {
        String json = "{\"data\":{\"4151\":{\"high\":2500000,\"highTime\":1700000001,\"low\":2490000,\"lowTime\":1700000000}}}";
        WikiApiModels.LatestResponse response = gson.fromJson(json, WikiApiModels.LatestResponse.class);

        assertNotNull(response.data);
        WikiApiModels.LatestPriceData item = response.data.get("4151");
        assertNotNull(item);
        assertEquals(Integer.valueOf(2500000), item.high);
        assertEquals(Integer.valueOf(2490000), item.low);
        assertEquals(Long.valueOf(1700000001L), item.highTime);
    }

    @Test
    public void parsesAverageResponse()
    {
        String json = "{\"data\":{\"4151\":{\"avgHighPrice\":2498000,\"highPriceVolume\":120,\"avgLowPrice\":2488000,\"lowPriceVolume\":95}}}";
        WikiApiModels.AverageResponse response = gson.fromJson(json, WikiApiModels.AverageResponse.class);

        WikiApiModels.AveragePriceData item = response.data.get("4151");
        assertNotNull(item);
        assertEquals(Integer.valueOf(2498000), item.avgHighPrice);
        assertEquals(Integer.valueOf(120), item.highPriceVolume);
    }

    @Test
    public void parsesMappingArray()
    {
        String json = "[{\"id\":4151,\"name\":\"Abyssal whip\",\"members\":true,\"limit\":70,\"lowalch\":60000,\"highalch\":90000,\"examine\":\"A weapon.\"}]";
        Type listType = new TypeToken<List<WikiApiModels.MappingEntry>>(){}.getType();
        List<WikiApiModels.MappingEntry> entries = gson.fromJson(json, listType);

        assertEquals(1, entries.size());
        WikiApiModels.MappingEntry entry = entries.get(0);
        assertEquals(4151, entry.id);
        assertEquals("Abyssal whip", entry.name);
        assertTrue(entry.members);
        assertEquals(70, entry.limit);
        assertEquals(90000, entry.highalch);
    }
}
