Pagination
==========

Archidata supports offset-based pagination for REST endpoints that return a
list of items. Pagination metadata travels through HTTP headers
(`X-Total-Count` and the RFC 5988 `Link` header) so the body stays a plain
JSON list — consumers that ignore the headers see exactly the same payload
they would get from a non-paginated endpoint.

This page covers the resource-side API, the wire format, the client-side
helper, and the things this design intentionally does not do.


When to use it
--------------

Use `Pagination<T>` for any `@GET` that returns a list whose size can grow
past a hundred items in practice — typically time-series data,
audit/history collections, or user-facing lists with filters.

Don't use it for short, bounded lists (enums, configuration, the set of
users in a small team). The runtime cost is negligible but the API noise
isn't — paginated endpoints publish three headers and a richer client
contract.

If a paginated endpoint already exists for a resource and you can change
the limit / offset to reach what you need, prefer that to introducing a
second, non-paginated variant.


Server-side: writing a paginated endpoint
-----------------------------------------

```java
@Path("/foos/{entity: [a-z]{2,23}~[a-z]{2,23}}")
public class FoosResource {

    @GET
    @RolesAllowed({ "{entity}/USER" })
    @Operation(summary = "List foos in a time window")
    @ApiResponse(responseCode = "200", description = "Paginated foos")
    public Pagination<Foo> list(
            @PathParam("entity") final String entity,
            @QueryParam("from") final Date from,
            @QueryParam("to")   final Date to,
            @PaginationContext  final PaginationContext page) {
        final Condition filter = ConfigVariable
                .getEntityFilterWithEntity(entity)
                .and(Filters.gte("timestamp", from))
                .and(Filters.lt("timestamp", to));
        final List<Foo> items = DataAccess.gets(Foo.class,
                new Condition(filter),
                new OrderBy(new OrderItem("timestamp", Order.ASC)),
                page.toSkip(),
                page.toLimit());
        final long total = DataAccess.count(Foo.class, new Condition(filter));
        return new Pagination<>(items, total, page.offset(), page.limit());
    }
}
```

A few things are worth pointing out:

- **Return type.** `Pagination<Foo>` is detected at response-time by
  `PaginationResponseFilter`, which swaps the entity for `pagination.getItems()`
  and emits the metadata headers. The resource code never touches `Response`.
- **`@PaginationContext`.** Marks the `PaginationContext` parameter as the
  carrier of pagination input. Resolved at request-time by
  `PaginationContextValueProvider`, which reads `X-Pagination-Offset` /
  `X-Pagination-Limit`, falls back to query parameters of the same name
  (for HATEOAS-style navigation through `Link` URIs), then to the defaults
  declared on `PaginationContext` (offset 0, limit 50).
- **`Skip` / `Limit`.** `PaginationContext` exposes `toSkip()` /
  `toLimit()` helpers; the matching MongoDB driver calls are wired in
  `DBAccessMongo`, just like `Limit` already was.
- **`DataAccess.count(...)`.** The `total` must match the same condition as
  the `gets()` — pass the same `Condition` to both. Counting without the
  filter leaks an inflated total.
- **No transaction.** This is a `@GET`. Per the project's transactionality
  rules, `@GET` endpoints must not open a `TransactionContext`.


Enabling the feature
--------------------

Register `PaginationFeature` once on the application's `ResourceConfig`:

```java
final ResourceConfig config = new ResourceConfig()
    .register(PaginationFeature.class)
    // … your resources, filters, providers …
    ;
```

This single registration wires both the response filter (which transforms
`Pagination<T>` into headers + body) and the HK2 binding for
`@PaginationContext` parameter resolution.


Wire format
-----------

### Request

| Header | Type | Default | Meaning |
|---|---|--:|---|
| `X-Pagination-Offset` | long, ≥ 0 | `0` | Number of items to skip before the page |
| `X-Pagination-Limit`  | long, > 0 | `50` | Maximum number of items in the page |

Both values are also accepted as query parameters of the same name, so that
the URIs in `Link` headers stay navigable from a plain web client. Headers
take precedence when both are present.

Bad input (non-numeric, negative offset, non-positive limit) falls back
silently to the server default rather than returning a 4xx. The reasoning:
clients that build their own pagination input get a friendly server, and
clients that follow server-built navigation links can never trip a 4xx on
themselves.

### Response

