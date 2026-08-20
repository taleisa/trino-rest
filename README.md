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
  ON p.name = e.product_name
 AND p.date = e.date
```

No special syntax - Trino's own query planner recognizes this as a candidate for an *index join*
(the same general-purpose SPI mechanism connectors like TPCH's reference implementation use) and
calls into this connector to resolve it. Under the hood, Trino feeds the probe side's rows
(`products`) through in bounded batches, and each batch becomes one `POST` call to the bulk
endpoint with that batch's keys - never the whole probe table loaded into memory at once, and
never more than one request in flight building the array for a given batch. The endpoint's
response has to echo the key fields back so Trino can correlate each result back to the probe row
that produced it.

Two consequences worth knowing before using this:

- **A join key field must appear under a matching name (case-insensitively) in both the request
  and the response.** The join condition above works because the endpoint's response happens to
  echo the key back as `product_name`/`date` - the same names (ignoring case) the request uses.
  This connector links a request field to its response counterpart once, at spec-parse time, by
  matching names case-insensitively (e.g. request `ip`, response `IP` still link up fine) - that's
  the only signal available, since OpenAPI has no way to declare "this response field is the same
  concept as that differently-named request field." **If an endpoint's request and response use
  genuinely different names for the same key** (e.g. the request takes `name` but the response
  echoes the looked-up value back as `ip`, not `name`), **no link can be established, and a JOIN
  on that field is not supported** - it will not error, but the result will always be empty,
  since there is nothing to correlate results back to probe rows with. Such a field stays exposed
  under a `request_filter_*`-prefixed name, signaling it's write-only (usable to build the
  request, not readable, and not usable as a join key in practice).
- **A bulk-lookup table can only be queried via `JOIN`** - a plain `SELECT * FROM
  rest_catalog.default.enrich` with no `JOIN` has no required values to send and will fail rather
  than silently returning nothing (though the current error message for that case is generic, not
  yet specific to "this table needs a JOIN" - see `docs/TODOS.md`).

See [`docs/FLOWS.md`](docs/FLOWS.md)'s "bulk lookup / index join" section for the full mechanism
trace - which method fires when, how many times per query, and what Trino does internally with
the results.

For the GET and filter-POST shapes, a response is parsed incrementally via a streaming JSON
parser - never buffered in full, so response size doesn't translate into proportional memory
use. The bulk-lookup/index-join shape doesn't do this yet: each batch's response is parsed into
an in-memory tree before being read, so its memory use scales with batch size (bounded by
Trino's own per-batch chunking, not unbounded, but not the same guarantee as the other two
shapes - see `docs/TODOS.md`). See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for how the
streaming path is implemented, and [`docs/FLOWS.md`](docs/FLOWS.md) for a step-by-step trace of
all three query paths, including which internal method gets called when.

## Requirements

- Java 25
- Maven
- A running Trino instance (built and tested against Trino 481)

## Building

```bash
mvn clean package
```

Produces several things under `target/`, but only one matters for deployment:
`target/trino-rest-<version>.zip` - the connector's own jar plus every runtime dependency jar,
already laid out as the plugin directory Trino expects. (`target/trino-rest-<version>.jar` is
just this project's own classes with no dependencies - not usable on its own.) Run the test suite
with:

```bash
mvn test
```

## Configuring a catalog

Create a catalog properties file (e.g. `mycatalog.properties`) pointing at the connector and the
target API's OpenAPI spec:

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

## Deploying

Every Trino connector needs two things in place: the plugin's jars, and the catalog properties
file from above. Plugins are only loaded when the Trino process starts, so a restart is required
after adding this connector for the first time (and again any time the jar changes), regardless
of `CATALOG_MANAGEMENT` mode.

**Docker-based Trino** (e.g. the official `trinodb/trino` image):

```bash
# unzip the plugin build into its own directory, then copy the whole thing in
unzip target/trino-rest-<version>.zip -d /tmp/trino-rest-plugin
docker cp /tmp/trino-rest-plugin/trino-rest-<version> <container>:/usr/lib/trino/plugin/rest

docker cp mycatalog.properties <container>:/etc/trino/catalog/mycatalog.properties

docker restart <container>
```

**Non-Docker installs**: unzip `target/trino-rest-<version>.zip` into a `rest/` directory under
wherever `plugin.dir` points (commonly `/usr/lib/trino/plugin/` or `<install-root>/plugin/`), put
the catalog properties file under `<install-root>/etc/catalog/`, then restart the Trino service.

Either way, confirm it picked up correctly before troubleshooting further:

```sql
SHOW CATALOGS;                     -- should list your catalog name
SHOW TABLES FROM mycatalog.default; -- one row per discovered endpoint
```

## More detail

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) - class-by-class structure and the query
  lifecycle, with diagrams.
- [`docs/FLOWS.md`](docs/FLOWS.md) - all three query paths traced end-to-end, method by method.
- [`docs/COMPARISON.md`](docs/COMPARISON.md) - a detailed, verified comparison against
  `nineinchnick/trino-openapi`, and why this project exists as its own connector.
- [`docs/TODOS.md`](docs/TODOS.md) - known gaps and planned work.
- [`docs/release-notes/`](docs/release-notes/) - what changed release to release, one file per
  version.

## License

[Apache License 2.0](LICENSE).
