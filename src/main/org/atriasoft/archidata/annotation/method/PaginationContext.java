package org.atriasoft.archidata.annotation.method;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource method parameter as the carrier of pagination metadata.
 *
 * <p>The parameter type must be
 * {@link org.atriasoft.archidata.dataAccess.model.PaginationContext} (same simple
 * name, different package). At request time, a Jersey {@code ValueParamProvider}
 * reads the headers {@code X-Pagination-Offset} and {@code X-Pagination-Limit}
 * and binds the values into the parameter. Missing headers fall back to the
 * server defaults declared in
 * {@link org.atriasoft.archidata.dataAccess.model.PaginationContext}.
 *
 * <p>Typical usage:
 * <pre>{@code
 * @GET
 * public Pagination<Foo> list(@PaginationContext PaginationContext page) {
 *     final var items = DataAccess.gets(Foo.class,
 *         new Skip(page.offset()), new Limit(page.limit()));
 *     final long total = DataAccess.count(Foo.class);
 *     return new Pagination<>(items, total, page.offset(), page.limit());
 * }
 * }</pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PaginationContext {}
