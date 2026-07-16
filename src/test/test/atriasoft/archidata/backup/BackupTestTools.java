package test.atriasoft.archidata.backup;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

/** Shared helpers for the backup test classes: tar.gz inspection/creation, NDJSON counting, cleanup. */
final class BackupTestTools {

	private BackupTestTools() {
		// Utility class
	}

	/** Extract every regular file of a .tar.gz archive into a map of entry name to textual content. */
	static Map<String, String> extractTarGzToMap(final Path inputPath) throws IOException {
		final Map<String, String> result = new HashMap<>();
		try (InputStream fileIn = Files.newInputStream(inputPath);
				BufferedInputStream bufferedIn = new BufferedInputStream(fileIn);
				GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(bufferedIn);
				TarArchiveInputStream tarIn = new TarArchiveInputStream(gzipIn)) {
			TarArchiveEntry entry;
			while ((entry = tarIn.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				final ByteArrayOutputStream out = new ByteArrayOutputStream();
				tarIn.transferTo(out);
				result.put(entry.getName(), out.toString(StandardCharsets.UTF_8));
			}
		}
		return result;
	}

	/** Write a map of entry name to textual content as a .tar.gz archive. */
	static void writeMapToTarGz(final Map<String, String> data, final Path output) throws IOException {
		try (OutputStream fileOut = Files.newOutputStream(output);
				GzipCompressorOutputStream gzipOut = new GzipCompressorOutputStream(fileOut);
				TarArchiveOutputStream tarOut = new TarArchiveOutputStream(gzipOut)) {
			tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
			for (final Map.Entry<String, String> entry : data.entrySet()) {
				final byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
				final TarArchiveEntry tarEntry = new TarArchiveEntry(entry.getKey());
				tarEntry.setSize(content.length);
				tarOut.putArchiveEntry(tarEntry);
				tarOut.write(content);
				tarOut.closeArchiveEntry();
			}
		}
	}

	/** Count the non-blank NDJSON lines of an archive entry content ({@code null} counts as 0). */
	static int countLines(final String content) {
		if (content == null || content.isEmpty()) {
			return 0;
		}
		return (int) content.lines().filter(line -> !line.isBlank()).count();
	}

	/** Recursively delete a directory, ignoring individual deletion errors. */
	static void deleteRecursive(final Path dir) throws IOException {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (var stream = Files.walk(dir)) {
			stream.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (final IOException e) {
					// ignore cleanup errors
				}
			});
		}
	}
}
