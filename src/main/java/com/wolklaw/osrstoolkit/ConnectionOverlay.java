package com.wolklaw.osrstoolkit;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * A small, always-visible line saying whether the sync connection is actually working right
 * now — not just at the moment settings were last touched.
 *
 * A chat message answers "did that just work" once and then scrolls away with everything
 * else in a busy chat window. This answers "is it working" for as long as the player cares to
 * look, the same way any other status overlay does, without needing to reopen the settings
 * panel to find out.
 */
final class ConnectionOverlay extends OverlayPanel
{
	private static final Color CONNECTED = new Color(112, 214, 161);
	private static final Color TROUBLE = new Color(226, 104, 95);
	private static final Color PENDING = Color.LIGHT_GRAY;

	private final OsrsToolkitSyncPlugin plugin;
	private final OsrsToolkitSyncConfig config;

	ConnectionOverlay(OsrsToolkitSyncPlugin plugin, OsrsToolkitSyncConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Nothing to say for someone not using this at all — an overlay with nothing to
		// report is exactly the clutter an opt-in status line should never become.
		if (!config.syncEnabled() || !config.showConnectionOverlay())
		{
			return null;
		}
		SyncClient.Outcome outcome = plugin.lastConnectionOutcome;
		String status;
		Color color;
		if (outcome == null)
		{
			status = "Connecting…";
			color = PENDING;
		}
		else
		{
			switch (outcome)
			{
				case DELIVERED:
					status = "Connected";
					color = CONNECTED;
					break;
				case UNAUTHORIZED:
					status = "Token rejected";
					color = TROUBLE;
					break;
				case REFUSED:
					status = "Service refused";
					color = TROUBLE;
					break;
				default:
					status = "Unreachable";
					color = TROUBLE;
					break;
			}
		}
		panelComponent.getChildren().add(
			LineComponent.builder().left("OSRS Toolkit Sync").right(status).rightColor(color).build()
		);
		return super.render(graphics);
	}
}
