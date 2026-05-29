package org.atriasoft.archidata.pagination;

import java.util.function.Function;

import org.atriasoft.archidata.dataAccess.model.PaginationContext;
import org.glassfish.jersey.server.ContainerRequest;
import org.glassfish.jersey.server.model.Parameter;
import org.glassfish.jersey.server.spi.internal.ValueParamProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Singleton;

/**
 * Jersey {@link ValueParamProvider} that binds an
 * {@link org.atriasoft.archidata.annotation.method.PaginationContext}-annotated
 * parameter of type {@link PaginationContext} to a value built from the
 * {@code X-Pagination-Offset} / {@code X-Pagination-Limit} HTTP headers.
 *
 * <p>Resolution order for each value:
 * <ol>
 *   <li>HTTP header ({@code X-Pagination-Offset} / {@code X-Pagination-Limit});</li>
 *   <li>query parameter of the same name (fallback for hypermedia navigation
 *       via {@code Link} header URIs, where headers cannot be carried);</li>
 *   <li>server default ({@link PaginationContext#DEFAULT_OFFSET} /
 *       {@link PaginationContext#DEFAULT_LIMIT}).</li>
 * </ol>
 *
 * <p>Negative or non-numeric inputs fall back to the default for the same
 * value (a 400 would surprise clients following navigation links built by the
 * server itself; bad clients still get a sane page).
 */
@Singleton
public class PaginationContextValueProvider implements ValueParamProvider {

	private static final Logger LOGGER = LoggerFactory.getLogger(PaginationContextValueProvider.class);

	/** HTTP header carrying the requested offset. */
	public static final String OFFSET_HEADER = "X-Pagination-Offset";
	/** HTTP header carrying the requested limit. */
	public static final String LIMIT_HEADER = "X-Pagination-Limit";

	/** Default constructor (required for HK2 injection). */
	public PaginationContextValueProvider() {
		// HK2 will instantiate via no-arg constructor.
	}

	@Override
	public Function<ContainerRequest, ?> getValueProvider(final Parameter parameter) {
		if (parameter.getAnnotation(org.atriasoft.archidata.annotation.method.PaginationContext.class) == null) {
			return null;
		}
		if (!PaginationContext.class.equals(parameter.getRawType())) {
			LOGGER.warn("@PaginationContext applied on parameter of unsupported type: {}", parameter.getRawType());
			return null;
		}
		return PaginationContextValueProvider::resolve;
	}

	@Override
	public PriorityType getPriority() {
		return Priority.NORMAL;
	}

	private static PaginationContext resolve(final ContainerRequest request) {
		final long offset = readNonNegativeLong(request, OFFSET_HEADER, PaginationContext.DEFAULT_OFFSET);
		final long limit = readPositiveLong(request, LIMIT_HEADER, PaginationContext.DEFAULT_LIMIT);
		return new PaginationContext(offset, limit);
	}

	private static long readNonNegativeLong(final ContainerRequest request, final String name, final long fallback) {
		final String raw = readRawValue(request, name);
		if (raw == null) {
			return fallback;
		}
		try {
			final long parsed = Long.parseLong(raw);
			return parsed >= 0L ? parsed : fallback;
		} catch (final NumberFormatException ex) {
			LOGGER.warn("Ignoring non-numeric {} value '{}', falling back to {}", name, raw, fallback);
			return fallback;
		}
	}

	private static long readPositiveLong(final ContainerRequest request, final String name, final long fallback) {
		final String raw = readRawValue(request, name);
		if (raw == null) {
			return fallback;
		}
		try {
			final long parsed = Long.parseLong(raw);
			if (parsed <= 0L) {
				return fallback;
			}
			// Clamp oversized page requests so a single call can never pull an unbounded page.
			return Math.min(parsed, PaginationContext.MAX_LIMIT);
		} catch (final NumberFormatException ex) {
			LOGGER.warn("Ignoring non-numeric {} value '{}', falling back to {}", name, raw, fallback);
			return fallback;
		}
	}

	private static String readRawValue(final ContainerRequest request, final String name) {
		final String header = request.getHeaderString(name);
		if (header != null && !header.isBlank()) {
			return header;
		}
		final var queryParams = request.getUriInfo().getQueryParameters();
		if (queryParams != null) {
			final String fromQuery = queryParams.getFirst(name);
			if (fromQuery != null && !fromQuery.isBlank()) {
				return fromQuery;
			}
		}
		return null;
	}
}
