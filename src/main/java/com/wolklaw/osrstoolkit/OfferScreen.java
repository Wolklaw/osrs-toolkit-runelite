package com.wolklaw.osrstoolkit;

import java.util.Objects;

/**
 * Where in the Grand Exchange the player is standing.
 *
 * Unlike everything else this plugin reports, this has not happened yet — which is the point.
 * The numbers a trade needs sit on a row in the desktop app's journal, and the moment worth
 * pointing at that row is while the player is in front of the box waiting to be typed into.
 */
final class OfferScreen
{
	/** Zero where the interface is open but on nothing in particular. */
	final int itemId;
	final String itemName;
	/** "buy", "sell", or null where no item is chosen to have a side. */
	final String side;

	OfferScreen(int itemId, String itemName, String side)
	{
		this.itemId = itemId;
		this.itemName = itemName;
		this.side = side;
	}

	/** Compared every tick to decide whether anything is worth sending, so equality is the gate. */
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
