package com.wolklaw.osrstoolkit;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.SkullIcon;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.AnimationID;
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
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;

@Slf4j
@PluginDescriptor(
	name = "OSRS Toolkit Sync",
	description = "Sends GE fills, and optionally player trades, PvM loadout snapshots, NPC "
		+ "loot and deaths, to the OSRS Toolkit sync service for the desktop app to import",
	tags = {"trade", "grand exchange", "journal", "tracker", "flipping", "pvm", "bank", "loot", "death"}
)
public class OsrsToolkitSyncPlugin extends Plugin
{
	/** Jagex colour tags for the two answers a connection test can give. */
	private static final String GOOD = "<col=40c057>";
	private static final String BAD = "<col=e2685f>";

	private static final String ACCEPTED_TRADE = "Accepted trade.";
	private static final String DECLINED_TRADE = "Other player declined trade.";
	// gameval has a constant for the trade container but none for the flagged form the other
	// player's side uses, so it is combined here. Legacy InventoryID spelled it TRADEOTHER.
	private static final int OTHER_PLAYER_CONTAINER = InventoryID.TRADEOFFER | 0x8000;
	// Bank contents can change many times in a row while shuffling items around; only snapshot
	// at most this often so a busy banking session doesn't flood the local event queue.
	private static final long LOADOUT_SNAPSHOT_MIN_INTERVAL_MS = 3_000;
	// The desktop app refuses a longer list, and one refusal throws away the whole snapshot —
	// gear, inventory and skills with it. A full bank is well past this, so trim here.
	private static final int MAX_SYNCED_CONTAINER_ITEMS = 1_200;
	// The item the "Set up offer" box is showing. Named after the trading post search in
	// gameval; the older API called it CURRENT_GE_ITEM.
	private static final int GE_SETUP_ITEM_VARP = VarPlayerID.TRADINGPOST_SEARCH;
	// Which side the box is set up for. Zero is a buy.
	private static final int GE_SETUP_SIDE_VARBIT = VarbitID.GE_NEWOFFER_TYPE;
	private static final int GE_SETUP_SIDE_BUY = 0;
	// Often enough that a player sitting still does not read as disconnected.
	private static final long HEARTBEAT_SECONDS = 60;
	private static final long FLUSH_SECONDS = 5;
	// The byte budget is the one that binds: a loadout snapshot runs to six figures, and a
	// hundred of them would be refused outright for exceeding the service's body limit.
	private static final int MAX_EVENTS_PER_FLUSH = 100;
	private static final int MAX_FLUSH_BYTES = 512 * 1024;
	// The examine line is drawn by one of two scripts named after the side being offered — the
	// only place the interface says buy or sell outright. RuneLite's own Grand Exchange plugin
	// tells the two apart this same way.
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

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientToolbar clientToolbar;

	// Written on the Swing thread in startUp/shutDown, read from the client thread whenever a
	// connection outcome needs to reach the sidebar -- the same cross-thread shape as syncClient.
	private volatile OsrsToolkitSyncPanel panel;
	private NavigationButton navButton;

