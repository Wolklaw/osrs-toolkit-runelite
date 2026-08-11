package com.wolklaw.osrstoolkit;

import java.util.Collections;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class SyncEventTest
{
	@Test
	public void createsDesktopCompatibleGrandExchangePayload()
	{
		OfferSnapshot snapshot = new OfferSnapshot();
		snapshot.slot = 3;
		snapshot.itemId = 453;
		snapshot.itemName = "Coal";
		snapshot.offerPrice = 200;
		snapshot.totalQuantity = 100;
		snapshot.quantityFilled = 10;
		snapshot.spentGp = 2_000;
		snapshot.state = "SELLING";
		snapshot.offerId = "offer-id";

		SyncEvent event = SyncEvent.geFill("account-hash", "Tester", snapshot, 10, 2_000);
		GeFillPayload payload = (GeFillPayload) event.payload;

		assertEquals(1, event.schema_version);
		assertEquals("ge_fill", event.event_type);
		assertEquals("account-hash", event.account.hash);
		assertEquals("sell", payload.side);
		assertEquals(453, payload.item_id);
		assertEquals(10, payload.quantity);
		assertEquals(2_000, payload.coins);
		assertNotNull(event.event_id);
		assertNotNull(event.occurred_at);
	}

	@Test
	public void createsPlayerTradeWithBothSides()
	{
		SyncItem coins = new SyncItem(995, "Coins", 1_000_000, 1);
		SyncItem item = new SyncItem(2, "Cannonball", 5_000, 180);

		SyncEvent event = SyncEvent.playerTrade(
			"account-hash",
			"Tester",
			"Other player",
			Collections.singletonList(coins),
			Collections.singletonList(item)
		);
		PlayerTradePayload payload = (PlayerTradePayload) event.payload;

		assertEquals("player_trade", event.event_type);
		assertEquals("Other player", payload.counterparty);
		assertEquals(1_000_000, payload.given.get(0).quantity);
		assertEquals(5_000, payload.received.get(0).quantity);
	}
}
