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
