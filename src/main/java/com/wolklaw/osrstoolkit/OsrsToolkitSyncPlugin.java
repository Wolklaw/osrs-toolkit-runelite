package com.wolklaw.osrstoolkit;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.time.Duration;
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
import net.runelite.api.ItemComposition;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
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
	description = "Syncs GE fills and optional player trades to the OSRS Toolkit desktop app",
	tags = {"trade", "grand exchange", "journal", "tracker", "flipping"}
)
public class OsrsToolkitSyncPlugin extends Plugin
{
	private static final String ACCEPTED_TRADE = "Accepted trade.";
	private static final String DECLINED_TRADE = "Other player declined trade.";
	// RuneScape exposes the other player's offer as the trade container with this API flag.
	private static final int OTHER_PLAYER_CONTAINER = InventoryID.TRADEOFFER | 0x8000;

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
	private LocalSyncStore store;
	private PendingPlayerTrade pendingPlayerTrade;
	private boolean inTradeConfirmation;
	private volatile String accountName = "Not logged in";
	private volatile String accountHash = "unknown";
	private volatile boolean playerTradeTracking;

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
		submitIo(
			"prune stale sync events",
			() -> store.pruneStaleEvents(Duration.ofDays(30), 20_000)
		);
		heartbeatFuture = ioExecutor.scheduleWithFixedDelay(
			() -> runIo(
				"update connection status",
				() -> store.writeHeartbeat(accountHash, accountName, playerTradeTracking)
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
			updateAccount();
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
		submitIo(
			"record Grand Exchange change",
			() -> processGrandExchangeOffer(eventAccountHash, eventAccountName, snapshot)
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
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!config.trackPlayerTrades() || pendingPlayerTrade == null)
		{
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
	}

	private void processGrandExchangeOffer(String eventAccountHash, String eventAccountName,
		OfferSnapshot current) throws IOException
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
			if (quantityDelta > 0 && coinsDelta >= 0)
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
		}
		offers.put(current.slot, current);
		store.writeOfferState(eventAccountHash, offers);
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
