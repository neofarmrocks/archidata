package test.atriasoft.archidata.pagination;

import org.atriasoft.archidata.dataAccess.model.PaginationContext;
import org.atriasoft.archidata.dataAccess.options.Limit;
import org.atriasoft.archidata.dataAccess.options.Skip;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestPaginationContext {

	@Test
	void rejectsNegativeOffset() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PaginationContext(-1L, 10L));
	}

	@Test
	void rejectsZeroLimit() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PaginationContext(0L, 0L));
	}

	@Test
	void rejectsNegativeLimit() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PaginationContext(0L, -5L));
	}

	@Test
	void defaultsReturnsZeroOffsetAndFiftyLimit() {
		final PaginationContext page = PaginationContext.defaults();
		Assertions.assertEquals(0L, page.offset());
		Assertions.assertEquals(50L, page.limit());
	}

	@Test
	void toSkipCarriesOffsetValue() {
		final Skip skip = new PaginationContext(123L, 25L).toSkip();
		Assertions.assertEquals(123L, skip.getValue());
	}

	@Test
	void toLimitCarriesLimitValue() {
		final Limit limit = new PaginationContext(0L, 25L).toLimit();
		Assertions.assertEquals(25L, limit.getValue());
	}
}