	private final Map<String, Map<Integer, OfferSnapshot>> accountOffers = new HashMap<>();
	private final Set<String> loadedAccounts = new HashSet<>();
	// Written on the Swing thread at start and stop, read from the client thread every tick.
	private volatile ScheduledExecutorService ioExecutor;
	private ScheduledFuture<?> heartbeatFuture;
	private ScheduledFuture<?> pruneFuture;
	private ScheduledFuture<?> flushFuture;
	private volatile LocalSyncStore store;
	private volatile SyncClient syncClient;
	// Dropped to one after a batch the service refused, so the payload it will not take is
	// isolated and discarded on its own rather than taking a hundred good events with it.
	private volatile int flushBatchLimit = MAX_EVENTS_PER_FLUSH;
	// True while a flush is in the air, so the timer cannot start a second one that would send
	// the same events again.
	private final AtomicBoolean flushInFlight = new AtomicBoolean();
	// What the last loadout snapshot looked like, so an unchanged bank is not sent again. Banks
	// barely move between openings, and a snapshot is the largest thing this plugin sends.
	private volatile Integer lastLoadoutFingerprint;
	private PendingPlayerTrade pendingPlayerTrade;
	private boolean inTradeConfirmation;
	// Read on the client thread only, alongside the animation change it is comparing against.
	// Marks the death animation's rising edge so one death is reported once rather than on every
	// tick the animation continues to play.
	private boolean lastAnimationWasDeath;
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
		ioExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "osrs-toolkit-sync");
			thread.setDaemon(true);
			return thread;
		});
		store = new LocalSyncStore(gson, RuneLite.RUNELITE_DIR.toPath(), ioExecutor);
		syncClient = new SyncClient(okHttpClient);
		panel = new OsrsToolkitSyncPanel(
			config.pairingToken(),
			token -> {
				configManager.setConfiguration(
					OsrsToolkitSyncConfig.GROUP, OsrsToolkitSyncConfig.PAIRING_TOKEN_KEY, token
				);
				// ConfigManager does not post ConfigChanged when the new value equals the old
				// one, so onConfigChanged's own trigger never fires for this exact case -- and
				// resubmitting the same token after a failed attempt is the ordinary way
				// someone retries. Called explicitly so Connect always does something.
				testConnectionAndReport();
			}
		);
		navButton = NavigationButton.builder()
			.tooltip("OSRS Toolkit Sync")
			.icon(ImageUtil.loadImageResource(getClass(), "icon.png"))
			.priority(5)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		applyConnectionSettings();
		if (!config.syncEnabled())
		{
			// Already what the panel's own constructor defaults to; set again in case this is
			// a second startUp() in the same session and the last thing shown was something else.
			panel.setNeutral("Not sending. Switch on \"Send to OSRS Toolkit Sync\" in this plugin's settings.");
		}
		else
		{
			// The constructor's default is "Not sending", which is wrong the moment sync was
			// already on from a previous session -- the heartbeat scheduled just below will
			// correct it almost immediately, since it runs with no initial delay, but "almost
			// immediately" is not "correct from the first frame", and nothing enforces that
			// delay stays zero.
			panel.setChecking("Checking the token…");
		}
		submitIo("initialize the outbox", store::initialize);
		// Emptying the outbox is the only thing that actually reaches the service. Everything
		// else this plugin records simply lands in it.
		flushFuture = ioExecutor.scheduleWithFixedDelay(
			() -> runIo("send queued events", this::flushOutbox),
			FLUSH_SECONDS,
			FLUSH_SECONDS,
			TimeUnit.SECONDS
		);
		// A client can stay open for weeks, so housekeeping has to repeat rather than only run
		// at start-up: the queue keeps growing the whole time the desktop app stays closed.
		pruneFuture = ioExecutor.scheduleWithFixedDelay(
			() -> runIo("prune stale sync files", this::pruneLocalFiles),
			0,
			6,
			TimeUnit.HOURS
		);
		heartbeatFuture = ioExecutor.scheduleWithFixedDelay(
			() -> runIo("say the plugin is still connected", this::sendHeartbeat),
			0,
			HEARTBEAT_SECONDS,
			TimeUnit.SECONDS
		);
		// Plugins are started on the Swing event thread, and who is logged in is the client's to
		// answer, so the first reading has to be asked for on the thread that owns it.
		clientThread.invokeLater(this::updateAccount);
		log.debug("OSRS Toolkit sync started");
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		panel = null;
		// Cancelled without interrupting. A task caught mid-flight here is a file being replaced
		// under a reader in the desktop app; letting it finish costs milliseconds on a background
		// thread, while interrupting it can leave a scratch file behind for the sweep to find.
		if (heartbeatFuture != null)
		{
			heartbeatFuture.cancel(false);
			heartbeatFuture = null;
		}
		if (pruneFuture != null)
		{
			pruneFuture.cancel(false);
			pruneFuture = null;
		}
		if (flushFuture != null)
		{
			flushFuture.cancel(false);
			flushFuture = null;
		}
		// Told rather than left to time out. The request itself is handed to OkHttp's own pool,
		// so nothing here waits on the network — plugins stop on the Swing event thread and that
		// thread draws the client's own settings panel.
		sendOfferScreen(accountHash, null);
		offerScreen = null;
		setupSide = null;
		setupSideItemId = 0;
		if (ioExecutor != null)
		{
			// shutdown() rather than shutdownNow(): the latter interrupts whatever is running,
			// and the queued clear above is the last thing this plugin has to say.
			ioExecutor.shutdown();
			ioExecutor = null;
		}
		pendingPlayerTrade = null;
		inTradeConfirmation = false;
		syncClient = null;
		log.debug("OSRS Toolkit sync stopped");
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
	public void onNpcLootReceived(NpcLootReceived event)
	{
		if (!config.trackLoot())
		{
			return;
		}
		updateAccount();
		if ("unknown".equals(accountHash))
		{
			return;
		}
		List<SyncItem> items = toLootItems(event.getItems());
		if (items.isEmpty())
		{
			return;
		}
		NPC npc = event.getNpc();
		String npcName = npc.getName() == null ? "Unknown NPC" : npc.getName();
		String eventAccountHash = accountHash;
		String eventAccountName = accountName;
		submitIo(
			"record NPC loot",
			() -> store.writeEvent(SyncEvent.npcLoot(eventAccountHash, eventAccountName, npcName, items))
		);
	}

	/**
	 * Notice the local player's death animation starting, and record what they were carrying.
	 *
	 * There is no death event in the client API this plugin builds against, so the animation is
	 * the only signal — {@code AnimationChanged} fires for any actor whose animation changes, and
	 * the death animation itself is a normal {@code Actor} animation like any other. Gated on the
	 * rising edge so the multi-tick death animation is reported once, not on every tick it plays.
	 */
	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (!config.trackDeaths())
		{
			return;
		}
		Player localPlayer = client.getLocalPlayer();
		if (localPlayer == null || event.getActor() != localPlayer)
		{
			return;
		}
		boolean isDeathAnimation = localPlayer.getAnimation() == AnimationID.HUMAN_DEATH;
		if (isDeathAnimation && !lastAnimationWasDeath)
		{
			recordDeath();
		}
		lastAnimationWasDeath = isDeathAnimation;
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!OsrsToolkitSyncConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		playerTradeTracking = config.trackPlayerTrades();
		applyConnectionSettings();
		// Only when the address, token, or the toggle itself just changed — not on every field
		// in this settings group, which would mean toggling "track player trades" alone also
		// re-testing a connection nothing about it touched. This is what answers "does this
		// work" the moment someone finishes typing, rather than on whatever the next scheduled
		// heartbeat happens to be, up to a minute later.
		if (
			"syncEnabled".equals(event.getKey())
			|| OsrsToolkitSyncConfig.PAIRING_TOKEN_KEY.equals(event.getKey())
		)
		{
			testConnectionAndReport();
		}
		// Config changes arrive on whichever thread made them, which for the settings panel is
		// the Swing event thread. Everything below belongs to the client thread — the trade in
		// progress and the offer box being watched are both written there on every tick — so it
		// is handed over rather than reached into from here.
		clientThread.invokeLater(() ->
		{
			if (!playerTradeTracking)
			{
				pendingPlayerTrade = null;
				inTradeConfirmation = false;
			}
			if (!config.trackGrandExchange())
			{
				clearOfferScreen();
			}
		});
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
			sendOfferState(eventAccountHash, offers);
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
		sendOfferState(eventAccountHash, offers);
	}

	/**
	 * Notice the "Set up offer" box opening, closing, or changing what it is on, and tell the
	 * desktop app when it does.
	 *
	 * Polled on the tick because no single event covers it: the box is a panel inside an already
	 * loaded interface, the var holding its item outlives that interface, and the script naming
	 * the side only runs on a redraw. The write happens only on ticks where the answer moved.
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
		sendOfferScreen(eventAccountHash, current);
	}

	/**
	 * Where in the Grand Exchange the player is, or null when they are not in it at all.
	 *
	 * Two answers: standing anywhere in the interface, and the "Set up offer" box open on a
	 * chosen item. An item of zero is the first without the second.
	 *
	 * Gated on the panels being on screen rather than merely existing — the var holding the
	 * chosen item outlives the interface, and would leave the desktop app pointing at a row the
	 * player walked away from an hour ago.
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
		sendOfferScreen(eventAccountHash, null);
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
		// A bank barely moves between openings, and this is by far the largest thing the plugin
		// sends — six figures of item names where everything else is a few hundred bytes. Sending
		// one that says exactly what the last one said costs the player bandwidth to tell the
		// desktop app something it already knows.
		int fingerprint = loadoutFingerprint(equipment, inventory, bank, skills);
		Integer previous = lastLoadoutFingerprint;
		if (previous != null && previous == fingerprint)
		{
			return;
		}
		lastLoadoutFingerprint = fingerprint;
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
			String name = itemNameOf(composition);
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

	private List<SyncItem> toLootItems(Collection<ItemStack> stacks)
	{
		List<SyncItem> result = new ArrayList<>();
		for (ItemStack stack : stacks)
		{
			if (stack.getId() <= 0 || stack.getQuantity() <= 0)
			{
				continue;
			}
			ItemComposition composition = client.getItemDefinition(stack.getId());
			String name = itemNameOf(composition);
			int unitValue = stack.getId() == ItemID.COINS
				? 1
				: Math.max(0, itemManager.getItemPrice(stack.getId()));
			result.add(new SyncItem(stack.getId(), name, stack.getQuantity(), unitValue));
		}
		return result;
	}

	/**
	 * Record what the character had equipped and carried at the moment they died.
	 *
	 * Not what was lost — which tradeable items survive depends on skull state, Protect Item, and
	 * whether death happened in the wilderness, rules this plugin does not simulate. The skulled
	 * flag is sent alongside so the desktop app has at least that much of the picture without
	 * having to guess it from the items themselves.
	 */
	private void recordDeath()
	{
		updateAccount();
		if ("unknown".equals(accountHash))
		{
			return;
		}
		List<SyncItem> equipment = containerItems(InventoryID.WORN);
		List<SyncItem> inventory = containerItems(InventoryID.INV);
		Player localPlayer = client.getLocalPlayer();
		boolean skulled = localPlayer != null && localPlayer.getSkullIcon() != SkullIcon.NONE;
		String eventAccountHash = accountHash;
		String eventAccountName = accountName;
		submitIo(
			"record death",
			() -> store.writeEvent(
				SyncEvent.playerDeath(eventAccountHash, eventAccountName, skulled, equipment, inventory)
			)
		);
	}

	private void pruneLocalFiles() throws IOException
	{
		store.pruneStaleEvents(Duration.ofDays(30), 20_000);
		store.deleteStaleTemporaryFiles(Duration.ofHours(1));
	}

	/**
	 * An item's name, never null.
	 *
	 * A definition can exist and still have no name. The Grand Exchange path already checked
	 * for both; the three callers of this did not, so a nameless definition reached the journal
	 * as a row with no name at all rather than one saying it could not be identified.
	 */
	private static String itemNameOf(ItemComposition composition)
	{
		if (composition == null || composition.getName() == null)
		{
			return "Unknown item";
		}
		return composition.getName();
	}

	private List<SyncItem> toSyncItems(Map<Integer, Integer> items)
	{
		List<SyncItem> result = new ArrayList<>();
		for (Map.Entry<Integer, Integer> item : items.entrySet())
		{
			int itemId = item.getKey();
			ItemComposition composition = client.getItemDefinition(itemId);
			String name = itemNameOf(composition);
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
		// A fresh login always starts outside the death animation, and the character it belonged
		// to may not even be the one now controlled by this client.
		lastAnimationWasDeath = false;
	}

	/**
	 * A stable filename for an account's state, derived from the public display name.
	 *
	 * Hashed because a display name can hold characters no filesystem wants, and because the
	 * character being played has no business sitting in a directory listing. Nothing here is
	 * secret — the digest is for a filesystem-safe token, not to protect anything.
	 */
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

	/**
	 * What the loadout holds, as one number.
	 *
	 * Item ids and counts only: prices drift on their own, and resending a hundred kilobytes
	 * over a gp is not worth it. Computed by hand rather than by serialising and hashing, since
	 * building the JSON twice is the cost this exists to avoid.
	 */
	private static int loadoutFingerprint(List<SyncItem> equipment, List<SyncItem> inventory,
		List<SyncItem> bank, Map<String, Integer> skills)
	{
		int result = 17;
		for (List<SyncItem> items : List.of(equipment, inventory, bank))
		{
			result = result * 31 + items.size();
			for (SyncItem item : items)
			{
				result = result * 31 + item.item_id;
				result = result * 31 + item.quantity;
			}
		}
		for (Map.Entry<String, Integer> skill : skills.entrySet())
		{
			result = result * 31 + skill.getKey().hashCode();
			result = result * 31 + skill.getValue();
		}
		return result;
	}

	/** Point the client at whatever the settings now say, or at nothing while sync is off. */
	private void applyConnectionSettings()
	{
		SyncClient client = syncClient;
		if (client == null)
		{
			return;
		}
		if (!config.syncEnabled())
		{
			client.configure("", "");
			return;
		}
		client.configure(SyncClient.SERVICE_URL, config.pairingToken());
	}

	/**
	 * Empty the outbox.
	 *
	 * One batch in the air at a time: the timer is short enough that a slow request would
	 * otherwise overlap the next pass, and both would read the same files and send them twice.
	 * The service would discard the duplicates, but sending a bank snapshot twice over someone's
	 * home connection to be told it was already there is worth avoiding.
	 */
	private void flushOutbox() throws IOException
	{
		SyncClient client = syncClient;
		LocalSyncStore outbox = store;
		if (client == null || outbox == null || !client.isConfigured())
		{
			return;
		}
		if (!flushInFlight.compareAndSet(false, true))
		{
			return;
		}
		boolean handedOff = false;
		try
		{
			List<LocalSyncStore.PendingEvent> pending = outbox.readPendingEvents(flushBatchLimit);
			if (pending.isEmpty())
			{
				return;
			}
			StringBuilder body = new StringBuilder("{\"events\":[");
			List<Path> sending = new ArrayList<>();
			int bytes = 0;
			for (LocalSyncStore.PendingEvent event : pending)
			{
				// The byte budget is what actually binds. One loadout snapshot is most of it, so
				// a batch is often a single event — but never zero, or an event larger than the
				// budget would never leave the queue at all.
				if (!sending.isEmpty() && bytes + event.json.length() > MAX_FLUSH_BYTES)
				{
					break;
				}
				if (!sending.isEmpty())
				{
					body.append(',');
				}
				body.append(event.json);
				bytes += event.json.length();
				sending.add(event.path);
			}
			body.append("]}");
			int batchSize = sending.size();
			client.send(
				"v1/events",
				body.toString(),
				outcome -> onFlushed(outcome, sending, batchSize)
			);
			handedOff = true;
		}
		finally
		{
			if (!handedOff)
			{
				flushInFlight.set(false);
			}
		}
	}

	/**
	 * What to do with the events a flush just carried. Runs on an OkHttp thread, so anything
	 * touching the outbox is handed back to the thread that owns it.
	 */
	private void onFlushed(SyncClient.Outcome outcome, List<Path> sent, int batchSize)
	{
		try
		{
			if (outcome == SyncClient.Outcome.DELIVERED)
			{
				flushBatchLimit = MAX_EVENTS_PER_FLUSH;
				submitIo("clear sent events", () -> deleteQueued(sent));
				return;
			}
			if (outcome != SyncClient.Outcome.REFUSED)
			{
				// Offline, rate limited, or a token that needs fixing. All of them mean the
				// events are still worth having, so nothing is deleted.
				return;
			}
			if (batchSize == 1)
			{
				// One event the service will never accept. Kept any longer it blocks everything
				// queued behind it, so it goes and the batch size returns to normal.
				log.debug("Dropping an event the sync service refused");
				submitIo("drop a refused event", () -> deleteQueued(sent));
				flushBatchLimit = MAX_EVENTS_PER_FLUSH;
				return;
			}
			// Something in the batch is unacceptable, but not necessarily all of it. Going one at
			// a time from here means only the offending event is lost.
			flushBatchLimit = 1;
		}
		finally
		{
			flushInFlight.set(false);
		}
	}

	private void deleteQueued(List<Path> paths)
	{
		LocalSyncStore outbox = store;
		if (outbox == null)
		{
			return;
		}
		for (Path path : paths)
		{
			outbox.deleteEvent(path);
		}
	}

	private void sendHeartbeat()
	{
		SyncClient client = syncClient;
		if (client == null || !client.isConfigured())
		{
			return;
		}
		// The panel follows the routine heartbeat as well as a settings change, or it would go
		// on claiming a live connection for as long as nobody touched a setting — the service
		// going down mid-session being exactly when it most needs to stop saying that. Silent:
		// the chatbox is for an answer somebody just asked for.
		client.send(
			"v1/heartbeat",
			heartbeatBody(),
			outcome -> clientThread.invokeLater(() -> applyOutcomeToPanel(outcome))
		);
	}

	/**
	 * Send one heartbeat right away and say in the chatbox what came back.
	 *
	 * The routine heartbeat is silent and up to a minute away. This runs the moment the settings
	 * panel gives a reason to ask "does this work now".
	 */
	private void testConnectionAndReport()
	{
		SyncClient syncClientRef = syncClient;
		if (syncClientRef == null)
		{
			return;
		}
		if (!syncClientRef.isConfigured())
		{
			// Nothing to test, but not nothing to say. Both halves of "not set up yet" used to
			// end here in silence when sync was off -- and pasting the token before reaching
			// for the switch is the order the website's own instructions produce, so the very
			// first thing most people do got no answer at all.
			OsrsToolkitSyncPanel panelRef = panel;
			if (!config.syncEnabled())
			{
				if (panelRef != null)
				{
					panelRef.setNeutral(
						"Not sending. Switch on \"Send to OSRS Toolkit Sync\" in this plugin's settings."
					);
				}
			}
			else
			{
				if (panelRef != null)
				{
					panelRef.setNeutral(
						"No pairing token yet. Paste one above, or copy it from your Profile page on runescope.app."
					);
				}
				say(BAD, "no pairing token yet. Copy one from the Profile page on "
					+ "runescope.app and paste it above.");
			}
			return;
		}
		OsrsToolkitSyncPanel panelRef = panel;
		if (panelRef != null)
		{
			panelRef.setChecking("Checking the token…");
		}
		syncClientRef.send(
			"v1/heartbeat",
			heartbeatBody(),
			outcome -> clientThread.invokeLater(() -> reportConnectionResult(outcome))
		);
	}

	private String heartbeatBody()
	{
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("account_hash", accountHash);
		body.put("account_name", accountName);
		body.put("player_trade_tracking", playerTradeTracking);
		return gson.toJson(body);
	}

	/**
	 * Say what a connection test found, once, in the chatbox — CONSOLE rather than a public
	 * chat channel, so it reads the same as any other local client message and nobody but the
	 * player sees it. This is the only place this plugin ever writes to the chatbox unprompted.
	 */
	private void reportConnectionResult(SyncClient.Outcome outcome)
	{
		applyOutcomeToPanel(outcome);
		switch (outcome)
		{
			case DELIVERED:
				say(GOOD, "pairing token accepted. This character is syncing.");
				break;
			case UNAUTHORIZED:
				say(BAD, "that pairing token was not accepted. Copy it again from the Profile "
					+ "page on runescope.app.");
				break;
			case REFUSED:
				say(BAD, "the sync service refused that request.");
				break;
			default:
				say(BAD, "could not reach the sync service. Nothing is lost — what this plugin "
					+ "records is kept and sent when the service comes back.");
				break;
		}
	}

	/**
	 * One line in the chatbox, under this plugin's name and in the colour of the answer.
	 *
	 * The sidebar panel is the persistent answer now; this is the one that reaches someone who
	 * is not looking at it. Pasting a token and clicking away, with the sidebar closed, gets a
	 * game message either way -- and it is still the only channel that exists at all before the
	 * sidebar panel has ever been opened once this session.
	 */
	private void say(String color, String message)
	{
		client.addChatMessage(
			ChatMessageType.CONSOLE,
			"",
			"OSRS Toolkit Sync: " + color + message + "</col>",
			null
		);
	}

	/**
	 * Colour and wording for the sidebar panel, for one request's outcome.
	 *
	 * A no-op once the plugin has stopped: {@code panel} is nulled in {@code shutDown()}, and
	 * an outcome from a request sent just before that can still arrive after.
	 */
	private void applyOutcomeToPanel(SyncClient.Outcome outcome)
	{
		OsrsToolkitSyncPanel panelRef = panel;
		if (panelRef == null)
		{
			return;
		}
		switch (outcome)
		{
			case DELIVERED:
				// Named rather than a bare "connected": on an account with several characters,
				// which one this token is carrying is the useful part.
				panelRef.setConnected(
					"Connected" + ("unknown".equals(accountHash) ? "." : " as " + accountName + ".")
				);
				break;
			case UNAUTHORIZED:
				panelRef.setRejected("Token not accepted. Copy it again from your Profile page on runescope.app.");
				break;
			case REFUSED:
				panelRef.setRejected("The sync service refused that request.");
				break;
			default:
				panelRef.setUnreachable("Could not reach the sync service. Nothing is lost.");
				break;
		}
	}

	/**
	 * Say where in the Grand Exchange the player is, or that they are no longer there.
	 *
	 * Sent straight out rather than queued: it describes this moment, so a retry would only
	 * point the desktop app at a screen the player closed minutes ago.
	 *
	 * A null screen leaves the payload off the request entirely, which the service reads as
	 * "clear it". Absence is still the message, exactly as a deleted file was.
	 */
	private void sendOfferScreen(String eventAccountHash, OfferScreen screen)
	{
		SyncClient client = syncClient;
		if (client == null || !client.isConfigured())
		{
			return;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("account_hash", eventAccountHash);
		if (screen != null)
		{
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("item_id", screen.itemId);
			payload.put("item_name", screen.itemName);
			payload.put("side", screen.side);
			body.put("payload", payload);
		}
		client.replace("v1/state/screen", gson.toJson(body), outcome -> { });
	}

	/** The eight slots as they stand. Current state, so it goes straight out like the screen. */
	private void sendOfferState(String eventAccountHash, Map<Integer, OfferSnapshot> offers)
	{
		SyncClient client = syncClient;
		if (client == null || !client.isConfigured())
		{
			return;
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("account_hash", eventAccountHash);
		body.put("payload", offers);
		client.replace("v1/state/offers", gson.toJson(body), outcome -> { });
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
