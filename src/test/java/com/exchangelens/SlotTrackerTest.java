package com.exchangelens;

import com.exchangelens.service.SlotTracker;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GrandExchangeOfferChanged;
import org.junit.Test;
import static org.junit.Assert.*;

public class SlotTrackerTest
{
    private GrandExchangeOfferChanged buildEvent(int slot, int itemId, int price,
                                                  GrandExchangeOfferState state)
    {
        GrandExchangeOffer offer = new GrandExchangeOffer()
        {
            public GrandExchangeOfferState getState() { return state; }
            public int getItemId()                    { return itemId; }
            public int getPrice()                     { return price; }
            public int getQuantitySold()              { return 0; }
            public int getTotalQuantity()             { return 1; }
            public int getSpent()                     { return 0; }
        };
        GrandExchangeOfferChanged event = new GrandExchangeOfferChanged();
        event.setSlot(slot);
        event.setOffer(offer);
        return event;
    }

    @Test
    public void recordsBuyPriceAndItemId()
    {
        SlotTracker tracker = new SlotTracker();
        tracker.onGrandExchangeOfferChanged(buildEvent(0, 4151, 1_950_000, GrandExchangeOfferState.BUYING));
        assertEquals(1_950_000, tracker.getBuyPrice(0));
        assertEquals(4151, tracker.getItemId(0));
        assertTrue(tracker.isBuySlot(0));
    }

    @Test
    public void clearsSlotOnEmpty()
    {
        SlotTracker tracker = new SlotTracker();
        tracker.onGrandExchangeOfferChanged(buildEvent(0, 4151, 1_950_000, GrandExchangeOfferState.BUYING));
        tracker.onGrandExchangeOfferChanged(buildEvent(0, 0, 0, GrandExchangeOfferState.EMPTY));
        assertEquals(0, tracker.getBuyPrice(0));
        assertEquals(0, tracker.getItemId(0));
        assertFalse(tracker.isBuySlot(0));
    }

    @Test
    public void sellSlotNotFlaggedAsBuy()
    {
        SlotTracker tracker = new SlotTracker();
        tracker.onGrandExchangeOfferChanged(buildEvent(2, 4151, 2_000_000, GrandExchangeOfferState.SELLING));
        assertFalse(tracker.isBuySlot(2));
        assertEquals(4151, tracker.getItemId(2));
    }

    @Test
    public void independentSlotsDoNotInterfere()
    {
        SlotTracker tracker = new SlotTracker();
        tracker.onGrandExchangeOfferChanged(buildEvent(0, 1001, 500, GrandExchangeOfferState.BUYING));
        tracker.onGrandExchangeOfferChanged(buildEvent(3, 1002, 1000, GrandExchangeOfferState.BUYING));
        assertEquals(500,  tracker.getBuyPrice(0));
        assertEquals(1000, tracker.getBuyPrice(3));
        assertEquals(1001, tracker.getItemId(0));
        assertEquals(1002, tracker.getItemId(3));
    }
}
