package test.atriasoft.archidata.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.atriasoft.archidata.backup.BackupEngine;
import org.atriasoft.archidata.backup.BackupEngine.EngineBackupType;
import org.atriasoft.archidata.backup.RetentionPolicy;
import org.atriasoft.archidata.dataAccess.DataAccess;
import org.atriasoft.archidata.dataAccess.options.AccessDeletedItems;
import org.atriasoft.archidata.dataAccess.options.ReadAllColumn;
import org.atriasoft.archidata.tools.ConfigBaseVariable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import test.atriasoft.archidata.ConfigureDb;
import test.atriasoft.archidata.backup.model.DataStoreWithUpdate;
import test.atriasoft.archidata.backup.model.DataStoreWithoutUpdate;

/**
 * Tests of the delta backup API: a delta archive only contains the documents created/updated
 * since a full backup, and is re-applied on top of that full with {@code restoreDeltaFile}
 * (upsert by {@code _id}).
 */
public class TestBackupDelta {

	private Path tempBackupDir;

	@BeforeAll
	public static void configureWebServer() throws Exception {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		ConfigureDb.configure();
	}

	@AfterAll
	public static void removeDataBase() throws IOException {
		ConfigureDb.clear();
	}

	@BeforeEach
	public void setUpBackupDir() throws IOException {
		this.tempBackupDir = Files.createTempDirectory("test_backup_delta_");
	}

	@AfterEach
	public void tearDownBackupDir() throws IOException {
		BackupTestTools.deleteRecursive(this.tempBackupDir);
	}

	private BackupEngine createEngine(final String baseName) {
		final BackupEngine engine = new BackupEngine(this.tempBackupDir, baseName, EngineBackupType.JSON_EXTENDED);
		engine.setEnableStoreOrRestoreData(false);
		return engine;
	}

	private DataStoreWithUpdate insertWithUpdate(final long value) throws Exception {
		final DataStoreWithUpdate dataInsert = new DataStoreWithUpdate();
		dataInsert.dataLong = value;
		dataInsert.dataDoubles = List.of((double) value);
		return DataAccess.insert(dataInsert);
	}

	private DataStoreWithoutUpdate insertWithoutUpdate(final String value) throws Exception {
		final DataStoreWithoutUpdate dataInsert = new DataStoreWithoutUpdate();
		dataInsert.dataString = value;
		return DataAccess.insert(dataInsert);
	}

	@Test
	public void testDeltaContainsOnlyChangesSinceFull() throws Exception {
		DataAccess.drop(DataStoreWithUpdate.class);
		DataAccess.drop(DataStoreWithoutUpdate.class);
		// State before the full: 3 dated documents + 1 undated document
		insertWithUpdate(1);
		insertWithUpdate(2);
		final DataStoreWithUpdate toModify = insertWithUpdate(3);
		insertWithoutUpdate("before full");
		Thread.sleep(20);

		final BackupEngine engine = createEngine("deltacontent");
		final Date fullDate = engine.storeAll("2026-01-01_00:00:00.000_full");
		Thread.sleep(20);

		// Changes after the full: 1 update, 1 creation, 1 undated creation
		toModify.dataLong = 333L;
		DataAccess.updateById(toModify, toModify.getOid());
		insertWithUpdate(4);
		insertWithoutUpdate("after full");

		engine.storeAllDelta("2026-01-01_00:15:00.000_delta", fullDate);

		final Map<String, String> deltaContent = BackupTestTools
				.extractTarGzToMap(this.tempBackupDir.resolve("deltacontent_2026-01-01_00:15:00.000_delta.tar.gz"));
		// Dated collection: only the updated and the created documents
		Assertions.assertEquals(2, BackupTestTools.countLines(deltaContent.get("DataStoreWithUpdate.json")));
		// Undated collection: no way to date the documents, all of them are included (safe fallback)
		Assertions.assertEquals(2, BackupTestTools.countLines(deltaContent.get("DataStoreWithoutUpdate.json")));
	}

