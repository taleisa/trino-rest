# Query Flows

This connector has three distinct query paths, depending on how a table's endpoint is
described. Each section below traces one path end to end: what the user writes, what Trino
calls, and which of our classes handles each call.

## 1. GET use case

**The flow, end to end**

1. User writes an ordinary `SELECT`:
   ```sql
   SELECT * FROM rest.default.products WHERE price > 10
   ```
   Nothing special - a GET-backed table with no filter pushdown support at all.

2. Trino calls `RestMetadata.getTableHandle()` / `getColumnHandles()` as part of planning.
   If a `WHERE` clause is present, Trino may call `RestMetadata.applyFilter()` - but for a
   GET-only table, `definition.isPostQuery()` is false, so `applyFilter()` returns
   `Optional.empty()` immediately. No pushdown happens; Trino applies `WHERE price > 10`
   itself, after the fact, against every row we hand back.

3. Trino calls `RestSplitManager.getSplits()`. Since `endpoint.isPostQuery()` is false, it
   builds the URI (`config.getBaseUrl() + endpoint.path()`) and returns a single split
   (`FixedSplitSource(new RestSplit(uri, endpoint))`) with no request body. GET endpoints are
   never partitioned in this connector - one split, one request, always.

4. Trino creates a `RestRecordCursor` for that split. Its constructor checks
   `split.endpointDefinition().isPostQuery()` - false - and calls `client.fetch(uri)` (a plain
   GET).

5. `RestRecordCursor` streams the response via Jackson's streaming `JsonParser`, never
   buffering the full body (the "stream input" work this connector is built around - the
   difference between ~32MB and 12GB+ peak memory on a large response, see `COMPARISON.md`).
   If `isResponseRootArray` is true, it consumes the top-level `[`, then reads one array
   element per `advanceNextPosition()` call; each column getter pulls the matching field out
   of the current row.

**The real limitation this path has**: every query against a GET table fetches the *entire*
response. There is no way to push a `WHERE` clause into the request - filtering only happens
after the full fetch, inside Trino.

## 2. Single POST-request-body-object use case (filter-POST)

**The flow, end to end**

1. User writes a `SELECT` with predicates on `request_filter_*` columns:
   ```sql
   SELECT * FROM rest.default.product_search
   WHERE request_filter_category = 'furniture' AND request_filter_ids IN (1,3)
   ```

2. Trino calls `RestMetadata.applyFilter()`, possibly more than once (it converges to a fixed
   point). Since `definition.isPostQuery()` is true, we build a
   `columnName -> PostFilterDefinition` map, then check `constraint.getExpression()` doesn't
   reference a filter column in a position we can't push down (`refrencesFilterColumn` - the
   cross-column-OR guard). We walk `constraint.getSummary()`'s per-column `Domain`s, resolving
   each filter column's discrete values (`resolveDiscreteValues`/`stringifyDomainValue` -
   converting Trino's typed `Domain` values to `String` here, since the result has to survive
   being embedded in a JSON-serialized `RestTableHandle` shipped coordinator to worker). We
   return a new `RestTableHandle` carrying `resolvedFilterValues`, plus whatever residual
   filter Trino still needs to check itself. Once a round makes zero new progress, we return
   `Optional.empty()` so Trino's optimizer stops calling us.

