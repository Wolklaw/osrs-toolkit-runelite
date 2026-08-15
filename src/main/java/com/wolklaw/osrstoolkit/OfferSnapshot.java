package com.wolklaw.osrstoolkit;

import java.util.UUID;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

final class OfferSnapshot
{
	int slot;
	int itemId;
	String itemName;
	int offerPrice;
	int totalQuantity;
	int quantityFilled;
	int spentGp;
	String state;
	String offerId;

	OfferSnapshot()
	{
	}

	static OfferSnapshot from(int slot, GrandExchangeOffer offer, String itemName)
	{
		OfferSnapshot snapshot = new OfferSnapshot();
		snapshot.slot = slot;
		snapshot.itemId = offer.getItemId();
		snapshot.itemName = itemName;
		snapshot.offerPrice = offer.getPrice();
		snapshot.totalQuantity = offer.getTotalQuantity();
		snapshot.quantityFilled = offer.getQuantitySold();
		snapshot.spentGp = offer.getSpent();
		snapshot.state = offer.getState().name();
		snapshot.offerId = UUID.randomUUID().toString();
		return snapshot;
	}

	/**
	 * Snapshots round-trip through JSON on disk, so a damaged or future-schema state file can
	 * hand back a state name this build does not know. Every reader goes through here so a bad
	 * value degrades into "unrecognised" instead of throwing and wedging Grand Exchange sync.
	 */
	GrandExchangeOfferState offerState()
	{
		if (state == null)
		{
			return null;
		}
		try
		{
			return GrandExchangeOfferState.valueOf(state);
		}
		catch (IllegalArgumentException ex)
		{
			return null;
		}
	}

	boolean isValid()
	{
		return offerState() != null;
	}

	boolean isEmpty()
	{
		return itemId <= 0 || totalQuantity <= 0 || offerState() == GrandExchangeOfferState.EMPTY;
	}

	String side()
	{
		GrandExchangeOfferState offerState = offerState();
		if (offerState == null)
		{
			return "";
		}
		switch (offerState)
		{
			case BUYING:
			case BOUGHT:
			case CANCELLED_BUY:
				return "buy";
			case SELLING:
			case SOLD:
			case CANCELLED_SELL:
				return "sell";
			default:
				return "";
		}
	}

	boolean continues(OfferSnapshot previous)
	{
		return previous != null
			&& previous.isValid()
			&& !previous.isTerminal()
			&& itemId == previous.itemId
			&& offerPrice == previous.offerPrice
			&& totalQuantity == previous.totalQuantity
			&& side().equals(previous.side())
			&& quantityFilled >= previous.quantityFilled
			&& spentGp >= previous.spentGp;
	}

	boolean isTerminal()
	{
		GrandExchangeOfferState offerState = offerState();
		return offerState == GrandExchangeOfferState.BOUGHT
			|| offerState == GrandExchangeOfferState.SOLD
			|| isCancelled();
	}

	boolean isCancelled()
	{
		GrandExchangeOfferState offerState = offerState();
		return offerState == GrandExchangeOfferState.CANCELLED_BUY
			|| offerState == GrandExchangeOfferState.CANCELLED_SELL;
	}
}
