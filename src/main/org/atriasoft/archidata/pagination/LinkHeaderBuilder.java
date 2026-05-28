package org.atriasoft.archidata.pagination;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.atriasoft.archidata.dataAccess.model.Pagination;

import jakarta.ws.rs.core.UriBuilder;

/**
 * Builds the value of the HTTP {@code Link} header (RFC 5988) carrying the
 * pagination navigation links {@code first}, {@code prev}, {@code next} and
 * {@code last} for a {@link Pagination} response.
 *
 * <p>{@code prev} is omitted on the first page; {@code next} is omitted on the
 * last page. The other query parameters of the original request are preserved
 * so that filters such as {@code ?from=…&to=…} apply to every navigation link.
 */
public final class LinkHeaderBuilder {

	private LinkHeaderBuilder() {}

	/**
	 * Builds the {@code Link} header value for the given pagination slice.
	 *
	 * @param requestUri the original request URI (path + query)
	 * @param page       the pagination result whose links must be built
	 * @return the formatted {@code Link} header value (without the leading
	 *         {@code Link:} prefix); never {@code null}
	 */
	public static String build(final URI requestUri, final Pagination<?> page) {
		final long total = page.getTotal();
		final long limit = page.getLimit();
		final long offset = page.getOffset();
		final long lastOffset = computeLastOffset(total, limit);

		final List<String> links = new ArrayList<>(4);
		links.add(formatLink(requestUri, 0L, limit, "first"));
		if (page.hasPrev()) {
			final long prevOffset = Math.max(0L, offset - limit);
			links.add(formatLink(requestUri, prevOffset, limit, "prev"));
		}
		if (page.hasNext()) {
			final long nextOffset = offset + limit;
			links.add(formatLink(requestUri, nextOffset, limit, "next"));
		}
		links.add(formatLink(requestUri, lastOffset, limit, "last"));
		return String.join(", ", links);
	}

	/**
	 * Computes the offset of the last page in a (total, limit) layout.
	 *
	 * <p>Layout reminder: pages are 0-indexed by offset, the last page contains
	 * the items whose index is in {@code [lastOffset, total)}. When {@code total}
	 * is exactly a multiple of {@code limit}, the last full page starts at
	 * {@code total - limit}.
	 *
	 * @param total the total item count (>= 0)
	 * @param limit the page size (> 0)
	 * @return the offset of the last page; always {@code >= 0}
	 */
	static long computeLastOffset(final long total, final long limit) {
		if (total <= 0L) {
			return 0L;
		}
		final long fullPages = (total - 1L) / limit;
		return fullPages * limit;
	}

	private static String formatLink(final URI base, final long offset, final long limit, final String rel) {
		final URI uri = UriBuilder.fromUri(base).replaceQueryParam("X-Pagination-Offset", offset)
				.replaceQueryParam("X-Pagination-Limit", limit).build();
		return "<" + uri.toString() + ">; rel=\"" + rel + "\"";
	}
}
