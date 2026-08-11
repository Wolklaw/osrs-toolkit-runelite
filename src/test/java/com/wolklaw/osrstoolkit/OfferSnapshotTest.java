package com.wolklaw.osrstoolkit;

import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OfferSnapshotTest
{
	@Test
	public void keepsTheSameLifecycleForMonotonicFillUpdates()
	{
		OfferSnapshot previous = OfferSnapshot.from(
			2,
			new TestOffer(100, 1_000, 10, 10_000, GrandExchangeOfferState.BUYING),
			"Test item"
		);
		OfferSnapshot current = OfferSnapshot.from(
			2,
			new TestOffer(100, 1_000, 15, 15_100, GrandExchangeOfferState.BUYING),
			"Test item"
		);

		assertTrue(current.continues(previous));
		assertEquals("buy", current.side());
		assertEquals(5, current.quantityFilled - previous.quantityFilled);
		assertEquals(5_100, current.spentGp - previous.spentGp);
	}

	@Test
	public void changedOfferPriceStartsANewLifecycle()
	{
		OfferSnapshot previous = OfferSnapshot.from(
			0,
			new TestOffer(100, 1_000, 0, 0, GrandExchangeOfferState.SELLING),
			"Test item"
		);
		OfferSnapshot replacement = OfferSnapshot.from(
			0,
			new TestOffer(100, 1_050, 0, 0, GrandExchangeOfferState.SELLING),
			"Test item"
		);

		assertFalse(replacement.continues(previous));
		assertEquals("sell", replacement.side());
	}

	@Test
	public void completedOfferCannotBecomeANewOfferInTheSameLifecycle()
	{
		OfferSnapshot completed = OfferSnapshot.from(
			1,
			new TestOffer(100, 1_000, 100, 100_000, GrandExchangeOfferState.BOUGHT),
			"Test item"
		);
		OfferSnapshot newOffer = OfferSnapshot.from(
			1,
			new TestOffer(100, 1_000, 100, 100_000, GrandExchangeOfferState.BUYING),
			"Test item"
		);

		assertFalse(newOffer.continues(completed));
	}

	private static final class TestOffer implements GrandExchangeOffer
	{
		private final int itemId;
		private final int price;
		private final int quantitySold;
		private final int spent;
		private final GrandExchangeOfferState state;

		private TestOffer(int itemId, int price, int quantitySold, int spent,
			GrandExchangeOfferState state)
		{
			this.itemId = itemId;
			this.price = price;
			this.quantitySold = quantitySold;
			this.spent = spent;
			this.state = state;
		}

		@Override
		public int getQuantitySold()
		{
			return quantitySold;
		}

		@Override
		public int getItemId()
		{
			return itemId;
		}

		@Override
		public int getTotalQuantity()
		{
			return 100;
		}

		@Override
		public int getPrice()
		{
			return price;
		}

		@Override
		public int getSpent()
		{
			return spent;
		}

		@Override
		public GrandExchangeOfferState getState()
		{
			return state;
		}
	}
}
