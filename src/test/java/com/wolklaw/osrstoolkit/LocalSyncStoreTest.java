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