	@Test
	public void testFullPlusDeltaRestoreRoundTrip() throws Exception {
		DataAccess.drop(DataStoreWithUpdate.class);
		DataAccess.drop(DataStoreWithoutUpdate.class);
		insertWithUpdate(1);
		insertWithUpdate(2);
		final DataStoreWithUpdate toModify = insertWithUpdate(3);
		Thread.sleep(20);

		final BackupEngine engine = createEngine("deltaroundtrip");
		final Date fullDate = engine.storeAll("2026-01-01_00:00:00.000_full");
		Thread.sleep(20);

		toModify.dataLong = 333L;
		DataAccess.updateById(toModify, toModify.getOid());
		insertWithUpdate(4);
		engine.storeAllDelta("2026-01-01_00:15:00.000_delta", fullDate);

		// Wipe and restore the full: intermediate state (3 documents, original value)
		DataAccess.drop(DataStoreWithUpdate.class);
		DataAccess.drop(DataStoreWithoutUpdate.class);
		final boolean result = engine
				.restoreFile(this.tempBackupDir.resolve("deltaroundtrip_2026-01-01_00:00:00.000_full.tar.gz"), null);
		Assertions.assertTrue(result);
		List<DataStoreWithUpdate> restored = DataAccess.gets(DataStoreWithUpdate.class, new AccessDeletedItems(),
				new ReadAllColumn());
		Assertions.assertEquals(3, restored.size());
		final DataStoreWithUpdate beforeDelta = restored.stream()
				.filter(elem -> elem.getOid().equals(toModify.getOid())).findFirst().orElseThrow();
		Assertions.assertEquals(3L, beforeDelta.dataLong);

		// Apply the delta: final state (4 documents, updated value)
		engine.restoreDeltaFile(this.tempBackupDir.resolve("deltaroundtrip_2026-01-01_00:15:00.000_delta.tar.gz"));
		restored = DataAccess.gets(DataStoreWithUpdate.class, new AccessDeletedItems(), new ReadAllColumn());
		Assertions.assertEquals(4, restored.size());
		final DataStoreWithUpdate afterDelta = restored.stream().filter(elem -> elem.getOid().equals(toModify.getOid()))
				.findFirst().orElseThrow();
		Assertions.assertEquals(333L, afterDelta.dataLong);
	}

	@Test
	public void testChunkedDeltaRestore() throws Exception {
		DataAccess.drop(DataStoreWithUpdate.class);
		insertWithUpdate(1);
		insertWithUpdate(2);
		insertWithUpdate(3);

		final BackupEngine engine = createEngine("deltachunk");
		engine.setChunkMaxDocuments(1);
		// since = epoch: every document is part of the delta
		engine.storeAllDelta("2026-01-01_00:15:00.000_delta", new Date(0));

		final Path archivePath = this.tempBackupDir.resolve("deltachunk_2026-01-01_00:15:00.000_delta.tar.gz");
		final Map<String, String> deltaContent = BackupTestTools.extractTarGzToMap(archivePath);
		Assertions.assertEquals(1, BackupTestTools.countLines(deltaContent.get("DataStoreWithUpdate.json")));
		Assertions.assertEquals(1, BackupTestTools.countLines(deltaContent.get("DataStoreWithUpdate__000001.json")));
		Assertions.assertEquals(1, BackupTestTools.countLines(deltaContent.get("DataStoreWithUpdate__000002.json")));

		// Upsert restore merges the chunks (and works on an empty collection)
		DataAccess.drop(DataStoreWithUpdate.class);
		engine.restoreDeltaFile(archivePath);
		final List<DataStoreWithUpdate> restored = DataAccess.gets(DataStoreWithUpdate.class, new AccessDeletedItems(),
				new ReadAllColumn());
		Assertions.assertEquals(3, restored.size());
	}

	@Test
	public void testDeltaMediaOnlyIncludesRecentFiles() throws Exception {
		DataAccess.drop(DataStoreWithUpdate.class);
		final Path tempMediaDir = Files.createTempDirectory("test_media_delta_");
		ConfigBaseVariable.setDataFolder(tempMediaDir.toString());
		try {
			Files.writeString(tempMediaDir.resolve("old-file.txt"), "old media");
			Thread.sleep(100);
			final Date since = new Date();
			Thread.sleep(100);
			Files.writeString(tempMediaDir.resolve("new-file.txt"), "new media");

			final BackupEngine engine = new BackupEngine(this.tempBackupDir, "deltamedia",
					EngineBackupType.JSON_EXTENDED);
			engine.storeAllDelta("2026-01-01_00:15:00.000_delta", since);

			final Map<String, String> deltaContent = BackupTestTools
					.extractTarGzToMap(this.tempBackupDir.resolve("deltamedia_2026-01-01_00:15:00.000_delta.tar.gz"));
			Assertions.assertTrue(deltaContent.containsKey("data/new-file.txt"));
			Assertions.assertFalse(deltaContent.containsKey("data/old-file.txt"));

			// Delta restore extracts over the existing media without moving them away
			Files.delete(tempMediaDir.resolve("new-file.txt"));
			engine.restoreDeltaFile(this.tempBackupDir.resolve("deltamedia_2026-01-01_00:15:00.000_delta.tar.gz"));
			Assertions.assertEquals("new media", Files.readString(tempMediaDir.resolve("new-file.txt")));
			Assertions.assertEquals("old media", Files.readString(tempMediaDir.resolve("old-file.txt")));
			Assertions.assertFalse(Files.exists(tempMediaDir.resolve("history_restore")));
		} finally {
			ConfigBaseVariable.setDataFolder(null);
			BackupTestTools.deleteRecursive(tempMediaDir);
		}
	}

