# Future Work

## POST endpoint support
Some APIs use POST for filter-based queries (e.g. Elasticsearch `POST /_search`).
Requires:
- Convention to identify query-style POST endpoints (e.g. OpenAPI extension `x-trino-query: true`)
- Predicate pushdown via `RestMetadata.applyFilter()`
- `RestSplit` to carry optional request body
- `RestHttpClient` to send POST with JSON body

## Path parameter support
Support parameterized endpoints like `GET /users/{id}` as queryable tables.
Requires:
- `OpenApiSchemaParser` to parse path parameters from endpoint definitions
- `RestMetadata.applyFilter()` to capture WHERE predicates and substitute path params
- `RestSplit` to carry the resolved URL
