package com.wolklaw.osrstoolkit;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
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
	private LocalSyncStore store;

	@Before
	public void setUp() throws IOException
	{
		tempDir = Files.createTempDirectory("osrs-toolkit-sync-test");
		store = new LocalSyncStore(new Gson(), tempDir);
		store.initialize();
	}

	@After
	public void tearDown() throws IOException
	{
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

	@Test
	public void writingOfferStateLeavesNoScratchFileBehind() throws IOException
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
