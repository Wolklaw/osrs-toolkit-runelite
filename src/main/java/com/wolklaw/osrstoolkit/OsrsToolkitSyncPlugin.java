package com.wolklaw.osrstoolkit;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "OSRS Toolkit Sync",
	description = "Syncs GE fills, optional player trades, and optional PvM loadout snapshots "
		+ "to the OSRS Toolkit desktop app",
	tags = {"trade", "grand exchange", "journal", "tracker", "flipping", "pvm", "bank"}
)
public class OsrsToolkitSyncPlugin extends Plugin
{
	private static final String ACCEPTED_TRADE = "Accepted trade.";
	private static final String DECLINED_TRADE = "Other player declined trade.";
	// RuneScape exposes the other player's offer as the trade container with this API flag.
	private static final int OTHER_PLAYER_CONTAINER = InventoryID.TRADEOFFER | 0x8000;
	// Bank contents can change many times in a row while shuffling items around; only snapshot
	// at most this often so a busy banking session doesn't flood the local event queue.
	private static final long LOADOUT_SNAPSHOT_MIN_INTERVAL_MS = 3_000;
	// The desktop app refuses a loadout list longer than this, and refusing one list throws away
	// the whole snapshot — gear, inventory and skills with it. A full bank is well past the limit,
	// so trim here rather than let the desktop app silently drop everything.
	private static final int MAX_SYNCED_CONTAINER_ITEMS = 1_200;
	// The item the "Set up offer" box is showing. RuneLite's own Grand Exchange plugin reads this
	// same var to know what its "Buy limit: …" line is about, which is that box and nothing else.
	// The older API called it CURRENT_GE_ITEM; gameval names it after the trading post search.
	private static final int GE_SETUP_ITEM_VARP = VarPlayerID.TRADINGPOST_SEARCH;
	// Which side the box is set up for. Zero is a buy: RuneLite's own item-stats plugin puts
	// equipment stats on the offer box only while this reads zero, and stats are what you weigh
	// before buying something, not before selling something already yours.
	private static final int GE_SETUP_SIDE_VARBIT = VarbitID.GE_NEWOFFER_TYPE;
	private static final int GE_SETUP_SIDE_BUY = 0;
	// The box's examine line is drawn by one of two scripts named after the side being offered,
	// which is the only place the interface says buy or sell in so many words. Preferred over the
	// var above where it has fired for the item in hand; RuneLite's Grand Exchange plugin tells
	// the two sides apart this same way.
	private static final String BUY_EXAMINE_CALLBACK = "geBuyExamineText";
	private static final String SELL_EXAMINE_CALLBACK = "geSellExamineText";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Gson gson;

	@Inject
	private OsrsToolkitSyncConfig config;

	private final Map<String, Map<Integer, OfferSnapshot>> accountOffers = new HashMap<>();
	private final Set<String> loadedAccounts = new HashSet<>();
	private ScheduledExecutorService ioExecutor;
	private ScheduledFuture<?> heartbeatFuture;
	private ScheduledFuture<?> pruneFuture;
	private LocalSyncStore store;
	private PendingPlayerTrade pendingPlayerTrade;
	private boolean inTradeConfirmation;
	private volatile String accountName = "Not logged in";
	private volatile String accountHash = "unknown";
	private volatile boolean playerTradeTracking;
	private volatile long lastLoadoutSnapshotMillis;
	// Client tick the world last finished loading on, or -1 before it ever has. Read on the
	// client thread only, where the Grand Exchange events it qualifies are also delivered.
	private int lastLoadedTick = -1;
	// The "Set up offer" box as it last read, or null when it is not open on an item. Written
	// from the client thread and read by the heartbeat on the IO thread, which re-stamps it so
	// the desktop app can tell a box still open from one a dead client left behind.
	private volatile OfferScreen offerScreen;
	// Which side the box last said it was, and the item it said it about. Kept as a pair because
	// the answer only arrives when the game redraws the examine line: holding the item alongside
	// it is what stops a side latched for the last item being reported for the next one.
	private String setupSide;
	private int setupSideItemId;

