package com.wolklaw.osrstoolkit;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class LocalSyncStore
{
	private static final Type OFFER_STATE_TYPE = new TypeToken<Map<Integer, OfferSnapshot>>() { }.getType();

	private final Gson gson;
	private final Path root;
	private final Path events;
	private final Path state;

	LocalSyncStore(Gson gson, Path runeLiteDirectory)
	{
		this.gson = gson;
		this.root = runeLiteDirectory.resolve("osrs-toolkit");
		this.events = root.resolve("events");
		this.state = root.resolve("state");
	}

	void initialize() throws IOException
	{
		Files.createDirectories(events);
		Files.createDirectories(state);
	}

	void writeEvent(SyncEvent event) throws IOException
	{
		initialize();
		atomicWrite(events.resolve(event.event_id + ".json"), gson.toJson(event));
	}

	Map<Integer, OfferSnapshot> readOfferState(String accountHash) throws IOException
	{
		initialize();
		Path path = statePath(accountHash);
		if (!Files.exists(path))
		{
			return new LinkedHashMap<>();
		}
		String json = Files.readString(path, StandardCharsets.UTF_8);
		Map<Integer, OfferSnapshot> snapshots = gson.fromJson(json, OFFER_STATE_TYPE);
		return snapshots == null ? new LinkedHashMap<>() : new LinkedHashMap<>(snapshots);
	}

	void writeOfferState(String accountHash, Map<Integer, OfferSnapshot> snapshots) throws IOException
	{
		initialize();
		atomicWrite(statePath(accountHash), gson.toJson(snapshots));
	}

	void writeHeartbeat(String accountHash, String accountName, boolean playerTradesEnabled)
		throws IOException
	{
		initialize();
		Map<String, Object> status = new LinkedHashMap<>();
		status.put("schema_version", 1);
		status.put("active", true);
		status.put("updated_at", Instant.now().toString());
		status.put("account_hash", accountHash);
		status.put("account_name", accountName);
		status.put("player_trade_tracking", playerTradesEnabled);
		atomicWrite(root.resolve("status.json"), gson.toJson(status));
	}

	/**
	 * The desktop app deletes each event file itself once imported, so this is only a
	 * safety net for events that pile up while the desktop app is closed for a long time
	 * (or never opened at all).
	 */
	void pruneStaleEvents(Duration maxAge, int maxCount) throws IOException
	{
		initialize();
		List<Path> files = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(events, "*.json"))
		{
			for (Path path : stream)
			{
				files.add(path);
			}
		}
		files.sort(Comparator.comparing(this::lastModifiedSafely));

		Instant cutoff = Instant.now().minus(maxAge);
		int remaining = files.size();
		for (Path path : files)
		{
			boolean expired = lastModifiedSafely(path).isBefore(cutoff);
			boolean overCount = remaining > maxCount;
			if (expired || overCount)
			{
				Files.deleteIfExists(path);
				remaining--;
			}
		}
	}

	private Instant lastModifiedSafely(Path path)
	{
		try
		{
			return Files.getLastModifiedTime(path).toInstant();
		}
		catch (IOException ex)
		{
			return Instant.EPOCH;
		}
	}

	private Path statePath(String accountHash)
	{
		String safeHash = accountHash == null ? "unknown" : accountHash.replaceAll("[^a-f0-9]", "");
		if (safeHash.isEmpty())
		{
			safeHash = "unknown";
		}
		return state.resolve(safeHash + ".json");
	}

	private void atomicWrite(Path destination, String contents) throws IOException
	{
		Path temporary = destination.resolveSibling(
			destination.getFileName() + "." + UUID.randomUUID() + ".tmp"
		);
		Files.writeString(temporary, contents, StandardCharsets.UTF_8);
		try
		{
			Files.move(
				temporary,
				destination,
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING
			);
		}
		catch (AtomicMoveNotSupportedException ex)
		{
			Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