```
HTTP/1.1 200 OK
Content-Type: application/json
X-Total-Count: 1234
Link: <https://api.example.com/foos?from=2026-01-01&offset=0&limit=50>; rel="first",
      <https://api.example.com/foos?from=2026-01-01&offset=50&limit=50>; rel="next",
      <https://api.example.com/foos?from=2026-01-01&offset=1200&limit=50>; rel="last"

[{"id": "…", "name": "…"}, {"id": "…", "name": "…"}, …]
```

- `X-Total-Count` reflects the number of items matching the request,
  ignoring pagination.
- `Link` follows RFC 5988 with relations `first`, `prev`, `next`, `last`.
  `prev` is omitted on the first page; `next` is omitted on the last page.
  Other query parameters of the request are preserved.
- The response body is a plain JSON array; there is no envelope.


Client-side: consuming a paginated endpoint
-------------------------------------------

The TypeScript generator emits a function returning `Promise<Pagination<T>>`
and routes it through the `RESTRequestPaginatedJson` helper bundled in
`rest-tools`:

```ts
import { listFoos } from "<generated-client>";

const page1 = await listFoos({
    restConfig,
    params: { entity: "neofarm~lisses" },
    queries: { from: "2026-01-01T00:00:00Z", to: "2026-02-01T00:00:00Z" },
    headers: { "X-Pagination-Offset": "0", "X-Pagination-Limit": "100" },
});

console.log(page1.items.length); // 100, typed Foo[]
console.log(page1.total);        // 1234
console.log(page1.hasNext);      // true
```

Direct use of the helper exposes an additional `page` argument that maps
to the request headers without the consumer having to construct them:

```ts
import { RESTRequestPaginatedJson } from "<bundle>/rest-tools";

const page = await RESTRequestPaginatedJson<Foo>(
    request,
    undefined,
    { offset: 100, limit: 25 }
);
```

`page.linkHeader` exposes the raw `Link` value when a client wants to drive
navigation entirely from rel="next" / rel="prev" URIs rather than from
offset arithmetic.


Sample
------

A minimal end-to-end usage lives under `src/test/.../externalRestApi/` as
`TestTypeScriptApiGenerationPagination` — it doubles as a regression
snapshot for the codegen and as a copy-paste-able example of a paginated
resource shape.


Limits and non-objectives
-------------------------

- **Offset-based only.** For very large collections (millions of rows
  changing during pagination) cursor-based pagination is more
  appropriate. It is not provided today; introducing it would mean a
  separate query option and a different `Link` relation set.
- **No cached total.** Each paginated call issues a Mongo `count`. The
  cost is bounded but non-zero — keep the predicate indexed.
- **Response filter is global.** Any resource method returning
  `Pagination<?>` is automatically processed; there is no per-resource
  opt-out. Conversely, returning a plain `List<T>` bypasses the
  feature entirely, so the global filter does not affect non-paginated
  endpoints.


Troubleshooting
---------------

| Symptom | Likely cause |
|---|---|
| Response body is `{ "items": [...], "total": N }` instead of a plain array | `PaginationResponseFilter` is not registered on the `ResourceConfig` — register `PaginationFeature` once at server start. |
| `X-Total-Count` is always equal to `items.length` | The resource constructed `Pagination<>(items, items.size(), ...)` instead of calling `DataAccess.count(...)`. The total must reflect the unpaginated query, not the page size. |
| The client TypeScript bundle does not know about `Pagination<Foo>` | `mvn exec:java@generate-api` was not re-run after introducing the paginated endpoint, or `pnpm build` was not re-run after that. |
| `hasNext` is always `false` despite there being more data | The browser / fetch client cannot read the `Link` header. If the API is consumed from a different origin, expose `Link` and `X-Total-Count` via the CORS configuration (`Access-Control-Expose-Headers`). |
| `@PaginationContext` parameter shows up in the generated TS function signature | The TS generator was built against an older archidata version that did not yet recognize the annotation. Rebuild archidata, then re-run `generate-api`. |
| Compile error on `import Pagination from "..."` in generated client | The `rest-tools.ts` shipped in the bundle is older than the API — re-run `pnpm install` against the rebuilt API library, and check that the version of `archidata` consumed by `back` matches the one that produced the API. |


See also
--------

- [Database Access](database_access.md) — `Skip` and `Limit` query options,
  `DataAccess.count(...)`.
- [TypeScript API Generation](typescript_api_generation.md) — overall
  pipeline; pagination is one branch of the generator.
