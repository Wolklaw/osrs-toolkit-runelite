package com.wolklaw.osrstoolkit;

import java.util.Objects;

/**
 * Where in the Grand Exchange the player is standing.
 *
 * Everything else this plugin reports has already happened — a fill, an offer placed, a trade
 * accepted — and the desktop app can take its time hearing about it. This is the one thing that
 * has not happened yet, and that is the whole point of it: the numbers a trade needs are sitting
 * on a row in the desktop app's journal, and the moment worth pointing at that row is while the
 * player is in front of the interface that wants them.
 *
 * A trade is a session and not a moment, so this says as much as it can at each stage of one. An
 * {@code itemId} of zero is the interface open with nothing in particular chosen — watching an
 * offer fill, or collecting one — which the desktop app pairs with the slots it already reads. A
 * real {@code itemId} is the "Set up offer" box open on that item, the one screen in the whole
 * process with two empty boxes waiting to be typed into.
 */
final class OfferScreen
{
	/** Zero where the interface is open but on nothing in particular. */
	final int itemId;
	/** Null alongside a zero {@link #itemId}. */
	final String itemName;
	/** "buy", "sell", or null where no item is chosen to have a side. */
	final String side;

	OfferScreen(int itemId, String itemName, String side)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.side = side;
	}

	/**
	 * Compared field by field because the plugin only writes this out when it changes: the check
	 * for "is this still the same screen?" runs every game tick, and every tick that answers yes
	 * is a file write not made.
	 */
	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof OfferScreen))
		{
			return false;
		}
		OfferScreen that = (OfferScreen) other;
		return itemId == that.itemId
			&& Objects.equals(itemName, that.itemName)
			&& Objects.equals(side, that.side);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(itemId, itemName, side);
	}
}
