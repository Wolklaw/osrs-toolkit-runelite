package com.wolklaw.osrstoolkit;

import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The sidebar panel that replaced writing status into a config field -- the fix for a field
 * that never repainted once RuneLite's settings panel had already drawn it once. Assertions run
 * through {@code invokeAndWait} because every real caller updates this off the EDT, same as a
 * heartbeat callback would.
 */
public class OsrsToolkitSyncPanelTest
{
	private static OsrsToolkitSyncPanel newPanel(AtomicReference<String> submitted) throws Exception
	{
		AtomicReference<OsrsToolkitSyncPanel> built = new AtomicReference<>();
		SwingUtilities.invokeAndWait(
			() -> built.set(new OsrsToolkitSyncPanel("existing-token", submitted::set))
		);
		return built.get();
	}

	@Test
	public void startsWithTheAlreadySavedToken() throws Exception
	{
		OsrsToolkitSyncPanel panel = newPanel(new AtomicReference<>());
		assertEquals("Not sending. Switch on \"Send to OSRS Toolkit Sync\" in this plugin's settings.",
			panel.statusText());
	}

	@Test
	public void submittingTheFieldCallsBack() throws Exception
	{
		AtomicReference<String> submitted = new AtomicReference<>();
		OsrsToolkitSyncPanel panel = newPanel(submitted);

		SwingUtilities.invokeAndWait(() -> panel.typeAndSubmit("  a-new-token  "));

		assertEquals("a-new-token", submitted.get());
	}

	@Test
	public void submittingBlankDoesNothing() throws Exception
	{
		AtomicReference<String> submitted = new AtomicReference<>();
		OsrsToolkitSyncPanel panel = newPanel(submitted);

		SwingUtilities.invokeAndWait(() -> panel.typeAndSubmit("   "));

		assertNull(submitted.get());
	}

	@Test
	public void connectedTurnsGreen() throws Exception
	{
		OsrsToolkitSyncPanel panel = newPanel(new AtomicReference<>());

		SwingUtilities.invokeAndWait(() -> panel.setConnected("Connected as Zed."));

		assertEquals("Connected as Zed.", panel.statusText());
		assertEquals(ColorScheme.PROGRESS_COMPLETE_COLOR, panel.statusColor());
	}

	@Test
	public void rejectedTurnsRed() throws Exception
	{
		OsrsToolkitSyncPanel panel = newPanel(new AtomicReference<>());

		SwingUtilities.invokeAndWait(() -> panel.setRejected("Token not accepted."));

		assertEquals(ColorScheme.PROGRESS_ERROR_COLOR, panel.statusColor());
	}

	@Test
	public void unreachableIsNeitherGreenNorRed() throws Exception
	{
		// Not the token's fault, so it must not read as a rejection.
		OsrsToolkitSyncPanel panel = newPanel(new AtomicReference<>());

		SwingUtilities.invokeAndWait(() -> panel.setUnreachable("Could not reach the sync service."));

		assertEquals(ColorScheme.BRAND_ORANGE, panel.statusColor());
	}
}
