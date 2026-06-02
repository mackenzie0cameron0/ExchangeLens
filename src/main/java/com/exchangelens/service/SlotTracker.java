package com.exchangelens.service;

import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.client.eventbus.Subscribe;
import javax.inject.Singleton;

@Singleton
public class SlotTracker
{
    private final int[]     buyPrices = new int[8];
    private final int[]     itemIds   = new int[8];
    private final boolean[] isBuy     = new boolean[8];

    @Subscribe
    public void onOfferChanged(GrandExchangeOfferChanged event)
    {
        int slot = event.getSlot();
        if (slot < 0 || slot >= 8) return;

        GrandExchangeOfferState state = event.getOffer().getState();
        if (state == GrandExchangeOfferState.EMPTY
                || state == GrandExchangeOfferState.CANCELLED_BUY
                || state == GrandExchangeOfferState.CANCELLED_SELL)
        {
            buyPrices[slot] = 0;
            itemIds[slot]   = 0;
            isBuy[slot]     = false;
            return;
        }

        itemIds[slot] = event.getOffer().getItemId();
        if (state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.BOUGHT)
        {
            buyPrices[slot] = event.getOffer().getPrice();
            isBuy[slot]     = true;
        }
        else
        {
            buyPrices[slot] = 0;
            isBuy[slot]     = false;
        }
    }

    public int     getBuyPrice(int slot) { return buyPrices[slot]; }
    public int     getItemId(int slot)   { return itemIds[slot]; }
    public boolean isBuySlot(int slot)   { return isBuy[slot]; }
}
