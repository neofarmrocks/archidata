package org.atriasoft.archidata.dataAccess.model;

import org.atriasoft.archidata.dataAccess.options.Limit;
import org.atriasoft.archidata.dataAccess.options.Skip;

/**
 * Pagination request input as resolved from HTTP headers
 * ({@code X-Pagination-Offset}, {@code X-Pagination-Limit}).
 *
 * <p>Carries the offset and limit a client asked for, with simple helpers to
 * project to the matching {@link Skip} / {@link Limit} query options. Bound to
 * a resource parameter via
 * {@link org.atriasoft.archidata.annotation.method.PaginationContext}.
 *
 * @param offset zero-based skip count; must be {@code >= 0}
 * @param limit  maximum items in the returned slice; must be {@code > 0}
 */
public record PaginationContext(
		long offset,
		long limit) {

	/** Server-side default offset applied when {@code X-Pagination-Offset} is absent. */
	public static final long DEFAULT_OFFSET = 0L;

	/** Server-side default limit applied when {@code X-Pagination-Limit} is absent. */
	public static final long DEFAULT_LIMIT = 50L;

	/**
	 * Server-side ceiling on the requested page size. A client asking for more
	 * than this is clamped down to it, so a single request can never pull an
	 * unbounded page. Resolved in {@code PaginationContextValueProvider}.
	 */
	public static final long MAX_LIMIT = 500L;

	/**
	 * Compact constructor enforcing the same range constraints as
	 * {@link Skip} and {@link Limit}.
	 */
	public PaginationContext {
		if (offset < 0L) {
			throw new IllegalArgumentException("Pagination offset must be >= 0, got: " + offset);
		}
		if (limit <= 0L) {
			throw new IllegalArgumentException("Pagination limit must be > 0, got: " + limit);
		}
	}

	/**
	 * Builds a {@link PaginationContext} from the server defaults.
	 *
	 * @return a context with {@link #DEFAULT_OFFSET} and {@link #DEFAULT_LIMIT}
	 */
	public static PaginationContext defaults() {
		return new PaginationContext(DEFAULT_OFFSET, DEFAULT_LIMIT);
	}

	/**
	 * Projects this context to a {@link Skip} option.
	 *
	 * @return a {@link Skip} carrying {@link #offset()}
	 */
	public Skip toSkip() {
		return new Skip(this.offset);
	}

	/**
	 * Projects this context to a {@link Limit} option.
	 *
	 * @return a {@link Limit} carrying {@link #limit()}
	 */
	public Limit toLimit() {
		return new Limit(this.limit);
	}
}
