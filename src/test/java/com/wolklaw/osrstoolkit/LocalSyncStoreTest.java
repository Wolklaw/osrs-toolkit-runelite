package com.wolklaw.osrstoolkit;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LocalSyncStoreTest
{
	private Path tempDir;
	private ScheduledExecutorService executor;
	private LocalSyncStore store;

	@Before
	public void setUp() throws IOException
	{
		tempDir = Files.createTempDirectory("osrs-toolkit-sync-test");
		executor = Executors.newSingleThreadScheduledExecutor();
		store = new LocalSyncStore(new Gson(), tempDir, executor);
		store.initialize();
	}

	@After
	public void tearDown() throws IOException
	{
		executor.shutdownNow();
		Files.walkFileTree(tempDir, new SimpleFileVisitor<Path>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
			{
				Files.deleteIfExists(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
			{
				Files.deleteIfExists(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static OfferSnapshot buyingSnapshot()
	{
		OfferSnapshot snapshot = new OfferSnapshot();
		snapshot.slot = 3;
		snapshot.itemId = 23_736;
		snapshot.itemName = "Divine ranging potion(3)";
		snapshot.offerPrice = 4_159;
		snapshot.totalQuantity = 150;
		snapshot.quantityFilled = 73;
		snapshot.spentGp = 303_607;
		snapshot.state = "BUYING";
		snapshot.offerId = "offer-id";
		return snapshot;
	}

	@Test
	public void writingOfferStateLeavesNoScratchFileBehind() throws IOException
	{
		OfferSnapshot snapshot = buyingSnapshot();

		store.writeOfferState("abc123", Collections.singletonMap(3, snapshot));
		store.writeOfferState("abc123", Collections.singletonMap(3, snapshot));

		try (Stream<Path> files = Files.list(tempDir.resolve("osrs-toolkit").resolve("state")))
		{
			assertEquals(
				Collections.singletonList("abc123.json"),
				files.map(path -> path.getFileName().toString()).collect(Collectors.toList())
			);
		}
		assertEquals(73, store.readOfferState("abc123").get(3).quantityFilled);
	}

	@Test
	public void queuedEventsComeBackOldestFirstWithTheFileTheyCameFrom() throws IOException
	{
		Path older = writeEvent("older");
		Path newer = writeEvent("newer");
		setModifiedDaysAgo(older, 2);
		setModifiedDaysAgo(newer, 1);

		List<LocalSyncStore.PendingEvent> pending = store.readPendingEvents(10);

		// Oldest first, because a fill has to reach the desktop app in the order it happened —
		// an offer opens before anything lands on it.
		assertEquals(
			List.of(older, newer),
			pending.stream().map(event -> event.path).collect(Collectors.toList())
		);
		assertEquals("{}", pending.get(0).json);
	}

	@Test
	public void readingTheQueueStopsAtTheLimit() throws IOException
	{
		for (int index = 0; index < 5; index++)
		{
			setModifiedDaysAgo(writeEvent("event-" + index), 5 - index);
		}

		assertEquals(2, store.readPendingEvents(2).size());
	}

	@Test
	public void deletingASentEventTakesItOutOfTheQueue() throws IOException
	{
		Path sent = writeEvent("sent");
		writeEvent("still-waiting");

		store.deleteEvent(sent);

		// Only what the service confirmed it holds is dropped; everything else is still owed.
		assertTrue(Files.notExists(sent));
		assertEquals(1, store.readPendingEvents(10).size());
	}

	@Test
	public void deletingAnEventThatIsAlreadyGoneIsHarmless() throws IOException
	{
		Path sent = writeEvent("sent");
		store.deleteEvent(sent);

		store.deleteEvent(sent);

		assertTrue(Files.notExists(sent));
	}

	@Test
	public void theQueueIgnoresScratchFilesFromWritesInFlight() throws IOException
	{
		writeEvent("real");
		Files.writeString(eventsDir().resolve("half-written.json.1234.tmp"), "{\"partial\"");

		List<LocalSyncStore.PendingEvent> pending = store.readPendingEvents(10);

		assertEquals(1, pending.size());
	}

	@Test
	public void pruneRemovesEventsOlderThanMaxAge() throws IOException
	{
		Path oldEvent = writeEvent("old-event");
		Path freshEvent = writeEvent("fresh-event");
		setModifiedDaysAgo(oldEvent, 40);
		setModifiedDaysAgo(freshEvent, 1);

		store.pruneStaleEvents(Duration.ofDays(30), 1_000);

		assertTrue(Files.notExists(oldEvent));
		assertTrue(Files.exists(freshEvent));
	}

	@Test
	public void pruneCapsTotalEventCountKeepingTheNewestFiles() throws IOException
	{
		for (int index = 0; index < 5; index++)
		{
			Path event = writeEvent("event-" + index);
			setModifiedDaysAgo(event, 5 - index);
		}

		store.pruneStaleEvents(Duration.ofDays(30), 2);

		assertEquals(2, countEventFiles());
		assertTrue(Files.exists(eventsDir().resolve("event-3.json")));
		assertTrue(Files.exists(eventsDir().resolve("event-4.json")));
	}

	@Test
	public void offerStateSurvivesADamagedFileInsteadOfFailingEveryRead() throws IOException
	{
		Path statePath = tempDir.resolve("osrs-toolkit").resolve("state").resolve("abc123.json");
		Files.writeString(statePath, "{ this is not json");

		assertTrue(store.readOfferState("abc123").isEmpty());

		// And the next write still repairs it, so tracking recovers on its own.
		OfferSnapshot snapshot = new OfferSnapshot();
		snapshot.slot = 1;
		snapshot.itemId = 453;
		snapshot.totalQuantity = 100;
		snapshot.state = "BUYING";
		store.writeOfferState("abc123", Collections.singletonMap(1, snapshot));

		assertEquals(1, store.readOfferState("abc123").size());
	}

	@Test
	public void offerStateIgnoresWellFormedJsonOfTheWrongShape() throws IOException
	{
		Path statePath = tempDir.resolve("osrs-toolkit").resolve("state").resolve("abc123.json");
		Files.writeString(statePath, "[{\"slot\":1}]");

		// Valid JSON, wrong shape: reads as no offers rather than throwing past the caller and
		// wedging every later read.
		assertTrue(store.readOfferState("abc123").isEmpty());
	}

	@Test
	public void offerStateDropsASlotItCannotNameWithoutLosingTheOthers() throws IOException
	{
		Path statePath = tempDir.resolve("osrs-toolkit").resolve("state").resolve("abc123.json");
		Files.writeString(
			statePath,
			"{\"1\":{\"slot\":1,\"itemId\":453,\"totalQuantity\":100,\"state\":\"BUYING\"},"
				+ "\"not-a-slot\":{\"slot\":2,\"itemId\":454,\"totalQuantity\":50,\"state\":\"BUYING\"},"
				+ "\"3\":[]}"
		);

		Map<Integer, OfferSnapshot> offers = store.readOfferState("abc123");

		assertEquals(1, offers.size());
		assertTrue(offers.containsKey(1));
	}

	@Test
	public void offerStateDropsEntriesThisBuildCannotUnderstand() throws IOException
	{
		Path statePath = tempDir.resolve("osrs-toolkit").resolve("state").resolve("abc123.json");
		Files.writeString(
			statePath,
			"{\"1\":{\"slot\":1,\"itemId\":453,\"totalQuantity\":100,\"state\":\"BUYING\"},"
				+ "\"2\":{\"slot\":2,\"itemId\":454,\"totalQuantity\":50,\"state\":\"SOMETHING_NEW\"},"
				+ "\"3\":{\"slot\":3,\"itemId\":455,\"totalQuantity\":50}}"
		);

		Map<Integer, OfferSnapshot> offers = store.readOfferState("abc123");

		assertEquals(1, offers.size());
		assertTrue(offers.containsKey(1));
	}

	@Test
	public void sweepsAbandonedScratchFilesButLeavesRecentOnesAlone() throws IOException
	{
		Path abandoned = eventsDir().resolve("abandoned.json.1234.tmp");
		Path inFlight = eventsDir().resolve("in-flight.json.5678.tmp");
		Files.writeString(abandoned, "{}");
		Files.writeString(inFlight, "{}");
		setModifiedDaysAgo(abandoned, 2);

		store.deleteStaleTemporaryFiles(Duration.ofHours(1));

		assertTrue(Files.notExists(abandoned));
		assertTrue(Files.exists(inFlight));
	}

	@Test
	public void pruneIgnoresScratchFilesWhenCountingEvents() throws IOException
	{
		writeEvent("kept-event");
		Path scratch = eventsDir().resolve("kept-event.json.9999.tmp");
		Files.writeString(scratch, "{}");

		store.pruneStaleEvents(Duration.ofDays(30), 1);

		assertTrue(Files.exists(eventsDir().resolve("kept-event.json")));
		assertTrue(Files.exists(scratch));
	}

	@Test
	public void queuedFilesCarryTheMomentTheyWereQueued() throws IOException
	{
		store.writeEvent(SyncEvent.geOfferCancelled("abc123", "Zed", buyingSnapshot()));

		try (Stream<Path> files = Files.list(eventsDir()))
		{
			String name = files.map(path -> path.getFileName().toString()).findFirst().orElse("");
			// A fixed-width millisecond stamp, so sorting the names sorts them by age — which
			// is what lets the queue be ordered without a stat per file.
			assertTrue(name, name.matches("\\d{13}-.+\\.json"));
		}
	}

	@Test
	public void queuedEventsAreOrderedWithoutAskingTheFilesystem() throws IOException
	{
		Path newer = writeStampedEvent(2_000_000_000_000L, "newer");
		Path older = writeStampedEvent(1_000_000_000_000L, "older");
		// Deliberately the wrong way round on disk: if ordering came from the file's own
		// timestamp rather than its name, "newer" was written first and would come back first.
		setModifiedDaysAgo(newer, 9);
		setModifiedDaysAgo(older, 1);

		List<LocalSyncStore.PendingEvent> pending = store.readPendingEvents(10);

		assertEquals(
			List.of(older, newer),
			pending.stream().map(event -> event.path).collect(Collectors.toList())
		);
	}

	@Test
	public void aQueueFromAnOlderVersionStillDrainsInOrder() throws IOException
	{
		// No stamp in the name, so age has to come from the filesystem — the two kinds sort
		// against each other while an upgraded queue empties.
		Path legacy = writeEvent("legacy");
		setModifiedDaysAgo(legacy, 30);
		Path stamped = writeStampedEvent(System.currentTimeMillis(), "stamped");

		List<LocalSyncStore.PendingEvent> pending = store.readPendingEvents(10);

		assertEquals(
			List.of(legacy, stamped),
			pending.stream().map(event -> event.path).collect(Collectors.toList())
		);
	}

	@Test
	public void onlyTheBatchIsReadEvenWithAFullQueue() throws IOException
	{
		for (int index = 0; index < 50; index++)
		{
			writeStampedEvent(1_000_000_000_000L + index, "event-" + index);
		}

		List<LocalSyncStore.PendingEvent> pending = store.readPendingEvents(1);

		assertEquals(1, pending.size());
		assertEquals("1000000000000-event-0.json", pending.get(0).path.getFileName().toString());
	}

	private Path writeStampedEvent(long queuedAtMillis, String name) throws IOException
	{
		Files.createDirectories(eventsDir());
		Path path = eventsDir().resolve(String.format("%013d-%s.json", queuedAtMillis, name));
		Files.writeString(path, "{}");
		return path;
	}

	private Path writeEvent(String name) throws IOException
	{
		Path path = eventsDir().resolve(name + ".json");
		Files.createDirectories(eventsDir());
		Files.writeString(path, "{}");
		return path;
	}

	private Path eventsDir()
	{
		return tempDir.resolve("osrs-toolkit").resolve("events");
	}

	private long countEventFiles() throws IOException
	{
		try (Stream<Path> files = Files.list(eventsDir()))
		{
			return files.count();
		}
	}

	private void setModifiedDaysAgo(Path path, long days) throws IOException
	{
		Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(Duration.ofDays(days))));
	}
}
