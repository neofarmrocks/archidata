package org.atriasoft.archidata.filter;

import java.io.IOException;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * JAX-RS response filter that adds Cross-Origin Resource Sharing (CORS) headers to all responses.
 *
 * <p>
 * This filter allows all origins, common HTTP methods, and standard headers
 * required for REST API communication.
 * </p>
 */
@Provider
public class CORSFilter implements ContainerResponseFilter {

	/**
	 * Adds CORS headers to the response.
	 *
	 * @param request the container request context
	 * @param response the container response context to which CORS headers are added
	 * @throws IOException if an I/O error occurs during filtering
	 */
	@Override
	public void filter(final ContainerRequestContext request, final ContainerResponseContext response)
			throws IOException {
		// System.err.println("filter cors ..." + request.toString());

		response.getHeaders().add("Access-Control-Allow-Origin", "*");
		response.getHeaders().add("Access-Control-Allow-Range", "bytes");
		// Expose pagination metadata so cross-origin clients can read the total
		// count and the RFC 5988 navigation links emitted by PaginationResponseFilter.
		response.getHeaders().add("access-control-expose-headers", "range, X-Total-Count, Link");
		response.getHeaders().add("Access-Control-Allow-Headers",
				"Origin, content-type, Content-type, Accept, Authorization, mime-type, filename, Range, X-Pagination-Offset, X-Pagination-Limit");
		response.getHeaders().add("Access-Control-Allow-Credentials", "true");
		response.getHeaders().add("Access-Control-Allow-Methods",
				"GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD, ARCHIVE, RESTORE, CALL");
	}
}
