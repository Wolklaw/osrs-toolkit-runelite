# OSRS Toolkit Sync

RuneLite companion plugin for [OSRS Toolkit](https://github.com/Wolklaw/OSRS-Toolkit).

It records Grand Exchange fills while RuneLite is running and sends them to the OSRS Toolkit
sync service, so the desktop app's Trade Journal has them — even when the app was closed at the
time of the trade. Player-to-player trade tracking, PvM gear/bank sync, NPC loot, and deaths are
all available as optional, off-by-default settings.

**The plugin sends nothing until you turn it on.** Sync is off by default, and even switched on
it does nothing until you give it a service address and a pairing token.

## What it records

- Partial and completed Grand Exchange fills, using the actual filled quantity and coins, plus
  the offer's full total quantity so the desktop app can size a new Journal position to the
  whole order instead of just one partial fill.
- The moment a new offer is placed, before anything has filled — so the desktop app's Journal
  can start tracking it right away instead of waiting for the first fill.
- The moment an offer is cancelled, which ends it without a fill of its own. The desktop app
  drops a position whose offer never filled, and resizes a part-filled one down to what actually
  bought, so nothing is left waiting on an offer that no longer exists.
- The character name, offer side, item, offer slot, limit price, and offer state.
- Where in the Grand Exchange you are standing, so the desktop app can point at the Journal row
  the trade in front of you needs.
- When explicitly enabled, completed player trades with the other player's display name and the
  exact items and coins given and received.
- When explicitly enabled, a snapshot of your equipped gear, inventory, bank contents, and skill
  levels each time you open your bank — used by the desktop app's PvM Readiness page. Snapshots
  are throttled, skipped entirely when nothing has changed since the last one, and a very large
  bank is trimmed to its most valuable 1,200 stacks.
- When explicitly enabled, the items an NPC drops when you kill it — the name and value of every
  stack, so the desktop app can total up what a PvM trip actually paid.
- When explicitly enabled, what you had equipped and in your inventory the moment you died, plus
  whether you were skulled. Not a computed loss — OSRS decides what survives a death by rules
  this plugin does not simulate (skull state, Protect Item, wilderness) — just what was on you
  at the time, for the desktop app to weigh against what a trip paid.

It does not request credentials, automate game actions, click interfaces, or alter offers.

## Where it sends

To the address you put in the settings, and nowhere else. The service is
[osrs-toolkit-sync-server](https://github.com/Wolklaw/osrs-toolkit-sync-server) — open source,
so what receives your data can be read rather than taken on trust. You can run it yourself and
point the plugin at your own copy.

The service holds your events only until the desktop app collects them, then deletes them. There
is no account, no password and no email; a pairing token is the only identifier, and the service
stores only a hash of it.

The endpoint contract is documented at
[docs/sync-api.md](https://github.com/Wolklaw/OSRS-Toolkit/blob/fresh-main/docs/sync-api.md).

## Setting it up

1. Get a pairing token from your sync service, or from the desktop app's Support tab.
2. In RuneLite, open this plugin's settings.
3. Fill in **Service address** and **Pairing token**, then switch on **Send to OSRS Toolkit
   Sync**.
4. Enter the same token in the desktop app.

## If the service is unreachable

Events queue up on disk under `.runelite/osrs-toolkit/events` and are sent when it comes back.
Nothing is deleted from the queue until the service confirms it has it, so a fill recorded while
your connection was down still reaches the Journal afterwards. The queue is pruned after 30 days
or 20,000 events as a safety net.

That queue is this plugin's own outbox — nothing else reads it.
