# OSRS Toolkit Sync

RuneLite companion plugin for [OSRS Toolkit](https://github.com/Wolklaw/OSRS-Toolkit).

It records Grand Exchange fills while RuneLite is running and makes them available to the
desktop toolkit's Trade Journal—even when the toolkit was closed at the time of the trade.
Player-to-player trade tracking is available as an optional, off-by-default setting.

## What it records

- Partial and completed Grand Exchange fills, using the actual filled quantity and coins.
- The character name, offer side, item, offer slot, limit price, and offer state.
- When explicitly enabled, completed player trades with the other player's display name and
  the exact items and coins given and received.

It does not request credentials, automate game actions, click interfaces, alter offers, or send
trade data over the network.

## Local connection

The plugin writes atomic JSON events under `.runelite/osrs-toolkit/events`. OSRS Toolkit imports
them into its local journal, acknowledges the event, and removes the queue file. Offer snapshots
are retained locally so restarting RuneLite does not turn an existing offer into a duplicate fill.

The plugin can keep queuing events while the desktop app is closed. Trades made through mobile,
the official client, or RuneLite while this plugin is disabled cannot be reconstructed later.

## Development

This project follows RuneLite's standard Plugin Hub layout and targets Java 11 bytecode.

```text
gradlew.bat clean test
gradlew.bat run
```

The second command opens a RuneLite development client; it never automates login or gameplay.

### Development login with a Jagex Account

This temporary setup is required only when testing the plugin from a development client. Normal
Plugin Hub users launch RuneLite through the Jagex Launcher as usual.

1. Confirm the RuneLite launcher is version 2.6.3 or newer.
2. Open **RuneLite (configure)** from the Windows Start Menu.
3. Add `--insecure-write-credentials` to **Client arguments** and save.
4. Launch RuneLite once through the Jagex Launcher. It writes a temporary
   `.runelite/credentials.properties` session file.
5. Start the development client again.

Never share, copy into this repository, or upload `credentials.properties`. When testing is
finished, delete that file. Use **End sessions** in the account settings on runescape.com if the
temporary session ever needs to be invalidated. See RuneLite's
[Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) guide.

## License

BSD 2-Clause. This permissive license is required for RuneLite Plugin Hub submissions.
