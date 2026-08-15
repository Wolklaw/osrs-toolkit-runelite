package com.wolklaw.osrstoolkit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SyncEvent
{
	final int schema_version = 1;
	final String event_id = UUID.randomUUID().toString();
	final String event_type;
	final String occurred_at = Instant.now().toString();
	final SyncAccount account;
	final Object payload;

	private SyncEvent(String eventType, SyncAccount account, Object payload)
	{
		this.event_type = eventType;
		this.account = account;
		this.payload = payload;
	}

	static SyncEvent geFill(String accountHash, String accountName, OfferSnapshot current,
		int quantity, int coins)
	{
		return new SyncEvent(
			"ge_fill",
			new SyncAccount(accountHash, accountName),
			new GeFillPayload(current, quantity, coins)
		);
	}

	static SyncEvent geOfferOpened(String accountHash, String accountName, OfferSnapshot current)
	{
		return new SyncEvent(
			"ge_offer_opened",
			new SyncAccount(accountHash, accountName),
			new GeOfferOpenedPayload(current)
		);
	}

	static SyncEvent playerTrade(String accountHash, String accountName, String counterparty,
		List<SyncItem> given, List<SyncItem> received)
	{
		return new SyncEvent(
			"player_trade",
			new SyncAccount(accountHash, accountName),
			new PlayerTradePayload(counterparty, given, received)
		);
	}

	static SyncEvent loadoutSnapshot(String accountHash, String accountName,
		List<SyncItem> equipment, List<SyncItem> inventory, List<SyncItem> bank,
		Map<String, Integer> skills)
	{
		return new SyncEvent(
			"loadout_snapshot",
			new SyncAccount(accountHash, accountName),
			new LoadoutSnapshotPayload(equipment, inventory, bank, skills)
		);
	}
}

final class SyncAccount
{
	final String hash;
	final String name;

	SyncAccount(String hash, String name)
	{
		this.hash = hash;
		this.name = name;
	}
}

final class GeFillPayload
{
	final String side;
	final int item_id;
	final String item_name;
	final int quantity;
	final int coins;
	final String offer_id;
	final int offer_slot;
	final int offer_price;
	final String offer_state;
	final int total_quantity;

	GeFillPayload(OfferSnapshot snapshot, int quantity, int coins)
	{
		this.side = snapshot.side();
		this.item_id = snapshot.itemId;
		this.item_name = snapshot.itemName;
		this.quantity = quantity;
		this.coins = coins;
		this.offer_id = snapshot.offerId;
		this.offer_slot = snapshot.slot;
		this.offer_price = snapshot.offerPrice;
		this.offer_state = snapshot.state;
		this.total_quantity = snapshot.totalQuantity;
	}
}

final class GeOfferOpenedPayload
{
	final String side;
	final int item_id;
	final String item_name;
	final String offer_id;
	final int offer_slot;
	final int offer_price;
	final int total_quantity;

	GeOfferOpenedPayload(OfferSnapshot snapshot)
	{
		this.side = snapshot.side();
		this.item_id = snapshot.itemId;
		this.item_name = snapshot.itemName;
		this.offer_id = snapshot.offerId;
		this.offer_slot = snapshot.slot;
		this.offer_price = snapshot.offerPrice;
		this.total_quantity = snapshot.totalQuantity;
	}
}

final class PlayerTradePayload
{
	final String counterparty;
	final List<SyncItem> given;
	final List<SyncItem> received;

	PlayerTradePayload(String counterparty, List<SyncItem> given, List<SyncItem> received)
	{
		this.counterparty = counterparty;
		this.given = given;
		this.received = received;
	}
}

final class LoadoutSnapshotPayload
{
	final List<SyncItem> equipment;
	final List<SyncItem> inventory;
	final List<SyncItem> bank;
	final Map<String, Integer> skills;

	LoadoutSnapshotPayload(List<SyncItem> equipment, List<SyncItem> inventory,
		List<SyncItem> bank, Map<String, Integer> skills)
	{
		this.equipment = equipment;
		this.inventory = inventory;
		this.bank = bank;
		this.skills = skills;
	}
}

final class SyncItem
{
	final int item_id;
	final String item_name;
	final int quantity;
	final int unit_value;

	SyncItem(int itemId, String itemName, int quantity, int unitValue)
	{
		this.item_id = itemId;
		this.item_name = itemName;
		this.quantity = quantity;
		this.unit_value = unitValue;
	}
}
