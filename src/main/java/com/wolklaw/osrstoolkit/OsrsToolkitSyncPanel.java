package com.wolklaw.osrstoolkit;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The pairing token and a live connection status, in the sidebar rather than the settings
 * panel it used to live in.
 *
 * RuneLite's settings panel draws each field once, when it is opened, and has no listener for
 * a value changing underneath it -- a plugin writing its own connection status there through
 * {@code ConfigManager} updates what is stored, but the open panel never repaints to show it.
 * This panel is the plugin's own component, so it updates the moment {@link #setStatus} is
 * called, logged in or not.
 */
final class OsrsToolkitSyncPanel extends PluginPanel
{
	private final JTextField tokenField = new JTextField();
	private final JLabel statusLabel = new JLabel();

	OsrsToolkitSyncPanel(String initialToken, Consumer<String> onSubmit)
	{
		super(false);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JLabel title = new JLabel("OSRS Toolkit Sync");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);

		JLabel help = new JLabel(
			"<html>Paste the pairing token from your Profile page on runescope.app, then press "
				+ "Enter or Connect.</html>");
		help.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		help.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

		tokenField.setText(initialToken);
		tokenField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tokenField.setForeground(Color.WHITE);
		tokenField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(4, 6, 4, 6)
		));

		JButton connect = new JButton("Connect");
		connect.setFocusPainted(false);

		ActionListener submit = event -> {
			String token = tokenField.getText().trim();
			if (!token.isEmpty())
			{
				onSubmit.accept(token);
			}
		};
		tokenField.addActionListener(submit);
		connect.addActionListener(submit);

		JPanel tokenRow = new JPanel(new BorderLayout(6, 0));
		tokenRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tokenRow.add(tokenField, BorderLayout.CENTER);

		JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 6));
		buttonRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		buttonRow.add(connect);

		statusLabel.setHorizontalAlignment(SwingConstants.LEFT);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		setNeutral("Not sending. Switch on \"Send to OSRS Toolkit Sync\" in this plugin's settings.");

		JPanel body = new JPanel();
		body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.add(title);
		body.add(help);
		body.add(tokenRow);
		body.add(buttonRow);
		body.add(statusLabel);

		add(body, BorderLayout.NORTH);
	}

	/** Neutral wording with no accent -- nothing has been tried yet, or nothing is wrong. */
	void setNeutral(String text)
	{
		apply(text, ColorScheme.LIGHT_GRAY_COLOR, ColorScheme.MEDIUM_GRAY_COLOR);
	}

	/** Green: the token was accepted and this character is syncing. */
	void setConnected(String text)
	{
		apply(text, ColorScheme.PROGRESS_COMPLETE_COLOR, ColorScheme.PROGRESS_COMPLETE_COLOR);
	}

	/** Red: the token itself is the problem. */
	void setRejected(String text)
	{
		apply(text, ColorScheme.PROGRESS_ERROR_COLOR, ColorScheme.PROGRESS_ERROR_COLOR);
	}

	/** Amber: the service didn't answer, which is not the token's fault. */
	void setUnreachable(String text)
	{
		apply(text, ColorScheme.BRAND_ORANGE, ColorScheme.BRAND_ORANGE);
	}

	/** Blue: a request is in flight. */
	void setChecking(String text)
	{
		apply(text, ColorScheme.PROGRESS_INPROGRESS_COLOR, ColorScheme.MEDIUM_GRAY_COLOR);
	}

	/**
	 * Called from the IO thread's callbacks by way of clientThread, which is not the EDT --
	 * but also from this panel's own constructor, which runs on the EDT already, since
	 * plugins start on the Swing thread. Queueing unconditionally would still repaint
	 * correctly there, just one event-queue turn later than it needs to.
	 */
	private void apply(String text, Color textColor, Color fieldAccent)
	{
		Runnable update = () -> {
			statusLabel.setText("<html>" + text + "</html>");
			statusLabel.setForeground(textColor);
			tokenField.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(fieldAccent, 2),
				BorderFactory.createEmptyBorder(3, 5, 3, 5)
			));
		};
		if (SwingUtilities.isEventDispatchThread())
		{
			update.run();
		}
		else
		{
			SwingUtilities.invokeLater(update);
		}
	}

	@Override
	public Dimension getPreferredSize()
	{
		return new Dimension(PluginPanel.PANEL_WIDTH, super.getPreferredSize().height);
	}

	// -- test-only observability; no production code reads these -------------------------

	String statusText()
	{
		return statusLabel.getText();
	}

	Color statusColor()
	{
		return statusLabel.getForeground();
	}

	void typeAndSubmit(String token)
	{
		tokenField.setText(token);
		for (ActionListener listener : tokenField.getActionListeners())
		{
			listener.actionPerformed(null);
		}
	}
}
