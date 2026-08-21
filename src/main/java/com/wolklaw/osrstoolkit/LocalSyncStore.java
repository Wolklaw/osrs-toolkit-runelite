package com.wolklaw.osrstoolkit;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class LocalSyncStore
{
	private static final int REPLACE_ATTEMPTS = 3;
	private static final long REPLACE_RETRY_MILLIS = 40;

	private final Gson gson;
	private final Path root;
	private final Path events;
	private final Path state;
	private final ScheduledExecutorService executor;

	LocalSyncStore(Gson gson, Path runeLiteDirectory, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.root = runeLiteDirectory.resolve("osrs-toolkit");
		this.events = root.resolve("events");
		this.state = root.resolve("state");
		this.executor = executor;
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
		JsonElement parsed;
		try
		{
			parsed = gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonElement.class);
		}
		catch (JsonParseException | CharacterCodingException ex)
		{
			// A damaged state file must not wedge Grand Exchange tracking for good. Every read
			// happens on the way to a write that replaces the file, so starting from nothing
			// costs at most one round of offers being treated as newly placed.
			return new LinkedHashMap<>();
		}
		if (parsed == null || !parsed.isJsonObject())
		{
			// Well-formed JSON that is not the shape this file is written in — a list, a bare
			// number — reads the same as no file at all rather than throwing past the caller.
			return new LinkedHashMap<>();
		}
		JsonObject snapshots = parsed.getAsJsonObject();
		// Walked slot by slot rather than deserialized in one go as a Map, because one unreadable
		// entry should cost that slot and not the whole file — and because asking Gson for a
		// generic Map means handing it a TypeToken, which drags in an import this plugin
		// otherwise has no reason to carry.
		Map<Integer, OfferSnapshot> usable = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : snapshots.entrySet())
		{
			Integer slot = parseSlot(entry.getKey());
			if (slot == null)
			{
				continue;
			}
			OfferSnapshot snapshot;
			try
			{
				snapshot = gson.fromJson(entry.getValue(), OfferSnapshot.class);
			}
			catch (JsonParseException ex)
			{
				continue;
			}
			if (snapshot != null && snapshot.isValid())
			{
				usable.put(slot, snapshot);
			}
		}
		return usable;
	}

	void writeOfferState(String accountHash, Map<Integer, OfferSnapshot> snapshots) throws IOException
	{
		initialize();
		atomicWrite(statePath(accountHash), gson.toJson(snapshots));
	}

	/**
	 * Record where in the Grand Exchange the player is, or take the record away.
	 *
	 * Absence is the message. The desktop app reads a missing file as "not at the Grand Exchange",
	 * so walking away has to actually delete it rather than leave the last item chosen behind for
	 * the app to go on pointing at. The stamp inside is what covers the case deletion cannot: a
	 * client that stops existing mid-trade deletes nothing, so the app times the file out and the
	 * plugin re-stamps it for as long as the player really is standing there.
	 */
	void writeOfferScreen(String accountHash, OfferScreen screen) throws IOException
	{
		initialize();
		Path path = offerScreenPath(accountHash);
		if (screen == null)
		{
			Files.deleteIfExists(path);
			return;
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("schema_version", 1);
		payload.put("updated_at", Instant.now().toString());
		// Zero, and no name or side with it, is the interface open on nothing in particular. The
		// file existing at all is what says the player is standing at the Grand Exchange.
		payload.put("item_id", screen.itemId);
		payload.put("item_name", screen.itemName);
		payload.put("side", screen.side);
		atomicWrite(path, gson.toJson(payload));
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
			if ((expired || overCount) && Files.deleteIfExists(path))
			{
				remaining--;
			}
		}
	}

	/**
	 * A write cut short by the client shutting down leaves its scratch file behind, and nothing
	 * ever revisits those names. Only sweep ones old enough that no write could still be using
	 * them, so a second client running against the same directory is left alone.
	 */
	void deleteStaleTemporaryFiles(Duration minAge) throws IOException
	{
		initialize();
		Instant cutoff = Instant.now().minus(minAge);
		for (Path directory : List.of(root, events, state))
		{
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.tmp"))
			{
				for (Path path : stream)
				{
					if (lastModifiedSafely(path).isBefore(cutoff))
					{
						Files.deleteIfExists(path);
					}
				}
			}
		}
	}

	/**
	 * The slot a state-file key names, or null where it names nothing this build can use.
	 *
	 * The keys are written from a {@code Map<Integer, …>}, so JSON turns them into strings on the
	 * way out and something has to turn them back. A key that will not, from a damaged or
	 * hand-edited file, costs its own slot and no other.
	 */
	private static Integer parseSlot(String key)
	{
		try
		{
			return Integer.valueOf(key);
		}
		catch (NumberFormatException ex)
		{
			return null;
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
		return stateFile(accountHash, ".json");
	}

	private Path offerScreenPath(String accountHash)
	{
		return stateFile(accountHash, "-screen.json");
	}

	private Path stateFile(String accountHash, String suffix)
	{
		String safeHash = accountHash == null ? "unknown" : accountHash.replaceAll("[^a-f0-9]", "");
		if (safeHash.isEmpty())
		{
			safeHash = "unknown";
		}
		return state.resolve(safeHash + suffix);
	}

	/**
	 * Replace a file's contents in one step, retrying a moment later if the filesystem refuses.
	 *
	 * Windows denies the replacement outright while another process has the destination open,
	 * and the desktop app reads the offer-state file whenever it redraws the Grand Exchange
	 * slots — so a write can collide with a reader for no reason but timing. Losing one leaves
	 * the saved offers behind the real ones, and the next client to start diffs live offers
	 * against that stale picture: an offer it has been following for hours looks brand new.
	 * A short wait outlives the reader, and the write lands on the second or third try.
	 *
	 * The first attempt happens inline; a contested attempt is retried by rescheduling itself
	 * on the same background executor rather than blocking that thread, so one contested write
	 * never stalls whatever is queued behind it.
	 */
	private void atomicWrite(Path destination, String contents) throws IOException
	{
		Path temporary = destination.resolveSibling(
			destination.getFileName() + "." + UUID.randomUUID() + ".tmp"
		);
		Files.writeString(temporary, contents, StandardCharsets.UTF_8);
		try
		{
			replace(temporary, destination);
			deleteQuietly(temporary);
		}
		catch (FileSystemException ex)
		{
			scheduleReplaceRetry(temporary, destination, 1);
		}
	}

	private void scheduleReplaceRetry(Path temporary, Path destination, int attempt)
	{
		executor.schedule(
			() -> retryReplace(temporary, destination, attempt),
			REPLACE_RETRY_MILLIS,
			TimeUnit.MILLISECONDS
		);
	}

	private void retryReplace(Path temporary, Path destination, int attempt)
	{
		try
		{
			replace(temporary, destination);
			deleteQuietly(temporary);
		}
		catch (FileSystemException ex)
		{
			if (attempt >= REPLACE_ATTEMPTS)
			{
				log.debug("Giving up replacing {} after {} attempts", destination.getFileName(), attempt, ex);
				deleteQuietly(temporary);
			}
			else
			{
				scheduleReplaceRetry(temporary, destination, attempt + 1);
			}
		}
		catch (IOException ex)
		{
			log.debug("Unable to replace {}", destination.getFileName(), ex);
			deleteQuietly(temporary);
		}
	}

	private void deleteQuietly(Path path)
	{
		// A move that never happened leaves its scratch file behind, and nothing else
		// revisits that name until the hourly sweep.
		try
		{
			Files.deleteIfExists(path);
		}
		catch (IOException ignored)
		{
			// Best effort: the sweep collects it later.
		}
	}

	private void replace(Path temporary, Path destination) throws IOException
	{
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