	@Override
	protected void startUp()
	{
		playerTradeTracking = config.trackPlayerTrades();
		store = new LocalSyncStore(gson, RuneLite.RUNELITE_DIR.toPath());
		ioExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "osrs-toolkit-sync");
			thread.setDaemon(true);
			return thread;
		});
		submitIo("initialize local bridge", store::initialize);
		// A client can stay open for weeks, so housekeeping has to repeat rather than only run
		// at start-up: the queue keeps growing the whole time the desktop app stays closed.
		pruneFuture = ioExecutor.scheduleWithFixedDelay(
			() -> runIo("prune stale sync files", this::pruneLocalFiles),
			0,
			6,
			TimeUnit.HOURS
		);
		heartbeatFuture = ioExecutor.scheduleWithFixedDelay(
			() -> runIo(
				"update connection status",
				() ->
				{
					store.writeHeartbeat(accountHash, accountName, playerTradeTracking);
					OfferScreen screen = offerScreen;
					if (screen != null)
					{
						// Re-stamped rather than only written when it changes: the desktop app times
						// this file out so a client that died with the box open cannot leave a
						// highlight behind, which means a box left open for minutes has to keep
						// saying it still is.
						store.writeOfferScreen(accountHash, screen);
					}
				}
			),
			0,
			10,
			TimeUnit.SECONDS
		);
		updateAccount();
		log.debug("OSRS Toolkit local sync started");
	}

	@Override
	protected void shutDown()
	{
		if (heartbeatFuture != null)
		{
			heartbeatFuture.cancel(true);
			heartbeatFuture = null;
		}
		if (pruneFuture != null)
		{
			pruneFuture.cancel(true);
			pruneFuture = null;
		}
		// Written straight through rather than queued: the desktop app reads this file as "the
		// player is looking at this offer right now", and the executor that would carry a queued
		// write is shut down on the next line.
		String closingAccountHash = accountHash;
		runIo("clear the open offer screen", () -> store.writeOfferScreen(closingAccountHash, null));
		offerScreen = null;
		setupSide = null;
		setupSideItemId = 0;
		if (ioExecutor != null)
		{
			ioExecutor.shutdownNow();
			ioExecutor = null;
		}
		pendingPlayerTrade = null;
		inTradeConfirmation = false;
		log.debug("OSRS Toolkit local sync stopped");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// The game re-sends every Grand Exchange offer as soon as the world finishes
			// loading — on login, on a hop, and on a region change. Those arrive on the tick
			// the load completes, which is the only thing that tells them apart from an offer
			// the player just placed: this plugin's memory of the slots is lost with the
			// client, so a restart makes a running offer look brand new.
			lastLoadedTick = client.getTickCount();
			updateAccount();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// Cleared before the hash it is filed under is forgotten, or the file would be left
			// behind under the old account with nothing left that knows how to find it.
			clearOfferScreen();
			// Switching characters goes through the login screen, and the Grand Exchange offers
			// for the next account arrive before its name does. Without forgetting the old
			// account here, those offers would be filed under it and diffed against its saved
			// slots. Hopping deliberately does not reset: it cannot change who is logged in.
			forgetAccount();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		updateAccount();
		if (pendingPlayerTrade != null)
		{
			captureCounterparty();
		}
		refreshOfferScreen();
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		if (!config.trackGrandExchange())
		{
			return;
		}
		updateAccount();
		if ("unknown".equals(accountHash))
		{
			return;
		}
		GrandExchangeOffer offer = event.getOffer();
		String itemName = "Unknown item";
		if (offer.getItemId() > 0)
		{
			ItemComposition composition = client.getItemDefinition(offer.getItemId());
			if (composition != null && composition.getName() != null)
			{
				itemName = composition.getName();
			}
		}
		OfferSnapshot snapshot = OfferSnapshot.from(event.getSlot(), offer, itemName);
		String eventAccountHash = accountHash;
		String eventAccountName = accountName;
		boolean restored = client.getTickCount() - lastLoadedTick <= 1;
		submitIo(
			"record Grand Exchange change",
			() -> processGrandExchangeOffer(eventAccountHash, eventAccountName, snapshot, restored)
		);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (!config.trackPlayerTrades())
		{
			return;
		}
		if (event.getGroupId() == InterfaceID.TRADEMAIN)
		{
			pendingPlayerTrade = new PendingPlayerTrade();
			inTradeConfirmation = false;
			captureCounterparty();
		}
		else if (event.getGroupId() == InterfaceID.TRADECONFIRM && pendingPlayerTrade != null)
		{
			inTradeConfirmation = true;
			captureCounterparty();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.GE_OFFERS)
		{
			// Answered here rather than left to the next tick: walking away from the Grand
			// Exchange is the moment the desktop app's highlight stops meaning anything, and a
			// tick of it pointing at a row nobody is standing in front of is a tick too many.
			clearOfferScreen();
			return;
		}
		if (event.getGroupId() == InterfaceID.TRADEMAIN && pendingPlayerTrade != null)
		{
			clientThread.invokeLater(() ->
			{
				if (!inTradeConfirmation)
				{
					pendingPlayerTrade = null;
				}
			});
		}
		else if (event.getGroupId() == InterfaceID.TRADECONFIRM)
		{
			clientThread.invokeLater(() ->
			{
				pendingPlayerTrade = null;
				inTradeConfirmation = false;
			});
		}
	}

	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		boolean buy = BUY_EXAMINE_CALLBACK.equals(event.getEventName());
		if (!buy && !SELL_EXAMINE_CALLBACK.equals(event.getEventName()))
		{
			return;
		}
		// Latched together with the item it was said about: the game only runs these scripts
		// when it redraws the examine line, so without the item alongside it the side chosen
		// for the last thing looked at would go on being reported for the next one.
		setupSide = buy ? "buy" : "sell";
		setupSideItemId = client.getVarpValue(GE_SETUP_ITEM_VARP);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK)
		{
			maybeSyncLoadout();
		}
		if (!config.trackPlayerTrades() || pendingPlayerTrade == null || inTradeConfirmation)
		{
			// Once the confirmation screen is up, what it shows is what both sides agreed to.
			// Any container update after that is the server emptying the trade window as the
			// trade completes, and taking it would blank the contents moments before the
			// "Accepted trade." message asks for them.
			return;
		}
		if (event.getContainerId() == InventoryID.TRADEOFFER)
		{
			pendingPlayerTrade.update(true, event.getItemContainer().getItems());
		}
		else if (event.getContainerId() == OTHER_PLAYER_CONTAINER)
		{
			pendingPlayerTrade.update(false, event.getItemContainer().getItems());
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.TRADE || pendingPlayerTrade == null)
		{
			return;
		}
		if (DECLINED_TRADE.equals(event.getMessage()))
		{
			pendingPlayerTrade = null;
			inTradeConfirmation = false;
			return;
		}
		if (!ACCEPTED_TRADE.equals(event.getMessage()) || !config.trackPlayerTrades())
		{
			return;
		}
		PendingPlayerTrade completedTrade = pendingPlayerTrade;
		pendingPlayerTrade = null;
		inTradeConfirmation = false;
		if (completedTrade.isEmpty())
		{
			return;
		}
		updateAccount();
		SyncEvent syncEvent = SyncEvent.playerTrade(
			accountHash,
			accountName,
			completedTrade.counterparty,
			toSyncItems(completedTrade.given),
			toSyncItems(completedTrade.received)
		);
		submitIo("record player trade", () -> store.writeEvent(syncEvent));
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!OsrsToolkitSyncConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		playerTradeTracking = config.trackPlayerTrades();
		if (!playerTradeTracking)
		{
			pendingPlayerTrade = null;
			inTradeConfirmation = false;
		}
		if (!config.trackGrandExchange())
		{
			clearOfferScreen();
		}
	}

	private void processGrandExchangeOffer(String eventAccountHash, String eventAccountName,
		OfferSnapshot current, boolean restored) throws IOException
	{
		Map<Integer, OfferSnapshot> offers = loadAccountOffers(eventAccountHash);
		OfferSnapshot previous = offers.get(current.slot);
		if (current.isEmpty())
		{
			offers.remove(current.slot);
			store.writeOfferState(eventAccountHash, offers);
			return;
		}
		if (current.continues(previous))
		{
			current.offerId = previous.offerId;
			int quantityDelta = current.quantityFilled - previous.quantityFilled;
			int coinsDelta = current.spentGp - previous.spentGp;
			// continues() already guarantees both deltas are non-negative; requiring coins to
			// be positive keeps us from writing a zero-coin fill the desktop app would only
			// reject, which would cost the quantity in it.
			if (quantityDelta > 0 && coinsDelta > 0)
			{
				store.writeEvent(
					SyncEvent.geFill(
						eventAccountHash,
						eventAccountName,
						current,
						quantityDelta,
						coinsDelta
					)
				);
			}
			if (current.isCancelled())
			{
				// Cancelling ends an offer without a fill of its own, so nothing above reports
				// it. Left unsaid, a position opened for this offer waits on it forever.
				store.writeEvent(
					SyncEvent.geOfferCancelled(eventAccountHash, eventAccountName, current)
				);
			}
		}
		else if (!current.isTerminal())
		{
			// A still-open offer this plugin was not already following. Usually that means the
			// player just placed it, and recording it now — rather than waiting for the first
			// fill — puts it in the Journal the moment they commit to it. It can equally be an
			// offer the game re-sent after a load, one that has been running for hours; the
			// desktop app has to be told which, because the two want opposite treatment and
			// only it knows what is already tracked.
			store.writeEvent(
				SyncEvent.geOfferOpened(eventAccountHash, eventAccountName, current, restored)
			);
		}
		offers.put(current.slot, current);
		store.writeOfferState(eventAccountHash, offers);
	}

	/**
	 * Notice the "Set up offer" box opening, closing, or changing what it is on, and tell the
	 * desktop app when it does.
	 *
	 * Polled on the tick rather than driven by an event, because there is no one event to drive
	 * it from: the box is a panel inside an interface that is already loaded, the var holding
	 * its item keeps the last thing picked long after the interface is gone, and the script that
	 * names the side only runs when the examine line is redrawn. Reading all three together once
	 * a tick is both the simplest way to be right and cheap enough not to matter — and the write
	 * only happens on the ticks where the answer actually moved.
	 */
	private void refreshOfferScreen()
	{
		OfferScreen current = readOfferScreen();
		if (current == null)
		{
			clearOfferScreen();
			return;
		}
		if (current.equals(offerScreen))
		{
			return;
		}
		offerScreen = current;
		String eventAccountHash = accountHash;
		submitIo(
			"record the open offer screen",
			() -> store.writeOfferScreen(eventAccountHash, current)
		);
	}

	/**
	 * Where in the Grand Exchange the player is, or null when they are not in it at all.
	 *
	 * Two answers, because a trade is a session rather than a moment. Standing anywhere in the
	 * interface is worth saying on its own — an offer placed a minute ago is still the thing
	 * being worked on while it fills and while it is collected, and the desktop app can pair
	 * that with the slots it already reads. On top of that, the "Set up offer" box open on a
	 * chosen item is worth saying precisely, because that is the one screen with two empty boxes
	 * waiting to be typed into. An item of zero is the first answer without the second.
	 *
	 * Gated on the panels actually being on screen and not merely existing, because the var
	 * holding the chosen item outlives the interface by a long way: read on its own it would
	 * leave the desktop app pointing at a row the player walked away from an hour ago.
	 */
	private OfferScreen readOfferScreen()
	{
		if (!config.trackGrandExchange())
		{
			return null;
		}
		Widget geInterface = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
		if (geInterface == null || geInterface.isHidden())
		{
			return null;
		}
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (setup == null || setup.isHidden())
		{
			// In the Grand Exchange, but on the slots rather than in a box: watching an offer
			// fill, or collecting one. Which offers those are is on the slots the desktop app
			// already reads, so naming them again here would only be a second chance to disagree.
			return new OfferScreen(0, null, null);
		}
		int itemId = client.getVarpValue(GE_SETUP_ITEM_VARP);
		if (itemId <= 0)
		{
			// The box is up but still on the search, with nothing chosen to point at yet.
			return new OfferScreen(0, null, null);
		}
		ItemComposition composition = client.getItemDefinition(itemId);
		String itemName = composition == null || composition.getName() == null
			? "Unknown item"
			: composition.getName();
		return new OfferScreen(itemId, itemName, setupSide(itemId));
	}

	/**
	 * Which side the box open on {@code itemId} is offering.
	 *
	 * The script that draws the examine line is named after the side outright, so where it has
	 * fired for this item that is the answer. It only runs when the line is redrawn, though, so
	 * a box already open before this plugin started has never produced one — the var is what
	 * answers then.
	 */
	private String setupSide(int itemId)
	{
		if (itemId == setupSideItemId && setupSide != null)
		{
			return setupSide;
		}
		return client.getVarbitValue(GE_SETUP_SIDE_VARBIT) == GE_SETUP_SIDE_BUY ? "buy" : "sell";
	}

	private void clearOfferScreen()
	{
		setupSide = null;
		setupSideItemId = 0;
		if (offerScreen == null)
		{
			// Nothing was ever said, so there is nothing to take back — and this runs on every
			// tick the player spends away from the Grand Exchange, which is nearly all of them.
			return;
		}
		offerScreen = null;
		String eventAccountHash = accountHash;
		submitIo(
			"clear the open offer screen",
			() -> store.writeOfferScreen(eventAccountHash, null)
		);
	}

	private Map<Integer, OfferSnapshot> loadAccountOffers(String eventAccountHash) throws IOException
	{
		if (!loadedAccounts.contains(eventAccountHash))
		{
			accountOffers.put(eventAccountHash, store.readOfferState(eventAccountHash));
			loadedAccounts.add(eventAccountHash);
		}
		return accountOffers.computeIfAbsent(eventAccountHash, ignored -> new HashMap<>());
	}

	private void maybeSyncLoadout()
	{
		if (!config.trackPvmLoadout())
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastLoadoutSnapshotMillis < LOADOUT_SNAPSHOT_MIN_INTERVAL_MS)
		{
			return;
		}
		updateAccount();
		if ("unknown".equals(accountHash))
		{
			return;
		}
		// Only spend the throttle window on a snapshot actually taken, so a bank opened before
		// the player name resolves doesn't silently cost the next few seconds of chances.
		lastLoadoutSnapshotMillis = now;
		List<SyncItem> equipment = containerItems(InventoryID.WORN);
		List<SyncItem> inventory = containerItems(InventoryID.INV);
		List<SyncItem> bank = containerItems(InventoryID.BANK);
		Map<String, Integer> skills = new LinkedHashMap<>();
		for (Skill skill : Skill.values())
		{
			if ("OVERALL".equals(skill.name()))
			{
				continue;
			}
			skills.put(skill.getName(), client.getRealSkillLevel(skill));
		}
		SyncEvent syncEvent = SyncEvent.loadoutSnapshot(
			accountHash, accountName, equipment, inventory, bank, skills
		);
		submitIo("record PvM loadout snapshot", () -> store.writeEvent(syncEvent));
	}

	private List<SyncItem> containerItems(int containerId)
	{
		List<SyncItem> result = new ArrayList<>();
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return result;
		}
		for (Item item : container.getItems())
		{
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			ItemComposition composition = client.getItemDefinition(item.getId());
			String name = composition == null ? "Unknown item" : composition.getName();
			int unitValue = item.getId() == ItemID.COINS
				? 1
				: Math.max(0, itemManager.getItemPrice(item.getId()));
			result.add(new SyncItem(item.getId(), name, item.getQuantity(), unitValue));
		}
		if (result.size() > MAX_SYNCED_CONTAINER_ITEMS)
		{
			// Keep the most valuable stacks: those are what the PvM checklists ask about, and
			// dropping the tail is far better than the desktop app rejecting the whole snapshot.
			result.sort(Comparator.comparingLong(SyncItem::totalValue).reversed());
			return new ArrayList<>(result.subList(0, MAX_SYNCED_CONTAINER_ITEMS));
		}
		return result;
	}

	private void pruneLocalFiles() throws IOException
	{
		store.pruneStaleEvents(Duration.ofDays(30), 20_000);
		store.deleteStaleTemporaryFiles(Duration.ofHours(1));
	}

	private List<SyncItem> toSyncItems(Map<Integer, Integer> items)
	{
		List<SyncItem> result = new ArrayList<>();
		for (Map.Entry<Integer, Integer> item : items.entrySet())
		{
			int itemId = item.getKey();
			ItemComposition composition = client.getItemDefinition(itemId);
			String name = composition == null ? "Unknown item" : composition.getName();
			int unitValue = itemId == ItemID.COINS ? 1 : Math.max(0, itemManager.getItemPrice(itemId));
			result.add(new SyncItem(itemId, name, item.getValue(), unitValue));
		}
		return result;
	}

	private void captureCounterparty()
	{
		if (pendingPlayerTrade == null)
		{
			return;
		}
		Widget widget = inTradeConfirmation
			? client.getWidget(InterfaceID.Tradeconfirm.TRADEOPPONENT)
			: client.getWidget(InterfaceID.Trademain.TITLE);
		if (widget == null || widget.getText() == null)
		{
			return;
		}
		String text = Text.removeTags(widget.getText()).trim();
		int separator = text.indexOf(':');
		if (separator >= 0 && separator + 1 < text.length())
		{
			text = text.substring(separator + 1).trim();
		}
		if (!text.isEmpty())
		{
			pendingPlayerTrade.counterparty = text;
		}
	}

	private void updateAccount()
	{
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || localPlayer.getName() == null || localPlayer.getName().trim().isEmpty())
		{
			return;
		}
		String currentName = localPlayer.getName().trim();
		if (!currentName.equals(accountName))
		{
			accountName = currentName;
			accountHash = hashAccountName(currentName);
		}
	}

	private void forgetAccount()
	{
		accountName = "Not logged in";
		accountHash = "unknown";
	}

	private static String hashAccountName(String name)
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] bytes = digest.digest(
				name.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)
			);
			StringBuilder result = new StringBuilder(bytes.length * 2);
			for (byte value : bytes)
			{
				result.append(String.format("%02x", value & 0xff));
			}
			return result.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private void submitIo(String action, IoAction work)
	{
		ScheduledExecutorService executor = ioExecutor;
		if (executor != null && !executor.isShutdown())
		{
			executor.execute(() -> runIo(action, work));
		}
	}

	private void runIo(String action, IoAction work)
	{
		try
		{
			work.run();
		}
		catch (Exception ex)
		{
			log.debug("Unable to {}", action, ex);
		}
	}

	@Provides
	OsrsToolkitSyncConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsToolkitSyncConfig.class);
	}

	@FunctionalInterface
	private interface IoAction
	{
		void run() throws Exception;
	}
}
