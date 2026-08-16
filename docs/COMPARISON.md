# Comparison with nineinchnick/trino-openapi

[nineinchnick/trino-openapi](https://github.com/nineinchnick/trino-openapi) is an existing,
published, community-maintained Trino connector for OpenAPI-described REST APIs - the closest
real-world prior art to this project. This document records what we found comparing the two,
including running it against our own test server, so the reasoning for building a separate
connector is traceable rather than asserted.

Findings below are marked as either **verified live** (we ran it and observed the behavior) or
**verified from source** (confirmed by reading their code, not by running it) or **inferred**
(reasoned from source, not confirmed by execution). Only the last category should be treated as
tentative.

## POST-filter correctness parity

`SELECT * FROM rest.default.product_search WHERE request_filter_category = 'furniture' AND
request_filter_ids IN (1,3)` (ours) and `SELECT * FROM "openapi-lib".default.product_search WHERE
category = 'furniture' AND ids = ARRAY[1,3]` (theirs, their `ARRAY`-equality equivalent) both
return the same correct single row. Both took roughly the same wall time (13.6s vs 10.76s), but
that's *not* a meaningful connector-performance signal here - both queries return only ~50-75
bytes, and both show a `Scheduled time` almost equal to total time against nearly-zero `CPU time`.
The dominant cost for this query is the test server itself: `search_products()` in
`test/test-server/server.py` deliberately reloads and linearly scans all ~13M records in
`products.json` fresh on every single request (a tradeoff made earlier for near-zero idle memory,
see `load_bytes`'s docstring), which any connector calling this endpoint would pay equally. The
~3s gap between the two runs is ordinary run-to-run variance for that scan, not evidence either
connector is faster here. *(verified live)*

## Where it's ahead of us

- **Broader feature surface.** Path parameter support, query parameter pushdown, pagination
  (page-number style), five authentication methods (none, HTTP basic/bearer, API key, OAuth
  client-credentials) - all things this connector doesn't have. *(verified from source)*
- **Multi-value (`IN`) predicates actually work**, including for POST body fields. Rather than
  rejecting a scalar filter with more than one resolved value (what we do), it computes the
  cartesian product across every multi-valued column and generates one split - one real HTTP
  request - per combination, merging the results. Genuinely more capable for that specific case,
  at the cost of a request-count explosion when multiple `IN` clauses combine (`IN` of 3 values
  AND `IN` of 2 values = 6 requests, not 2). *(verified from source and live: `WHERE category IN
  ('furniture','test')` did fire multiple requests)*
- **Array-typed filter fields map to genuine Trino `ARRAY` columns**, not a flattened scalar
  column with `IN` support the way we do it. More type-faithful to the source schema, and can
  express element order and an explicit empty array - both of which our `IN`-based, order-blind
  `Domain` mechanism cannot represent at all. Less ergonomic for the common "match any of these
  IDs" case (`WHERE ids = ARRAY[1,3]` vs. our plain `WHERE request_filter_ids IN (1,3)`), but
  more expressive for order-sensitive fields. *(verified live)*
- **Handles request/response field name collisions explicitly** - a request field with the same
  name as a response field gets suffixed `_req` rather than silently colliding. We get the same
  outcome, but only because every filter column is unconditionally prefixed `request_filter_*`,
  not because of any dedicated collision-handling logic. *(verified from source)*
- **Full CRUD mapping** - INSERT/UPDATE/DELETE map to POST/PUT/DELETE via
  `ConnectorPageSink`/`beginInsert`, a real write path we don't have at all. (See "Why we're
  building our own" below for why this isn't actually a gap we want closed.)

## Limitations found

- **Does not run on our target Trino version.** Pinned to Trino SPI 479, in both its latest
  release (v1.85) and current unreleased `main` branch. Loading it against Trino 481 crashes with
  `NoSuchMethodError: 'String ColumnMetadata.getComment()'` - a binary-incompatible SPI change
  between those versions. Because Trino's static catalog manager fails the *entire coordinator*
  if any one catalog fails to load, this took down a working Trino instance (including our own,
  unrelated catalogs) rather than just failing to register its own catalog. *(verified live - we
  hit this directly)*
- **Buffers the entire HTTP response in memory rather than streaming it.**
  `JsonResponseHandler` reads the full response into one `String`
  (`CharStreams.toString(new InputStreamReader(...))`) and then parses that into a complete
  in-memory `JsonNode` tree (`OBJECT_MAPPER.readTree(result)`). Against a ~1GB response from our
  own test server, this drove Trino's memory usage past 12GB. This is exactly the failure mode
  `RestRecordCursor` was deliberately redesigned to avoid (see the "stream input" work in git
  history) - a single eager fetch, but parsed incrementally via `CountingInputStream` + Jackson's
  streaming `JsonParser`, never materializing the whole response as one object. *(verified live)*

  Same query (`SELECT * FROM <catalog>.default.orders`, 8.38M rows both times) measured through
  Trino's own query stats, run back to back against each catalog:

  | | ours (`rest`) | trino-openapi (`openapi-lib`) |
  |---|---|---|
  | Total time | ~16-18s | 57s |
  | CPU time | ~9.2s | 30.42s |
  | Physical input read time | ~7.5-7.9s | 0.00ns |
  | Reported "peak memory" | 32.4-32.5MB | 32.5MB |

  The CPU/wall-time gap (~3.2-3.5x) is real and consistent with parsing into a full in-memory tree
  being more expensive than incremental streaming parsing. The reported "peak memory" figure,
  however, is **not** a fair comparison and should not be read as trino-openapi being
  memory-efficient here - see the note below. *(verified live)*

  **Why "peak memory" shows ~32MB for both despite the 12GB difference we actually observed:**
  Trino's own memory-accounting only tracks allocations made through its own tracked APIs
  (`Block`/`Page`/memory-context framework). `CharStreams.toString(...)` + `readTree(...)`
  allocate a plain `String` and `JsonNode` tree via ordinary, untracked Java heap - invisible to
  that statistic even though it's real memory, and exactly what drove the JVM to 12GB (observable
  via `docker stats`/OS-level memory monitoring, not via Trino's query stats). The `Physical input
  read time: 0.00ns` figure is a related tell: our connector reports real read time because
  `RestRecordCursor` implements `getReadTimeNanos()` through `CountingInputStream`, the exact hook
  Trino's stats framework reads; trino-openapi reporting a flat `0` there suggests it isn't
  populating that hook, so whatever time is actually spent on network/parsing work just gets
  folded into generic CPU/scheduled time instead of being broken out. **Takeaway: Trino's built-in
  "peak memory" query stat is not a reliable way to compare connector memory usage - only
  process-level memory monitoring is.** *(verified live)*
- **Root-array (bulk) request bodies may not round-trip correctly.** Column discovery unwraps a
  root-array request schema into its item type's fields (same idea as our response-side handling
  for root-array GET responses), so those fields do become filterable columns. But
  `serializeMap()` - what actually builds the request body for a `SELECT` - always constructs and
  returns a plain `ObjectNode`; no array-wrapping logic was found anywhere between that and the
  bytes actually sent. If the target endpoint's schema requires a root JSON array, this may send
  a bare object where the API expects `[...]`, likely to be rejected by the target's own schema
  validation. *(inferred from source - not run live against a real array-bodied endpoint to
  confirm)*

  **Update:** we've since built and live-verified our own handling for this exact case - not by
  changing the request-body shape, but via Trino's `ConnectorIndex`/index-join mechanism, so a
  root-array bulk-lookup endpoint is queried through an ordinary `JOIN` rather than a `SELECT`
  with a WHERE-resolved filter. Confirmed working end-to-end against a live Trino deployment
  (`JOIN` against a `memory` connector table, real HTTP round trip to a bulk-lookup endpoint,
  correct correlated results). See `FLOWS.md`'s "bulk lookup / index join" section.
- **Puzzling split/request-count mismatch.** The cartesian-product split logic in
  `OpenApiSplitManager` should generate one split per `IN` value (2 splits, 2 requests, for a
  2-value `IN`), and we did observe 2 requests for `category IN ('furniture','test')` in one
  test - but in a later, similar test only one request actually reached the server despite the
  same code path apparently applying. Not fully explained; would need to actually instrument
  their code (or inspect Trino's own split/task execution stats) to pin down definitively.
  *(observed live, mechanism not confirmed)*
- **No visible protection against the "filter column referenced in an unpushed position" class
  of bug** we found and fixed in our own connector (`RestMetadata.refrencesFilterColumn`,
  walking `constraint.getExpression()`). `OpenApiTableHandle.applyFilter()` never reads
  `constraint.getExpression()` - it's passed straight through unread, the same way our own code
  did before that fix. Since their predicate-mapped columns (`ParameterLocation.PATH/QUERY/BODY`)
  are equally unbacked by real response data, the same silent-always-false-branch failure mode
  we found via Petstore's `/pet` likely applies to their connector too, for the equivalent query
  shape. *(inferred from source - not reproduced live)*

## Why we're building our own

**This connector is deliberately read-only.** trino-openapi treats every CRUD verb as a first-
class SQL operation (`SELECT`/`INSERT`/`UPDATE`/`DELETE` mapped to `GET`/`POST or PUT`/`PATCH or
POST`/`DELETE`) - a broader, more general ambition. We've decided against that entirely: using
Trino to *write* into a system that's fronted by a REST API doesn't make sense for this
connector's purpose, so there's no INSERT/UPDATE/DELETE path here, and won't be. This is a scope
decision, not a missing feature - trino-openapi being more capable in that dimension isn't a gap
we're trying to close.

**Streaming response handling is a hard requirement, not a nice-to-have, for this project.**
Target REST APIs can return arbitrarily large payloads, and buffering a full response (as string,
then as a JSON tree) doesn't scale - we hit this concretely testing trino-openapi against our own
~1GB test fixture. Our own connector was already built around avoiding exactly that (see
`RestRecordCursor`'s incremental `JsonParser`-based reading), and it's a design constraint we're
not willing to compromise on.

**Currency with the Trino version we actually run.** trino-openapi is pinned two Trino releases
behind ours and hasn't updated even on its unreleased branch; being blocked on a third-party
project's release cadence for basic compatibility isn't acceptable for our own deployment target.

**Correctness depth on the specific mechanism we care about.** The parts of trino-openapi we
could verify around POST-body-as-filter pushdown (`getFilter`/`serializeMap`,
`OpenApiTableHandle.applyFilter`) don't show the same defensive handling we arrived at for our
own connector this session - notably around the cross-column-OR "predicate on a column with no
real backing data goes silently unenforced" failure mode. Given that failure mode returns
plausible-looking but silently incomplete results with no error at all, it's the kind of gap
worth owning end-to-end rather than inheriting unverified.
