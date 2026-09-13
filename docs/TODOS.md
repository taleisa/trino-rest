# Future Work

## Path parameter support
Support parameterized endpoints like `GET /users/{id}` as queryable tables.
Requires:
- `OpenApiSchemaParser` to parse path parameters from endpoint definitions
- `RestMetadata.applyFilter()` to capture WHERE predicates and substitute path params
- `RestSplit` to carry the resolved URL

## Echo resolved filter values instead of NULL
`request_filter_*` columns have no backing data in the response, so `RestRecordCursor`
currently reports them as `NULL` in `SELECT *` results even though a value was supplied and
used to build the request. Since the resolved value is already known (it's on the table
handle/split), it could be filled in directly instead of left `NULL` - nicer `SELECT *`
output, no correctness change either way.

## Push additional predicates into index-lookup requests
`RestMetadata.resolveIndex()` passes its `tupleDomain` argument straight through as the
`unresolvedTupleDomain` on `ConnectorResolvedIndex`, without attempting to push any of it into
the request itself. A query like `... JOIN rest.default.enrich e ON ... WHERE e.request_filter_date
= '2024-01-01'` would work correctly today (Trino re-filters afterward), but every row in every
batch still gets looked up rather than skipping batches that can't match. Not required for
correctness, just an efficiency gap.

## Reuse a single HttpClient per catalog
Every `RestRecordCursor` constructor and every `RestConnectorIndex.lookup()` call does
`new RestHttpClient(config)`, and each of those does `HttpClient.newHttpClient()`. Java's
`HttpClient` is meant to be reused (connection pool, TLS session, executor); constructing a
new one per request throws that away.

For a single large GET/filter-POST this is one extra client for the whole query. For a bulk
`JOIN` it is one new client per batch. Sharing one client means mixing a live pool into
`RestConfig` or threading `RestHttpClient` through the SPI wrappers. Skipped: architecture
over a minor keep-alive/executor win.

## Scan via ConnectorPageSourceProvider instead of ConnectorRecordSetProvider
GET/filter-POST use `getRecordSetProvider()` → `RestRecordCursor` (one JSON object per
`advanceNextPosition()`). Trino wraps that cursor in `RecordPageSource` and builds pages
itself. `Connector.getPageSourceProvider()` is the current scan SPI: implement
`ConnectorPageSource.getNextSourcePage()`, write columns into `BlockBuilder`s, return a
`Page`. `RestRecordSet` / `RestRecordCursor` would go away.

Valid, scan only. `ConnectorIndex.lookup(RecordSet)` is unchanged — JOIN still receives a
`RecordSet` of probe keys and still returns a `ConnectorPageSource`. Helps large GET/filter-POST
(less per-cell boxing). Does not change HTTP, JOIN request build, or JOIN `InMemoryRecordSet`.
Cost is a rewrite (`Page`, `BlockBuilder`, VARCHAR `Slice`, page size, `getMemoryUsage`).

## Benchmark the index-lookup request-building path
`RestConnectorIndex.lookup()` builds each row's request body via `PostBodyDefinition.buildPostPayload()`
per row, then serializes the collected list once. Worth benchmarking against Trino's own
`expectedPositions=10000` batch cap to see whether the per-row `MAPPER.convertValue()` deep-copy
becomes a measurable cost at that scale, or stays negligible next to the network round trip.

## Format-aware string values (dates, etc.)
Every string value - filter values, index-lookup key values - passes through byte-for-byte from
wherever it originated in the query, with no format conversion or validation anywhere in the
connector. `OpenApiSchemaParser` doesn't read OpenAPI's `format` annotation (`format: date`,
`format: date-time`, etc.) at all - a field declared that way is treated identically to any other
plain string. If a target endpoint expects a specific string format (e.g. `MM/DD/YYYY`) and the
query supplies a different one, the request is sent as-is and likely rejected by the target API;
there's currently no way for the connector to catch or convert this. Fixing it would mean reading
`format` during parsing and, at minimum, validating (or reformatting) values against it before
they're sent.

## Increase visibility and stats for the REST connector
Beyond `RestRecordCursor.getCompletedBytes()`/`getReadTimeNanos()` (which Trino surfaces in its
own query stats), there's no way to see what the connector is actually doing over HTTP -
confirmed directly while testing the index-join work, where the only way to count how many
`POST /enrich` requests a query issued was to grep the target API's own request log, since
neither Trino's query stats nor `EXPLAIN ANALYZE` expose a request/batch count for the
`IndexSource` stage. Worth adding real visibility: request counts, per-batch timing, batch sizes
for `RestConnectorIndex.lookup()`. This includes what actually surfaces in Trino's own Web UI
(the query details/live plan page at `:8080/ui`) - operator-level stats, split info
(`ConnectorSplit`/index-source stage), not just JMX or logs - since that's where anyone actually
running a query would look first, not an external log file.

## Verify echoed index-lookup keys against the request that was actually sent
`RestConnectorIndex.lookup()` correlates a response row back to a probe row purely by the key
value the target API echoes back in that response object (read via `JsonUtil.walk()` against
the key column's own path) - never against the value we actually sent for that request slot.
Trino's own index-join machinery
then hashes/matches on that same echoed value. Nothing anywhere checks that the echo is honest:
if the target API has a bug (or is malicious) and returns a response object whose key field
doesn't match the data actually attached to it, the wrong row gets silently joined onto the probe
row - no error, no warning. Same blast radius for a dropped key (silently vanishes from an inner
join) or a duplicated key (silently fans out into extra rows). Worth adding an optional
verification mode: track which key value was sent for each request slot, and log or fail loudly
when an echoed key doesn't correspond to anything that was actually requested in that batch.

