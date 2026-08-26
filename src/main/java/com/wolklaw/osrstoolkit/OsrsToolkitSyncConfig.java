package com.wolklaw.osrstoolkit;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(OsrsToolkitSyncConfig.GROUP)
public interface OsrsToolkitSyncConfig extends Config
{
	String GROUP = "osrs-toolkit-sync";

	/** The pairing token's own key, referenced by both {@link #pairingToken} and the plugin
	 * that listens for it changing. */
	String PAIRING_TOKEN_KEY = "pairingToken";

	@ConfigSection(
		name = "Connection",
		description = "The master switch. The pairing token and a live connection status are "
			+ "in this plugin's sidebar panel, not here.",
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
		description = "Send what this plugin records to the OSRS Toolkit sync service, so "
			+ "runescope.app can show it. Nothing is sent until this is on and a pairing token "
			+ "is entered in this plugin's sidebar panel.",
		warning = "This feature submits your IP address to a 3rd-party server not controlled or "
			+ "verified by RuneLite developers",
		section = connectionSection,
		position = 0
	)
	default boolean syncEnabled()
	{
		return false;
	}

	/**
	 * Hidden rather than removed: still the one place this value is actually stored, still
	 * read by {@code applyConnectionSettings}, still what the sidebar panel writes to and
	 * reads its starting value from. RuneLite's config panel draws a field once, when it is
	 * opened, and has no listener for a value changing under it -- a token typed here would
	 * show pasted dots and then nothing, the exact problem the sidebar panel exists to fix.
	 */
	@ConfigItem(
		keyName = PAIRING_TOKEN_KEY,
		name = "Pairing token",
		description = "The token from your Profile page on runescope.app. Entered from this "
			+ "plugin's sidebar panel, not here.",
		secret = true,
		hidden = true,
		section = connectionSection,
		position = 1
	)
	default String pairingToken()
	{
		return "";
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
