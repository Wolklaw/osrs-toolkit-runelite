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

/**
 * This plugin's own files under {@code .runelite/osrs-toolkit/}, read by nothing but this
 * plugin.
 *
 * {@code events/} is a network outbox: a fill written here goes out over HTTPS the moment
 * {@link SyncClient} can reach the service, and the file is deleted only once the service has
 * said it holds the event. It exists so a fill recorded while offline, or right before the
 * client closes, is not lost — the alternative to writing it somewhere is losing it outright,
 * not sending it more directly.
 *
 * {@code state/} is this plugin's own memory of the eight Grand Exchange slots, read back by
 * {@link #readOfferState} the next time RuneLite starts so a fill can still be measured as a
 * delta against an offer that has been open for hours. Older versions of this plugin let the
 * desktop app read this same file directly; that arrangement is what the RuneLite Plugin Hub
 * refused a submission for, and it no longer exists on either side — the desktop app reads
 * live state through the website now, never this folder.
 */
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
	 * The events waiting to be sent, oldest first, and the file each one came from.
	 *
	 * The queue on disk is this plugin's own outbox and nothing else reads it. It exists so that
	 * a fill recorded while the service is unreachable — or while the client is closed before it
	 * could be sent — is still there to send afterwards. A file is deleted only once the service
	 * has said it holds the event.
	 */
	List<PendingEvent> readPendingEvents(int limit) throws IOException
	{
		initialize();
		List<AgedFile> files = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(events, "*.json"))
		{
			for (Path path : stream)
			{
				files.add(new AgedFile(path, lastModifiedSafely(path)));
			}
		}
		files.sort(Comparator.comparing((AgedFile file) -> file.lastModified));

		List<PendingEvent> pending = new ArrayList<>();
		for (AgedFile file : files)
		{
			if (pending.size() >= limit)
			{
				break;
			}
			try
			{
				pending.add(new PendingEvent(file.path, Files.readString(file.path, StandardCharsets.UTF_8)));
			}
			catch (IOException ex)
			{
				// A file being written as we read it is not a failure worth reporting; the next
				// flush finds it finished.
				log.debug("Skipping an unreadable queued event", ex);
			}
		}
		return pending;
	}

	void deleteEvent(Path path)
	{
		deleteQuietly(path);
	}

	/** One queued event: the file it lives in, and the JSON to send. */
	static final class PendingEvent
	{
		final Path path;
		final String json;

		PendingEvent(Path path, String json)
		{
			this.path = path;
			this.json = json;
		}
	}

	/**
	 * A queued event is deleted as soon as the service says it holds it, so this is only a
	 * safety net for events that pile up while the service is unreachable for a long time — or
	 * while the plugin was never pointed at one in the first place.
	 */
	void pruneStaleEvents(Duration maxAge, int maxCount) throws IOException
	{
		initialize();
		// Each file's age is read once here and carried alongside it. Sorting on a key it has to
		// go back to the filesystem for would ask again on every comparison, and again in the
		// loop below: ordering a directory at the cap costs a few hundred thousand reads that
		// way, against one per file this way.
		List<AgedFile> files = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(events, "*.json"))
		{
			for (Path path : stream)
			{
				files.add(new AgedFile(path, lastModifiedSafely(path)));
			}
		}
		files.sort(Comparator.comparing((AgedFile file) -> file.lastModified));

		Instant cutoff = Instant.now().minus(maxAge);
		int remaining = files.size();
		for (AgedFile file : files)
		{
			boolean expired = file.lastModified.isBefore(cutoff);
			boolean overCount = remaining > maxCount;
			if ((expired || overCount) && Files.deleteIfExists(file.path))
			{
				remaining--;
			}
		}
	}

	/**
	 * An event file paired with the moment it was last written, read once when the directory is
	 * listed. Oldest first is what the count cap wants to delete in, and what "expired" is judged
	 * against, so both answers come from the same single reading.
	 */
	private static final class AgedFile
	{
		private final Path path;
		private final Instant lastModified;

		private AgedFile(Path path, Instant lastModified)
		{
			this.path = path;
			this.lastModified = lastModified;
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
	 * Windows denies the replacement outright while another process has the destination open —
	 * antivirus scanning it, an indexer, a backup tool, someone's own text editor — so a write
	 * can collide with a reader for no reason but timing. This file is not shared with anything
	 * else this plugin talks to over the network; the only reader that matters to correctness
	 * is this same plugin's own {@link #readOfferState}, on the next RuneLite start. Losing a
	 * write there leaves the saved offers behind the real ones, and the next client to start
	 * diffs live offers against that stale picture: an offer it has been following for hours
	 * looks brand new. A short wait outlives whatever briefly held the file, and the write
	 * lands on the second or third try.
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
