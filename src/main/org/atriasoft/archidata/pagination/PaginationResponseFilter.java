package org.atriasoft.archidata.pagination;

import java.io.IOException;
import java.net.URI;

import org.atriasoft.archidata.dataAccess.model.Pagination;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Global JAX-RS response filter that transforms a {@link Pagination} response
 * entity into a flat list body plus the matching pagination HTTP headers.
 *
 * <p>When a resource method returns a {@link Pagination}, the filter:
 * <ul>
 *   <li>replaces the response entity by {@link Pagination#getItems()} so that
 *       the body is the plain list of items (and consumers do not need to know
 *       about the wrapper);</li>
 *   <li>adds {@code X-Total-Count} carrying {@link Pagination#getTotal()};</li>
 *   <li>adds {@code Link} (RFC 5988) built by {@link LinkHeaderBuilder} with
 *       {@code first} / {@code prev} / {@code next} / {@code last} relations.</li>
 * </ul>
 *
 * <p>Responses whose entity is not a {@link Pagination} are passed through
 * unchanged — endpoints returning {@code List<T>} keep their existing
 * behaviour.
 */
@Provider
public class PaginationResponseFilter implements ContainerResponseFilter {

	/** HTTP header carrying the total number of items matching the query. */
	public static final String TOTAL_COUNT_HEADER = "X-Total-Count";
	/** HTTP header carrying the RFC 5988 navigation links. */
	public static final String LINK_HEADER = "Link";

	/** Default constructor. */
	public PaginationResponseFilter() {
		// default constructor
	}

	@Override
	public void filter(final ContainerRequestContext requestContext, final ContainerResponseContext responseContext)
			throws IOException {
		final Object entity = responseContext.getEntity();
		if (!(entity instanceof final Pagination<?> page)) {
			return;
		}
		final URI requestUri = requestContext.getUriInfo().getRequestUri();
		responseContext.setEntity(page.getItems());
		responseContext.getHeaders().putSingle(TOTAL_COUNT_HEADER, page.getTotal());
		responseContext.getHeaders().putSingle(LINK_HEADER, LinkHeaderBuilder.build(requestUri, page));
	}
}
