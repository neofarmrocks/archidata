package test.atriasoft.archidata.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import org.atriasoft.archidata.backup.BackupEngine;
import org.atriasoft.archidata.backup.BackupEngine.EngineBackupType;
import org.atriasoft.archidata.checker.DataAccessConnectionContext;
import org.atriasoft.archidata.dataAccess.DBAccessMongo;
import org.atriasoft.archidata.dataAccess.DataAccess;
import org.atriasoft.archidata.dataAccess.options.AccessDeletedItems;
import org.atriasoft.archidata.dataAccess.options.ReadAllColumn;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import test.atriasoft.archidata.ConfigureDb;
import test.atriasoft.archidata.backup.model.DataStoreWithUpdate;

/**
 * Tests of the chunked archive layout: a collection bigger than the chunk thresholds is split
 * into multiple archive entries ({@code <collection>.json}, {@code <collection>__000001.json}, ...)
 * and merged back into a single collection at restore.
 */
public class TestBackupChunked {

	@BeforeAll
	public static void configureWebServer() throws Exception {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		ConfigureDb.configure();
	}

	@AfterAll
	public static void removeDataBase() throws IOException {
		ConfigureDb.clear();
	}

	private void insertDocuments(final int count) throws Exception {
		DataAccess.drop(DataStoreWithUpdate.class);
		for (int i = 0; i < count; i++) {
			final DataStoreWithUpdate dataInsert = new DataStoreWithUpdate();
			dataInsert.dataLong = (long) i;
			dataInsert.dataDoubles = List.of((double) i);
			DataAccess.insert(dataInsert);
		}
	}

	@Test
	public void testChunkedBackupByDocumentCountAndRestore() throws Exception {
		insertDocuments(5);
		final Path backupDir = Files.createTempDirectory("backup_chunked_test");
		try {
			final BackupEngine engine = new BackupEngine(backupDir, "chunked", EngineBackupType.JSON_EXTENDED);
			engine.addClass(DataStoreWithUpdate.class);
			engine.setEnableStoreOrRestoreData(false);
			engine.setChunkMaxDocuments(2);
			engine.store("chunk-doc");

			final Path archivePath = backupDir.resolve("chunked_chunk-doc.tar.gz");
			final Map<String, String> dataExtract = BackupTestTools.extractTarGzToMap(archivePath);
			// 5 documents, 2 per chunk => 3 entries: base name + 2 suffixed chunks
			Assertions.assertEquals(3, dataExtract.size());
			Assertions.assertEquals(2, BackupTestTools.countLines(dataExtract.get("DataStoreWithUpdate.json")));
			Assertions.assertEquals(2, BackupTestTools.countLines(dataExtract.get("DataStoreWithUpdate__000001.json")));
			Assertions.assertEquals(1, BackupTestTools.countLines(dataExtract.get("DataStoreWithUpdate__000002.json")));

			// Restore: the 3 chunks must be merged back in a single collection
			DataAccess.drop(DataStoreWithUpdate.class);
			final boolean result = engine.restoreFile(archivePath, "DataStoreWithUpdate");
			Assertions.assertTrue(result);
			final List<DataStoreWithUpdate> restored = DataAccess.gets(DataStoreWithUpdate.class,
					new AccessDeletedItems(), new ReadAllColumn());
			Assertions.assertEquals(5, restored.size());
		} finally {
			BackupTestTools.deleteRecursive(backupDir);
		}
	}

	@Test
	public void testChunkedBackupByBytes() throws Exception {
		insertDocuments(4);
		final Path backupDir = Files.createTempDirectory("backup_chunked_bytes_test");
		try {
			final BackupEngine engine = new BackupEngine(backupDir, "chunkedbytes", EngineBackupType.JSON_EXTENDED);
			engine.addClass(DataStoreWithUpdate.class);
			engine.setEnableStoreOrRestoreData(false);
			// 1 byte threshold => every document flushes its own chunk
			engine.setChunkMaxBytes(1);
			engine.store("chunk-bytes");

			final Path archivePath = backupDir.resolve("chunkedbytes_chunk-bytes.tar.gz");
			final Map<String, String> dataExtract = BackupTestTools.extractTarGzToMap(archivePath);
			Assertions.assertEquals(4, dataExtract.size());
			Assertions.assertEquals(1, BackupTestTools.countLines(dataExtract.get("DataStoreWithUpdate.json")));
			Assertions.assertEquals(1, BackupTestTools.countLines(dataExtract.get("DataStoreWithUpdate__000001.json")));
			Assertions.assertEquals(1, BackupTestTools.countLines(dataExtract.get("DataStoreWithUpdate__000002.json")));
			Assertions.assertEquals(1, BackupTestTools.countLines(dataExtract.get("DataStoreWithUpdate__000003.json")));
		} finally {
			BackupTestTools.deleteRecursive(backupDir);
		}
	}

