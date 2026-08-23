package com.wolklaw.osrstoolkit;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
		assertEquals(100, payload.total_quantity);
		assertNotNull(event.event_id);
		assertNotNull(event.occurred_at);
	}

	@Test
	public void createsOfferOpenedPayloadWithNothingFilledYet()
	{
		OfferSnapshot snapshot = new OfferSnapshot();
		snapshot.slot = 1;
		snapshot.itemId = 24_615;
		snapshot.itemName = "Blighted teleport spell sack";
		snapshot.offerPrice = 60;
		snapshot.totalQuantity = 8_000;
		snapshot.quantityFilled = 0;
		snapshot.spentGp = 0;
		snapshot.state = "BUYING";
		snapshot.offerId = "offer-id";

		SyncEvent event = SyncEvent.geOfferOpened("account-hash", "Tester", snapshot, false);
		GeOfferOpenedPayload payload = (GeOfferOpenedPayload) event.payload;

		assertEquals("ge_offer_opened", event.event_type);
		assertEquals("buy", payload.side);
		assertEquals(24_615, payload.item_id);
		assertEquals("Blighted teleport spell sack", payload.item_name);
		assertEquals(8_000, payload.total_quantity);
		assertEquals(60, payload.offer_price);
		assertEquals("offer-id", payload.offer_id);
		assertFalse(payload.restored);
	}

	@Test
	public void marksAnOfferTheGameResentSoTheDesktopDoesNotReadItAsNewlyPlaced()
	{
		OfferSnapshot snapshot = new OfferSnapshot();
		snapshot.slot = 3;
		snapshot.itemId = 23_736;
		snapshot.itemName = "Divine ranging potion(3)";
		snapshot.offerPrice = 4_159;
		snapshot.totalQuantity = 150;
		snapshot.quantityFilled = 73;
		snapshot.spentGp = 303_607;
		snapshot.state = "BUYING";
		snapshot.offerId = "offer-id";

		SyncEvent event = SyncEvent.geOfferOpened("account-hash", "Tester", snapshot, true);

		assertTrue(((GeOfferOpenedPayload) event.payload).restored);
	}

	@Test
	public void createsCancelledPayloadMatchingTheOpeningSoTheDesktopCanPairThem()
	{
		OfferSnapshot snapshot = new OfferSnapshot();
		snapshot.slot = 1;
		snapshot.itemId = 24_615;
		snapshot.itemName = "Blighted teleport spell sack";
		snapshot.offerPrice = 60;
		snapshot.totalQuantity = 8_000;
		snapshot.quantityFilled = 3_000;
		snapshot.spentGp = 180_000;
		snapshot.state = "CANCELLED_BUY";
		snapshot.offerId = "offer-id";

		SyncEvent event = SyncEvent.geOfferCancelled("account-hash", "Tester", snapshot);
		GeOfferCancelledPayload payload = (GeOfferCancelledPayload) event.payload;

		assertEquals("ge_offer_cancelled", event.event_type);
		assertEquals("buy", payload.side);
		assertEquals(24_615, payload.item_id);
		assertEquals(8_000, payload.total_quantity);
		assertEquals(60, payload.offer_price);
		assertEquals("offer-id", payload.offer_id);
		assertEquals(1, payload.offer_slot);
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

	@Test
	public void createsLoadoutSnapshotWithEquipmentInventoryBankAndSkills()
	{
		List<SyncItem> equipment = Collections.singletonList(new SyncItem(1, "Rune scimitar", 1, 15_000));
		List<SyncItem> inventory = Collections.singletonList(new SyncItem(995, "Coins", 250_000, 1));
		List<SyncItem> bank = Collections.singletonList(new SyncItem(11_802, "Armadyl godsword", 1, 40_000_000));
		Map<String, Integer> skills = Collections.singletonMap("Attack", 75);

		SyncEvent event = SyncEvent.loadoutSnapshot(
			"account-hash", "Tester", equipment, inventory, bank, skills
		);
		LoadoutSnapshotPayload payload = (LoadoutSnapshotPayload) event.payload;

		assertEquals(1, event.schema_version);
		assertEquals("loadout_snapshot", event.event_type);
		assertEquals("account-hash", event.account.hash);
		assertEquals(1, payload.equipment.size());
		assertEquals(1, payload.inventory.size());
		assertEquals(1, payload.bank.size());
		assertEquals(Integer.valueOf(75), payload.skills.get("Attack"));
		assertTrue(payload.bank.get(0).unit_value > 0);
		assertNotNull(event.event_id);
	}

	@Test
	public void createsNpcLootWithTheNpcNameAndItems()
	{
		List<SyncItem> items = Collections.singletonList(new SyncItem(11_802, "Armadyl godsword", 1, 40_000_000));

		SyncEvent event = SyncEvent.npcLoot("account-hash", "Tester", "Zulrah", items);
		NpcLootPayload payload = (NpcLootPayload) event.payload;

		assertEquals(1, event.schema_version);
		assertEquals("npc_loot", event.event_type);
		assertEquals("account-hash", event.account.hash);
		assertEquals("Zulrah", payload.npc_name);
		assertEquals(1, payload.items.size());
		assertEquals(40_000_000, payload.items.get(0).unit_value);
		assertNotNull(event.event_id);
	}

	@Test
	public void createsPlayerDeathWithEquipmentInventoryAndSkullState()
	{
		List<SyncItem> equipment = Collections.singletonList(new SyncItem(11_802, "Armadyl godsword", 1, 40_000_000));
		List<SyncItem> inventory = Collections.singletonList(new SyncItem(995, "Coins", 250_000, 1));

		SyncEvent event = SyncEvent.playerDeath("account-hash", "Tester", true, equipment, inventory);
		PlayerDeathPayload payload = (PlayerDeathPayload) event.payload;

		assertEquals("player_death", event.event_type);
		assertTrue(payload.skulled);
		assertEquals(1, payload.equipment.size());
		assertEquals(1, payload.inventory.size());
		assertEquals(250_000, payload.inventory.get(0).quantity);
		assertNotNull(event.event_id);
	}
}
