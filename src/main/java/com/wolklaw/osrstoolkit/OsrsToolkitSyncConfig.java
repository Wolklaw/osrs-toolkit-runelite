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
		description = "The token that says what this plugin records is yours",
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
			+ "is filled in below.",
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
		keyName = "pairingToken",
		name = "Pairing token",
		description = "The token from your Profile page on runescope.app. Paste it here and "
			+ "the Status line below says whether it was accepted. It identifies your queue "
			+ "and nothing else.",
		secret = true,
		section = connectionSection,
		position = 1
	)
	default String pairingToken()
	{
		return "";
	}

	/**
	 * Written by the plugin, not by the player.
	 *
	 * The token field is a password field -- {@code secret} renders one -- so pasting into it
	 * shows a row of dots and no way to tell a good paste from a bad one. The chatbox answer
	 * this used to rely on does not exist at the login screen, which is where a plugin is
	 * usually set up. This line is in the panel the token was pasted into, and it reads the
	 * same whether or not anyone is logged in.
	 *
	 * Editing it does nothing: the next thing the plugin learns overwrites it.
	 */
	@ConfigItem(
		keyName = "connectionStatus",
		name = "Status",
		description = "Whether the token above was accepted. Set by the plugin; editing it "
			+ "has no effect.",
		section = connectionSection,
		position = 2
	)
	default String connectionStatus()
	{
		return "Not sending yet.";
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
