package com.wolklaw.osrstoolkit;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(OsrsToolkitSyncConfig.GROUP)
public interface OsrsToolkitSyncConfig extends Config
{
	String GROUP = "osrs-toolkit-sync";

	@ConfigSection(
		name = "Connection",
		description = "Where this plugin sends what it records, and the token that says it is yours",
		position = 0
	)
	String connectionSection = "connection";

	@ConfigSection(
		name = "What to record",
		description = "Which of your activity is recorded and sent",
		position = 1
	)
	String recordingSection = "recording";

	@ConfigItem(
		keyName = "syncEnabled",
		name = "Send to OSRS Toolkit Sync",
		description = "Send what this plugin records to the OSRS Toolkit sync service so the "
			+ "desktop app can import it. Nothing is sent until this is on and both the address "
			+ "and token below are filled in.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or "
			+ "verified by RuneLite developers",
		section = connectionSection,
		position = 0
	)
	default boolean syncEnabled()
	{
		return false;
	}

	@ConfigItem(
		keyName = "serverUrl",
		name = "Service address",
		description = "The address of the sync service. Leave this as it is unless you run the "
			+ "service yourself, in which case point it at your own copy.",
		section = connectionSection,
		position = 1
	)
	default String serverUrl()
	{
		return "https://sync.runescope.app";
	}

	@ConfigItem(
		keyName = "pairingToken",
		name = "Pairing token",
		description = "The token the sync service issued you. The desktop app shows the same one. "
			+ "It identifies your queue and nothing else.",
		secret = true,
		section = connectionSection,
		position = 2
	)
	default String pairingToken()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showConnectionOverlay",
		name = "Show connection status overlay",
		description = "Show a small always-visible line saying whether the sync connection is "
			+ "currently working, instead of only finding out when something you did should have "
			+ "sent something.",
		section = connectionSection,
		position = 3
	)
	default boolean showConnectionOverlay()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackGrandExchange",
		name = "Track Grand Exchange fills",
		description = "Record completed and partial Grand Exchange fills for the OSRS Toolkit journal",
		section = recordingSection,
		position = 0
	)
	default boolean trackGrandExchange()
	{
		return true;
	}

	@ConfigItem(
		keyName = "trackPlayerTrades",
		name = "Track player trades",
		description = "Record the other player's name and the items given and received.",
		section = recordingSection,
		position = 1
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
			+ "activity checklists.",
		section = recordingSection,
		position = 2
	)
	default boolean trackPvmLoadout()
	{
		return false;
	}

	@ConfigItem(
		keyName = "trackLoot",
		name = "Track valuable loot",
		description = "Record the items an NPC drops when you kill it, so the desktop app can "
			+ "show what a PvM trip actually paid.",
		section = recordingSection,
		position = 3
	)
	default boolean trackLoot()
	{
		return false;
	}

	@ConfigItem(
		keyName = "trackDeaths",
		name = "Track deaths",
		description = "When you die, record the gear and inventory you had on you, so the "
			+ "desktop app can show what a PvM trip put at risk.",
		section = recordingSection,
		position = 4
	)
	default boolean trackDeaths()
	{
		return false;
	}
}
