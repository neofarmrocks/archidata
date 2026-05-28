package org.atriasoft.archidata.dataAccess.model;

import java.util.List;
import java.util.Objects;

/**
 * Immutable transport for a paginated slice of items together with its position
 * metadata.
 *
 * <p>This type is the return type of paginated REST endpoints. A response filter
 * detects it, writes {@link #getItems()} as the response body, and emits
 * {@code X-Total-Count} and an RFC 5988 {@code Link} header carrying the
 * navigation links (first / prev / next / last). The transport type itself is
 * never serialized to JSON.
 *
 * <p>Not a Bean: fields are {@code final} and accessors return values without
 * mutation. Construct once with the slice you have read, the total count,
 * and the offset/limit used to obtain that slice.
 *
 * @param <T> the type of items in the page
 */
public class Pagination<T> {

	private final List<T> items;
	private final long total;
	private final long offset;
	private final long limit;

	/**
	 * Constructs a Pagination wrapping the given slice.
	 *
	 * @param items the items in this page (never {@code null}; empty if
	 *              {@code offset >= total})
	 * @param total the total number of items matching the query, ignoring
	 *              pagination (must be {@code >= 0})
	 * @param offset the offset that was applied to obtain this slice (must be
	 *               {@code >= 0})
	 * @param limit the limit that was applied to obtain this slice (must be
	 *              {@code > 0})
	 * @throws IllegalArgumentException if any argument is out of range
	 * @throws NullPointerException if {@code items} is {@code null}
	 */
	public Pagination(final List<T> items, final long total, final long offset, final long limit) {
		Objects.requireNonNull(items, "items");
		if (total < 0L) {
			throw new IllegalArgumentException("total must be >= 0, got: " + total);
		}
		if (offset < 0L) {
			throw new IllegalArgumentException("offset must be >= 0, got: " + offset);
		}
		if (limit <= 0L) {
			throw new IllegalArgumentException("limit must be > 0, got: " + limit);
		}
		this.items = items;
		this.total = total;
		this.offset = offset;
		this.limit = limit;
	}

	/**
	 * Returns the items in this page.
	 *
	 * @return the items (never {@code null})
	 */
	public List<T> getItems() {
		return this.items;
	}

	/**
	 * Returns the total number of items matching the query, ignoring pagination.
	 *
	 * @return the total
	 */
	public long getTotal() {
		return this.total;
	}

	/**
	 * Returns the offset used to obtain this slice.
	 *
	 * @return the offset
	 */
	public long getOffset() {
		return this.offset;
	}

	/**
	 * Returns the limit used to obtain this slice.
	 *
	 * @return the limit
	 */
	public long getLimit() {
		return this.limit;
	}

	/**
	 * Indicates whether more items exist past this slice.
	 *
	 * @return {@code true} when {@code offset + limit < total}
	 */
	public boolean hasNext() {
		return this.offset + this.limit < this.total;
	}

	/**
	 * Indicates whether items exist before this slice.
	 *
	 * @return {@code true} when {@code offset > 0}
	 */
	public boolean hasPrev() {
		return this.offset > 0L;
	}
}
