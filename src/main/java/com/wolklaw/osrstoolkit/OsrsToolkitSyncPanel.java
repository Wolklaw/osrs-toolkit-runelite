package com.wolklaw.osrstoolkit;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ActionListener;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
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
 * This panel is the plugin's own component, so it updates the moment {@link #apply} is called,
 * logged in or not.
 */
final class OsrsToolkitSyncPanel extends PluginPanel
{
	private static final int WIDTH = PluginPanel.PANEL_WIDTH - 2 * PluginPanel.BORDER_OFFSET;

	private final JTextField tokenField = new JTextField();
	private final JTextArea statusLabel = wrappingText();

	OsrsToolkitSyncPanel(String initialToken, Consumer<String> onSubmit)
	{
		super(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		body.add(fullWidth(title()));
		body.add(Box.createVerticalStrut(6));
		body.add(fullWidth(help()));
		body.add(Box.createVerticalStrut(12));
		body.add(fullWidth(tokenField));
		body.add(Box.createVerticalStrut(6));

		JButton connect = connectButton();
		ActionListener submit = event -> {
			String token = tokenField.getText().trim();
			if (!token.isEmpty())
			{
				onSubmit.accept(token);
			}
		};
		tokenField.addActionListener(submit);
		connect.addActionListener(submit);
		body.add(fullWidth(connect));
		body.add(Box.createVerticalStrut(10));

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		body.add(fullWidth(statusLabel));

		tokenField.setText(initialToken);
		tokenField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		tokenField.setForeground(Color.WHITE);
		tokenField.setFont(FontManager.getRunescapeSmallFont());
		setNeutral("Not sending. Switch on \"Send to OSRS Toolkit Sync\" in this plugin's settings.");

		add(body, BorderLayout.NORTH);
	}

	private static JLabel title()
	{
		JLabel title = new JLabel("OSRS Toolkit Sync");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		return title;
	}

	private static JTextArea help()
	{
		JTextArea help = wrappingText();
		help.setFont(FontManager.getRunescapeSmallFont());
		help.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		setWrappedText(help,
			"Paste the pairing token from your Profile page on runescope.app, then press "
				+ "Enter or Connect.");
		return help;
	}

	/**
	 * A JLabel styled to look like one, but a real JTextArea underneath.
	 *
	 * An HTML JLabel does not know how many lines it needs until it has been laid out at a
	 * real width, and by then BoxLayout has often already asked for its preferred size once
	 * -- the classic Swing trap where wrapped text renders as one line, cropped. JTextArea
	 * reflows against whatever width it is actually given, but only reliably reports that in
	 * its own preferred size once it already knows that width, which is the same chicken-and
	 * -egg problem one level down: see {@link #setWrappedText}.
	 */
	private static JTextArea wrappingText()
	{
		JTextArea text = new JTextArea();
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setEditable(false);
		text.setFocusable(false);
		text.setOpaque(false);
		text.setBorder(null);
		return text;
	}

	/**
	 * Set a wrapping text area's content and lock in the preferred height that content needs
	 * at this panel's fixed width, computed right now rather than left for BoxLayout to ask
	 * for later.
	 *
	 * A freshly built or freshly retexted JTextArea has not been given a width by any layout
	 * pass yet, so its own {@code getPreferredSize()} answers as if the text were one
	 * unwrapped line -- which is what BoxLayout asks for the moment it first measures this
	 * container, before ever assigning real widths. {@code setSize} first, so the text area's
	 * view has something to wrap against when {@code getPreferredSize()} is asked immediately
	 * after; the answer is then pinned down explicitly rather than trusted to survive.
	 */
	private static void setWrappedText(JTextArea area, String text)
	{
		area.setText(text);
		area.setSize(WIDTH, Short.MAX_VALUE);
		area.setPreferredSize(new Dimension(WIDTH, area.getPreferredSize().height));
	}

	private static JButton connectButton()
	{
		// setOpaque/setBorderPainted/setContentAreaFilled together are what it takes to get a
		// flat, theme-colored button out of Swing's native look and feel rather than a
		// Windows-grey one sitting in the middle of a dark panel.
		JButton button = new JButton("Connect");
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setForeground(Color.WHITE);
		button.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
		button.setFocusPainted(false);
		button.setBorderPainted(false);
		button.setOpaque(true);
		return button;
	}

	/** Stretched to the panel's own width and pinned there, so BoxLayout does not shrink it
	 *  back down to its content -- the failure mode that put the button in the corner. */
	private static <T extends JComponent> T fullWidth(T component)
	{
		component.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		component.setMaximumSize(new Dimension(WIDTH, Integer.MAX_VALUE));
		return component;
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
			setWrappedText(statusLabel, text);
			statusLabel.setForeground(textColor);
			statusLabel.revalidate();
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
