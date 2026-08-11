package com.wolklaw.osrstoolkit;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class OsrsToolkitSyncPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(OsrsToolkitSyncPlugin.class);
		RuneLite.main(args);
	}
}
