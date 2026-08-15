# OSRS Toolkit Sync

RuneLite companion plugin for [OSRS Toolkit](https://github.com/Wolklaw/OSRS-Toolkit).

It records Grand Exchange fills while RuneLite is running and makes them available to the
desktop toolkit's Trade Journal—even when the toolkit was closed at the time of the trade.
Player-to-player trade tracking and PvM gear/bank sync are both available as optional,
off-by-default settings.

## What it records

- Partial and completed Grand Exchange fills, using the actual filled quantity and coins, plus
  the offer's full total quantity so the desktop app can size a new Journal position to the
  whole order instead of just one partial fill.
- The moment a new offer is placed, before anything has filled — so the desktop app's Journal
  can start tracking it right away instead of waiting for the first fill.
- The character name, offer side, item, offer slot, limit price, and offer state.
- When explicitly enabled, completed player trades with the other player's display name and
  the exact items and coins given and received.
- When explicitly enabled, a snapshot of your equipped gear, inventory, bank contents, and
  skill levels each time you open your bank — used by the desktop app's PvM Readiness page.
  Snapshots are throttled to once every few seconds so a busy banking session doesn't flood
  the queue.

It does not request credentials, automate game actions, click interfaces, alter offers, or send
trade data over the network.

## Local connection

The plugin writes atomic JSON events under `.runelite/osrs-toolkit/events`. OSRS Toolkit imports
them into its local journal and removes the queue file once durably committed. Offer snapshots
are retained locally so restarting RuneLite does not turn an existing offer into a duplicate fill.
Event files older than 30 days, or beyond 20,000 queued files, are pruned automatically as a
safety net for when the desktop app stays closed for a long time.

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
