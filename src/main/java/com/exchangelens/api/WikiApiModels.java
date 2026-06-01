package com.exchangelens.api;

import com.google.gson.annotations.SerializedName;
import java.util.Map;

public final class WikiApiModels
{
    private WikiApiModels() {}

    public static class LatestResponse
    {
        public Map<String, LatestPriceData> data;
    }

    public static class LatestPriceData
    {
        public Integer high;
        public Long highTime;
        public Integer low;
        public Long lowTime;
    }

    public static class AverageResponse
    {
        public Map<String, AveragePriceData> data;
    }

    public static class AveragePriceData
    {
        public Integer avgHighPrice;
        public Integer highPriceVolume;
        public Integer avgLowPrice;
        public Integer lowPriceVolume;
    }

    public static class MappingEntry
    {
        public int id;
        public String name;
        public boolean members;
        public int limit;
        @SerializedName("lowalch")
        public int lowalch;
        @SerializedName("highalch")
        public int highalch;
        public String examine;
    }
}
