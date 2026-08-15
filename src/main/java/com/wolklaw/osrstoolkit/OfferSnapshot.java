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

	boolean isEmpty()
	{
		return itemId <= 0 || totalQuantity <= 0 || state.equals(GrandExchangeOfferState.EMPTY.name());
	}

	String side()
	{
		GrandExchangeOfferState offerState = GrandExchangeOfferState.valueOf(state);
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
		GrandExchangeOfferState offerState = GrandExchangeOfferState.valueOf(state);
		return offerState == GrandExchangeOfferState.BOUGHT
			|| offerState == GrandExchangeOfferState.SOLD
			|| offerState == GrandExchangeOfferState.CANCELLED_BUY
			|| offerState == GrandExchangeOfferState.CANCELLED_SELL;
	}
}
