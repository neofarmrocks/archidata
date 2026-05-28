package test.atriasoft.archidata.pagination;

import java.net.URI;
import java.util.List;

import org.atriasoft.archidata.dataAccess.model.Pagination;
import org.atriasoft.archidata.pagination.LinkHeaderBuilder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TestLinkHeaderBuilder {

	private static final URI BASE = URI.create("https://api.example.com/foos");

	@Test
	void firstPageOmitsPrev() {
		final Pagination<String> page = new Pagination<>(List.of("a"), 100L, 0L, 25L);
		final String link = LinkHeaderBuilder.build(BASE, page);
		Assertions.assertFalse(link.contains("rel=\"prev\""), link);
		Assertions.assertTrue(link.contains("rel=\"first\""), link);
		Assertions.assertTrue(link.contains("rel=\"next\""), link);
		Assertions.assertTrue(link.contains("rel=\"last\""), link);
	}

	@Test
	void lastPageOmitsNext() {
		final Pagination<String> page = new Pagination<>(List.of("z"), 100L, 75L, 25L);
		final String link = LinkHeaderBuilder.build(BASE, page);
		Assertions.assertFalse(link.contains("rel=\"next\""), link);
		Assertions.assertTrue(link.contains("rel=\"prev\""), link);
		Assertions.assertTrue(link.contains("rel=\"first\""), link);
		Assertions.assertTrue(link.contains("rel=\"last\""), link);
	}

	@Test
	void middlePageCarriesAllFourRels() {
		final Pagination<String> page = new Pagination<>(List.of("m"), 100L, 50L, 25L);
		final String link = LinkHeaderBuilder.build(BASE, page);
		Assertions.assertTrue(link.contains("rel=\"first\""), link);
		Assertions.assertTrue(link.contains("rel=\"prev\""), link);
		Assertions.assertTrue(link.contains("rel=\"next\""), link);
		Assertions.assertTrue(link.contains("rel=\"last\""), link);
	}

	@Test
	void nextLinkPointsAtCorrectOffset() {
		// offset=50, limit=25 → next should be offset=75
		final Pagination<String> page = new Pagination<>(List.of("m"), 200L, 50L, 25L);
		final String link = LinkHeaderBuilder.build(BASE, page);
		Assertions.assertTrue(link.contains("X-Pagination-Offset=75"), link);
	}

	@Test
	void prevLinkClampsToZero() {
		// offset=10, limit=25 → prev cannot be -15, must clamp to 0
		final Pagination<String> page = new Pagination<>(List.of("m"), 200L, 10L, 25L);
		final String link = LinkHeaderBuilder.build(BASE, page);
		// Two zero-offset links: "first" and "prev", but importantly no negative offset.
		Assertions.assertFalse(link.contains("X-Pagination-Offset=-"), link);
	}

	@Test
	void preservesOtherQueryParams() {
		final URI withQuery = URI.create("https://api.example.com/foos?from=2026-01-01&to=2026-02-01");
		final Pagination<String> page = new Pagination<>(List.of("m"), 200L, 50L, 25L);
		final String link = LinkHeaderBuilder.build(withQuery, page);
		Assertions.assertTrue(link.contains("from=2026-01-01"), link);
		Assertions.assertTrue(link.contains("to=2026-02-01"), link);
	}

	@Test
	void emptyResultStillProducesFirstAndLastLinks() {
		final Pagination<String> page = new Pagination<>(List.of(), 0L, 0L, 25L);
		final String link = LinkHeaderBuilder.build(BASE, page);
		Assertions.assertTrue(link.contains("rel=\"first\""), link);
		Assertions.assertTrue(link.contains("rel=\"last\""), link);
		Assertions.assertFalse(link.contains("rel=\"prev\""), link);
		Assertions.assertFalse(link.contains("rel=\"next\""), link);
	}

	@Test
	void lastLinkPointsAtPageStartForExactMultiple() {
		// 100 items, page size 25 → last page starts at offset 75 (items 75..99)
		final Pagination<String> page = new Pagination<>(List.of("a"), 100L, 0L, 25L);
		final String link = LinkHeaderBuilder.build(BASE, page);
		Assertions.assertTrue(link.contains("X-Pagination-Offset=75>; rel=\"last\""), link);
	}

	@Test
	void lastLinkAlignsToPageBoundaryForNonMultiple() {
		// 103 items, page size 25 → last page starts at offset 100 (items 100..102)
		final Pagination<String> page = new Pagination<>(List.of("a"), 103L, 0L, 25L);
		final String link = LinkHeaderBuilder.build(BASE, page);
		Assertions.assertTrue(link.contains("X-Pagination-Offset=100>; rel=\"last\""), link);
	}

	@Test
	void lastLinkAtZeroWhenSinglePageFitsAll() {
		// 10 items, page size 25 → last page is the only page, offset 0
		final Pagination<String> page = new Pagination<>(List.of("a"), 10L, 0L, 25L);
		final String link = LinkHeaderBuilder.build(BASE, page);
		Assertions.assertTrue(link.contains("X-Pagination-Offset=0>; rel=\"last\""), link);
	}
}
