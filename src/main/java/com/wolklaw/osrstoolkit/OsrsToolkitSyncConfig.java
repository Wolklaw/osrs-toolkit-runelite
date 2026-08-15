package com.wolklaw.osrstoolkit;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(OsrsToolkitSyncConfig.GROUP)
public interface OsrsToolkitSyncConfig extends Config
{
	String GROUP = "osrs-toolkit-sync";

	@ConfigItem(
		keyName = "trackGrandExchange",
		name = "Track Grand Exchange fills",
		description = "Save completed and partial Grand Exchange fills for the OSRS Toolkit journal"
	)
	default boolean trackGrandExchange()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackPlayerTrades",
		name = "Track player trades",
		description = "Locally record the other player's name and the items given and received. "
			+ "Nothing is uploaded."
	)
	default boolean trackPlayerTrades()
	{
		return false;
	}

	@ConfigItem(
		keyName = "trackPvmLoadout",
		name = "Sync gear and bank for PvM readiness",
		description = "When you open your bank, record your equipped gear, inventory, bank "
			+ "contents, and skill levels so the desktop app can compare them against PvM "
			+ "activity checklists. Nothing is uploaded."
	)
	default boolean trackPvmLoadout()
	{
		return false;
	}
}
