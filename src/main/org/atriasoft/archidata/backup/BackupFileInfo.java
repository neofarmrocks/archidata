package org.atriasoft.archidata.backup;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Metadata for a backup file parsed from its filename.
 * <p>
 * {@code delta} marks an incremental backup (sequence ending with {@code _delta}): it only
 * contains the documents modified since the full backup it is based on, and is superseded by
 * any newer full backup.
 */
record BackupFileInfo(
		Path path,
		String sequence,
		LocalDate date,
		boolean partial,
		boolean delta)
		implements Comparable<BackupFileInfo> {

	@Override
	public int compareTo(final BackupFileInfo other) {
		final int dateCompare = this.date.compareTo(other.date);
		if (dateCompare != 0) {
			return dateCompare;
		}
		return this.sequence.compareTo(other.sequence);
	}
}
