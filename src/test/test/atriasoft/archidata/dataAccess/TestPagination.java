package test.atriasoft.archidata.dataAccess;

import java.util.List;

import org.atriasoft.archidata.dataAccess.model.Pagination;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestPagination {

	@Test
	void rejectsNullItems() {
		Assertions.assertThrows(NullPointerException.class, () -> new Pagination<>(null, 0L, 0L, 10L));
	}

	@Test
	void rejectsNegativeTotal() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new Pagination<>(List.of(), -1L, 0L, 10L));
	}

	@Test
	void rejectsNegativeOffset() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new Pagination<>(List.of(), 0L, -1L, 10L));
	}

	@Test
	void rejectsZeroLimit() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new Pagination<>(List.of(), 0L, 0L, 0L));
	}

	@Test
	void rejectsNegativeLimit() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new Pagination<>(List.of(), 0L, 0L, -5L));
	}

	@Test
	void hasNextWhenMoreItemsRemain() {
		final Pagination<String> page = new Pagination<>(List.of("a", "b"), 100L, 50L, 25L);
		Assertions.assertTrue(page.hasNext());
	}

	@Test
	void noNextWhenExactlyOnLastBoundary() {
		// offset 75 + limit 25 = 100 = total → no more items past this page
		final Pagination<String> page = new Pagination<>(List.of("a"), 100L, 75L, 25L);
		Assertions.assertFalse(page.hasNext());
	}

	@Test
	void noNextWhenPastTotal() {
		// offset 80 + limit 25 = 105 > total → already a partial / empty page
		final Pagination<String> page = new Pagination<>(List.of(), 100L, 80L, 25L);
		Assertions.assertFalse(page.hasNext());
	}

	@Test
	void hasPrevWhenOffsetPositive() {
		final Pagination<String> page = new Pagination<>(List.of("a"), 100L, 1L, 10L);
		Assertions.assertTrue(page.hasPrev());
	}

	@Test
	void noPrevAtZeroOffset() {
		final Pagination<String> page = new Pagination<>(List.of("a"), 100L, 0L, 10L);
		Assertions.assertFalse(page.hasPrev());
	}

	@Test
	void emptyResultSet() {
		// total=0 → no items anywhere, no next, no prev
		final Pagination<String> page = new Pagination<>(List.of(), 0L, 0L, 10L);
		Assertions.assertEquals(0, page.getItems().size());
		Assertions.assertFalse(page.hasNext());
		Assertions.assertFalse(page.hasPrev());
	}

	@Test
	void offsetBeyondTotalKeepsPrev() {
		// User asked offset=200 on a 100-doc collection → empty slice but
		// navigation back to earlier pages still makes sense.
		final Pagination<String> page = new Pagination<>(List.of(), 100L, 200L, 25L);
		Assertions.assertFalse(page.hasNext());
		Assertions.assertTrue(page.hasPrev());
	}

	@Test
	void exposesAccessors() {
		final List<String> items = List.of("a", "b", "c");
		final Pagination<String> page = new Pagination<>(items, 50L, 10L, 3L);
		Assertions.assertSame(items, page.getItems());
		Assertions.assertEquals(50L, page.getTotal());
		Assertions.assertEquals(10L, page.getOffset());
		Assertions.assertEquals(3L, page.getLimit());
	}
}