3. Trino calls `RestSplitManager.getSplits()`. Since `endpoint.isPostQuery()` is true, it
   checks every `required` filter in `postBody.filters()` has a resolved value - throwing a
   clear `TrinoException` if not (this is what catches the "predicate unpushable because of an
   OR across columns" case). Then it calls `postBody.buildPostPayload(resolvedFilterValues)`,
   which fills the *one* request-body template (`castToExpectedType` converts each `String`
   value back to its real type here) and returns the filled `Map<String, Object>` - not yet
   serialized. `RestSplitManager` turns that into the final JSON string via
   `PostBodyDefinition.serialize(...)`. One split, carrying that request body.

4. `RestRecordCursor`'s constructor sees `isPostQuery()` true and calls
   `client.post(uri, split.requestBody())` instead of `fetch()`.

5. From here it's identical to the GET path: the target API's response (an array of matching
   rows) is streamed the same way.

## 3. Bulk lookup / index join

**Status: done.** `RestIndexHandle`, `RestMetadata.resolveIndex()`,
`RestConnector.getIndexProvider()`, and `RestConnectorIndex.lookup()` are all implemented and
verified live end-to-end (`JOIN` against a `memory` connector table, real HTTP round trip to
`/enrich`, correct correlated results). Covered by `RestConnectorIndexTest`.

**The flow, end to end**

1. User writes an ordinary `JOIN`:
   ```sql
   SELECT p.name, e.discount_pct
   FROM memory.default.products p
   JOIN rest.default.enrich e
     ON p.name = e.product_name
    AND p.date = e.date
   ```
   The join condition uses the plain `product_name`/`date` columns - `PostFilterDefinition`
   resolves each request key to its response-side column (by case-insensitive name match, at
   spec-parse time), and `columnName()` returns that response column's real name whenever a
   link exists. A key only falls back to a `request_filter_*`-prefixed name when no matching
   response field could be found for it - see README's "Bulk lookup / index join, explained"
   section for that case.
   No special syntax - the whole point of using `ConnectorIndex` instead of a table function.

2. Trino's planner considers this `JOIN` and asks each side "can you serve as an index for
   this?" For our side, it calls
   `RestMetadata.resolveIndex(session, tableHandle, indexableColumns, outputColumns, tupleDomain)`
   - passing the join-key columns (`product_name`, `date`) as
   `indexableColumns` and whatever columns the query needs back (`discount_pct`, plus the keys)
   as `outputColumns`. **We enter here first.** We check whether `indexableColumns` matches our
   endpoint's bulk-lookup keys (`postBody().filters()`) and all required keys are covered. If
   yes, we return `Optional.of(new ConnectorResolvedIndex(new RestIndexHandle(...), tupleDomain))`.
   If the table isn't a bulk-lookup endpoint at all, we return `Optional.empty()` and Trino
   quietly falls back to a normal query path. But if it *is* a bulk-lookup endpoint and the
   join's columns don't match its keys, we throw a `TrinoException` instead of returning empty -
   there's no valid non-index way to query a bulk-lookup-only table, so failing here with a clear
   message is better than a confusing failure later in `RestSplitManager`.

   This call happens **once per query, during planning** - not once per batch, not once per
   row.

3. Assuming we said yes, Trino builds an IndexJoin plan and, once execution starts, needs an
   actual worker object to call. It calls `Connector.getIndexProvider()` to get our
   `ConnectorIndexProvider`, then calls
   `getIndex(transactionHandle, session, indexHandle, lookupSchema, outputSchema)` on it -
   handing back the `RestIndexHandle` we returned in step 2. **We enter here second:** cast the
   handle back, look up the matching `EndpointDefinition`, and construct one
   `RestConnectorIndex` instance carrying that definition plus the schemas. This also happens
   **once per query**, not per batch.

4. Execution begins. Trino's `IndexSourceOperator` reads the probe side (`memory.default.products`)
   in bounded chunks - confirmed via decompile, capped at `expectedPositions=10000` plus a
   memory limit, entirely Trino's own concern, not ours. For **each chunk**, it calls
   `index.lookup(recordSet)` - the `recordSet` being that chunk's `product_name`/`date` pairs.
   **We enter here third**, and this is the part that actually runs per batch, potentially many
   times per query: `RestConnectorIndex.lookup()` builds the request rows, POSTs them to
   `/enrich`, parses the response, and returns a page shaped to `outputSchema`.

   **A real bug lived here, worth remembering**: the output page's key-column values were
   looked up in the parsed response by an exact-match Trino column name, but request and
   response schemas are authored independently and often disagree on casing for the same field
   (e.g. request `ip`, response `IP`) - so a mismatched key column came back `null` for every
   row, and the join silently returned zero rows (never an error, since `null` just never
   matches anything). Fixed by resolving each filter's response-side column once, at
   spec-parse time, matching names case-insensitively (`PostFilterDefinition.responseColumn`) -
   the linked column's own name and path are then used to read the value, same as any other
   output column. Covered by `RestConnectorIndexTest.lookupReturnsKeyColumnValuesNotNull` and
   `RestConnectorIndexTest.keyColumnMatchesResponseFieldCaseInsensitively`.

5. Everything after that is Trino's engine, not our code. `IndexSnapshotBuilder` takes our
   returned page and builds a hash-keyed snapshot from it (using the key columns that are part
   of `outputSchema` - this is why the keys must be echoed back in the response). The ordinary
   `LookupJoinOperator` - the same operator used for regular hash joins - probes that snapshot
   with the real probe rows and produces the final joined output. We never see or handle the
   correlation between "this output row belongs to that probe row" ourselves; it's done by
   value-matching on the key columns, entirely inside Trino.

Concretely, our code is touched at exactly three points - `resolveIndex` (once, planning),
`getIndexProvider`/`getIndex` (once, execution start), `lookup` (once per batch, execution) -
and nowhere else in the whole join.