	@Test
	public void testBackupBelowThresholdKeepsLegacySingleEntryLayout() throws Exception {
		insertDocuments(5);
		final Path backupDir = Files.createTempDirectory("backup_legacy_layout_test");
		try {
			final BackupEngine engine = new BackupEngine(backupDir, "legacy", EngineBackupType.JSON_EXTENDED);
			engine.addClass(DataStoreWithUpdate.class);
			engine.setEnableStoreOrRestoreData(false);
			// Default thresholds: 5 small documents stay in a single legacy-named entry
			engine.store("legacy");

			final Map<String, String> dataExtract = BackupTestTools
					.extractTarGzToMap(backupDir.resolve("legacy_legacy.tar.gz"));
			Assertions.assertEquals(1, dataExtract.size());
			Assertions.assertEquals(5, BackupTestTools.countLines(dataExtract.get("DataStoreWithUpdate.json")));
		} finally {
			BackupTestTools.deleteRecursive(backupDir);
		}
	}

	@Test
	public void testEmptyCollectionStillProducesAnEntry() throws Exception {
		DataAccess.drop(DataStoreWithUpdate.class);
		final Path backupDir = Files.createTempDirectory("backup_empty_col_test");
		try {
			final BackupEngine engine = new BackupEngine(backupDir, "emptycol", EngineBackupType.JSON_EXTENDED);
			engine.addClass(DataStoreWithUpdate.class);
			engine.setEnableStoreOrRestoreData(false);
			engine.store("empty");

			final Map<String, String> dataExtract = BackupTestTools
					.extractTarGzToMap(backupDir.resolve("emptycol_empty.tar.gz"));
			Assertions.assertEquals(1, dataExtract.size());
			Assertions.assertEquals("", dataExtract.get("DataStoreWithUpdate.json"));
		} finally {
			BackupTestTools.deleteRecursive(backupDir);
		}
	}

	@Test
	public void testChunkedRestoreFailsWhenCollectionNotEmpty() throws Exception {
		insertDocuments(1);
		final Map<String, String> data = Map.of( //
				"DataStoreWithUpdate.json", """
						{"_id": {"$oid": "aaaaaaaaaaaaaaaaaaaaaa01"}, "dataLong": {"$numberLong": "1"}}
						""", //
				"DataStoreWithUpdate__000001.json", """
						{"_id": {"$oid": "aaaaaaaaaaaaaaaaaaaaaa02"}, "dataLong": {"$numberLong": "2"}}
						""");
		final Path fileTestPath = Files.createTempDirectory("backup_chunked_conflict_test")
				.resolve("test_chunk_conflict.tar.gz");
		BackupTestTools.writeMapToTarGz(data, fileTestPath);
		try {
			final BackupEngine engine = new BackupEngine(fileTestPath.getParent(), "test_chunk_conflict",
					EngineBackupType.JSON_EXTENDED);
			engine.setEnableStoreOrRestoreData(false);
			// The target collection is not empty: the first chunk must be rejected
			Assertions.assertThrows(IOException.class, () -> {
				engine.restoreFile(fileTestPath, "DataStoreWithUpdate");
			});
		} finally {
			BackupTestTools.deleteRecursive(fileTestPath.getParent());
		}
	}

	@Test
	public void testNonNumericDoubleUnderscoreSuffixIsNotAChunk() throws Exception {
		// A collection legitimately named with "__<letters>" must NOT be merged into another one
		final Map<String, String> data = Map.of("foo__bar.json", """
				{"_id": {"$oid": "bbbbbbbbbbbbbbbbbbbbbb01"}, "dataLong": {"$numberLong": "1"}}
				""");
		final Path fileTestPath = Files.createTempDirectory("backup_suffix_test").resolve("test_suffix.tar.gz");
		BackupTestTools.writeMapToTarGz(data, fileTestPath);
		try {
			final BackupEngine engine = new BackupEngine(fileTestPath.getParent(), "test_suffix",
					EngineBackupType.JSON_EXTENDED);
			engine.setEnableStoreOrRestoreData(false);
			// Filtering on "foo" must not match: the entry belongs to collection "foo__bar"
			final boolean resultFiltered = engine.restoreFile(fileTestPath, "foo");
			Assertions.assertTrue(resultFiltered);
			Assertions.assertEquals(0, countCollectionDocuments("foo"));
			Assertions.assertEquals(0, countCollectionDocuments("foo__bar"));
			// Filtering on the real name restores it as-is
			final boolean result = engine.restoreFile(fileTestPath, "foo__bar");
			Assertions.assertTrue(result);
			Assertions.assertEquals(1, countCollectionDocuments("foo__bar"));
		} finally {
			dropCollection("foo__bar");
			BackupTestTools.deleteRecursive(fileTestPath.getParent());
		}
	}

	private long countCollectionDocuments(final String collectionName) throws Exception {
		try (DataAccessConnectionContext ctx = new DataAccessConnectionContext()) {
			final DBAccessMongo dbMongo = ctx.get();
			return dbMongo.getInterface().getDatabase().getCollection(collectionName).countDocuments();
		}
	}

	private void dropCollection(final String collectionName) throws Exception {
		try (DataAccessConnectionContext ctx = new DataAccessConnectionContext()) {
			final DBAccessMongo dbMongo = ctx.get();
			dbMongo.getInterface().getDatabase().getCollection(collectionName).drop();
		}
	}

}
