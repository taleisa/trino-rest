# trino-rest

A [Trino](https://trino.io) connector that turns any REST API described by an OpenAPI spec into
queryable SQL tables — no custom mapping code required, just point it at a spec.

Deliberately **read-only**: there is no INSERT/UPDATE/DELETE path, and there never will be. Using
Trino to write into a system that's fronted by a REST API doesn't fit this project's purpose. See
[`docs/COMPARISON.md`](docs/COMPARISON.md) for the full reasoning against an existing alternative.

## What it can query

Three distinct table shapes, inferred automatically from the OpenAPI spec:

| Shape | SQL | Backed by |
|---|---|---|
| **GET** | `SELECT * FROM catalog.default.table` | Any `GET` endpoint returning JSON |
| **Filter-POST** | `SELECT * FROM catalog.default.table WHERE request_filter_x = 'y'` | A `POST` endpoint whose request body is a single JSON object - `WHERE` predicates on `request_filter_*` columns get pushed into the request |
| **Bulk lookup / index join** | `SELECT ... FROM other_table JOIN catalog.default.table ON ...` | A `POST` endpoint whose request body is a root JSON array of key-tuples - queried via an ordinary `JOIN`, using Trino's index-join mechanism (no special syntax) |

### Bulk lookup / index join, explained

Some APIs expose a bulk endpoint that takes a *list* of keys in one request and returns one
result per key - e.g. `POST /enrich` with body `[{"product_name": "Widget", "date": "2024-01-01"}, ...]`,
returning a matching enrichment record per item. This doesn't fit the filter-POST shape above: a
filter-POST request has exactly one value per field, resolved once from a `WHERE` clause, but a
bulk endpoint needs a whole *batch* of key-tuples per call, and those keys come from somewhere
else entirely - another table's rows, not a literal in this query.

So this connector maps a bulk endpoint to an ordinary SQL `JOIN` instead:

```sql
SELECT p.name, e.discount_pct
FROM some_catalog.default.products p
JOIN rest_catalog.default.enrich e
  ON p.name = e.request_filter_product_name
 AND p.date = e.request_filter_date
```

No special syntax - Trino's own query planner recognizes this as a candidate for an *index join*
(the same general-purpose SPI mechanism connectors like TPCH's reference implementation use) and
calls into this connector to resolve it. Under the hood, Trino feeds the probe side's rows
(`products`) through in bounded batches, and each batch becomes one `POST` call to the bulk
endpoint with that batch's keys - never the whole probe table loaded into memory at once, and
never more than one request in flight building the array for a given batch. The endpoint's
response has to echo the key fields back (`product_name`/`date` in the example above) so Trino
can correlate each result back to the probe row that produced it.

Two consequences worth knowing before using this: the join condition must reference this table's
`request_filter_*`-prefixed columns specifically (not any plain, same-named column that happens
to also exist from the response schema), and a bulk-lookup table can *only* be queried this way -
a plain `SELECT * FROM rest_catalog.default.enrich` with no `JOIN` has no required values to send
and will fail rather than silently returning nothing (though the current error message for that
case is generic, not yet specific to "this table needs a JOIN" - see `docs/TODOS.md`).

See [`docs/FLOWS.md`](docs/FLOWS.md)'s "bulk lookup / index join" section for the full mechanism
trace - which method fires when, how many times per query, and what Trino does internally with
the results.

Every response is parsed incrementally via a streaming JSON parser - a response is never
buffered in full, so response size doesn't translate into proportional memory use. See
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how that's implemented, and
[`docs/FLOWS.md`](docs/FLOWS.md) for a step-by-step trace of all three query paths, including
which internal method gets called when.

## Requirements

- Java 25
- Maven
- A running Trino instance (built and tested against Trino 481)

## Building

```bash
mvn clean package
```

Produces a `trino-plugin` artifact under `target/`. Run the test suite with:

```bash
mvn test
```

## Configuring a catalog

Create a catalog properties file (e.g. `etc/catalog/mycatalog.properties`) pointing at the
connector and the target API's OpenAPI spec:

```properties
connector.name=rest
rest.token=<bearer-token>
rest.baseUrl=https://api.example.com
rest.specUrl=https://api.example.com/openapi.json
```

`rest.token` is optional - omit it entirely for a target API that doesn't require authentication;
no `Authorization` header is sent at all in that case.

`rest.specUrl` and `rest.specPath` are mutually exclusive - use `rest.specPath` instead to load
the spec from a local file already available to the Trino process rather than fetching it over
HTTP:

```properties
rest.specPath=/etc/trino/specs/example-openapi.json
```

Deploy the built plugin and the properties file to Trino, then restart (or add the catalog before
first boot, depending on your `CATALOG_MANAGEMENT` mode) - tables show up automatically under
`mycatalog.default`, one per discovered endpoint.

## More detail

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) - class-by-class structure and the query
  lifecycle, with diagrams.
- [`docs/FLOWS.md`](docs/FLOWS.md) - all three query paths traced end-to-end, method by method.
- [`docs/COMPARISON.md`](docs/COMPARISON.md) - a detailed, verified comparison against
  `nineinchnick/trino-openapi`, and why this project exists as its own connector.
- [`docs/TODOS.md`](docs/TODOS.md) - known gaps and planned work.
