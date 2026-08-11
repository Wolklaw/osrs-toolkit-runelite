package com.wolklaw.osrstoolkit;

import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Item;

final class PendingPlayerTrade
{
	String counterparty = "Unknown player";
	final Map<Integer, Integer> given = new LinkedHashMap<>();
	final Map<Integer, Integer> received = new LinkedHashMap<>();

	void update(boolean isGiven, Item[] items)
	{
		Map<Integer, Integer> destination = isGiven ? given : received;
		destination.clear();
		if (items == null)
		{
			return;
		}
		for (Item item : items)
		{
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			destination.merge(item.getId(), item.getQuantity(), Integer::sum);
		}
	}

	boolean isEmpty()
	{
		return given.isEmpty() && received.isEmpty();
	}
}
