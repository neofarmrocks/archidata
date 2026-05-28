package org.atriasoft.archidata.pagination;

import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.spi.internal.ValueParamProvider;

import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;

/**
 * Jersey {@link Feature} that wires the pagination machinery:
 * <ul>
 *   <li>{@link PaginationContextValueProvider} so that
 *       {@link org.atriasoft.archidata.annotation.method.PaginationContext}-annotated
 *       parameters get resolved from the request headers / query parameters;</li>
 *   <li>{@link PaginationResponseFilter} so that resource methods returning
 *       {@link org.atriasoft.archidata.dataAccess.model.Pagination} have their
 *       body replaced by the plain items list and their pagination metadata
 *       emitted as {@code X-Total-Count} and {@code Link} HTTP headers.</li>
 * </ul>
 *
 * <p>Register this feature on the application's {@code ResourceConfig} (or
 * equivalent JAX-RS {@link jakarta.ws.rs.core.Application}) once per server.
 */
public class PaginationFeature implements Feature {

	/** Default constructor. */
	public PaginationFeature() {
		// default constructor
	}

	@Override
	public boolean configure(final FeatureContext context) {
		context.register(PaginationResponseFilter.class);
		context.register(new PaginationBinder());
		return true;
	}

	private static class PaginationBinder extends AbstractBinder {
		@Override
		protected void configure() {
			bind(PaginationContextValueProvider.class).to(ValueParamProvider.class).in(Singleton.class);
		}
	}
}