	@Test
	public void testRetentionDropsDeltasSupersededByNewerFull() throws Exception {
		final String base = "retentiondelta";
		final List<String> fileNames = List.of(//
				base + "_2026-01-01_00:00:00.000_full.tar.gz", //
				base + "_2026-01-01_00:15:00.000_delta.tar.gz", //
				base + "_2026-01-01_05:45:00.000_delta.tar.gz", //
				base + "_2026-01-01_06:00:00.000_full.tar.gz", //
				base + "_2026-01-01_06:15:00.000_delta.tar.gz");
		for (final String fileName : fileNames) {
			Files.createFile(this.tempBackupDir.resolve(fileName));
		}
		final BackupEngine engine = createEngine(base);
		// keepAllDays very large: no full backup is due for deletion, only superseded deltas
		final List<Path> deleted = engine.clean(new RetentionPolicy(9999, 9999, 9999), LocalDate.of(2026, 1, 2));

		Assertions.assertEquals(2, deleted.size());
		Assertions.assertFalse(Files.exists(this.tempBackupDir.resolve(fileNames.get(1))));
		Assertions.assertFalse(Files.exists(this.tempBackupDir.resolve(fileNames.get(2))));
		Assertions.assertTrue(Files.exists(this.tempBackupDir.resolve(fileNames.get(0))));
		Assertions.assertTrue(Files.exists(this.tempBackupDir.resolve(fileNames.get(3))));
		Assertions.assertTrue(Files.exists(this.tempBackupDir.resolve(fileNames.get(4))));
	}

	@Test
	public void testGetLastFullBackupDateAndRestoreChain() throws Exception {
		final String base = "chaindelta";
		final List<String> fileNames = List.of(//
				base + "_2026-01-01_00:00:00.000_full.tar.gz", //
				base + "_2026-01-01_05:45:00.000_delta.tar.gz", //
				base + "_2026-01-01_06:00:00.000_full.tar.gz", //
				base + "_2026-01-01_06:15:00.000_delta.tar.gz", //
				base + "_2026-01-01_06:30:00.000_delta.tar.gz");
		for (final String fileName : fileNames) {
			Files.createFile(this.tempBackupDir.resolve(fileName));
		}
		final BackupEngine engine = createEngine(base);

		final Date lastFull = engine.getLastFullBackupDate();
		Assertions.assertNotNull(lastFull);
		Assertions.assertEquals(Date.from(LocalDateTime.of(2026, 1, 1, 6, 0, 0).toInstant(ZoneOffset.UTC)).getTime(),
				lastFull.getTime());

		final List<Path> chain = engine.getLatestRestoreChain();
		Assertions.assertEquals(2, chain.size());
		Assertions.assertEquals(fileNames.get(2), chain.get(0).getFileName().toString());
		Assertions.assertEquals(fileNames.get(4), chain.get(1).getFileName().toString());
	}

	@Test
	public void testPartialArchiveIsNotARestoreBase() throws Exception {
		final String base = "partialbase";
		final List<String> fileNames = List.of(//
				base + "_2026-01-01_00:00:00.000_full.tar.gz", //
				base + "_2026-01-01_06:00:00.000_partial.tar.gz", //
				base + "_2026-01-01_06:15:00.000_delta.tar.gz");
		for (final String fileName : fileNames) {
			Files.createFile(this.tempBackupDir.resolve(fileName));
		}
		final BackupEngine engine = createEngine(base);

		// A partial archive (subset of collections) newer than the last full must not become
		// the delta/restore base
		final Date lastFull = engine.getLastFullBackupDate();
		Assertions.assertNotNull(lastFull);
		Assertions.assertEquals(Date.from(LocalDateTime.of(2026, 1, 1, 0, 0, 0).toInstant(ZoneOffset.UTC)).getTime(),
				lastFull.getTime());

		final List<Path> chain = engine.getLatestRestoreChain();
		Assertions.assertEquals(2, chain.size());
		Assertions.assertEquals(fileNames.get(0), chain.get(0).getFileName().toString());
		Assertions.assertEquals(fileNames.get(2), chain.get(1).getFileName().toString());
	}

	@Test
	public void testGetLastFullBackupDateWithoutAnyFull() throws Exception {
		final BackupEngine engine = createEngine("nofull");
		Assertions.assertNull(engine.getLastFullBackupDate());
		Assertions.assertTrue(engine.getLatestRestoreChain().isEmpty());

		Files.createFile(this.tempBackupDir.resolve("nofull_2026-01-01_00:15:00.000_delta.tar.gz"));
		// A delta without any full backup cannot be used as a restore base
		Assertions.assertNull(engine.getLastFullBackupDate());
		Assertions.assertTrue(engine.getLatestRestoreChain().isEmpty());
	}
}
