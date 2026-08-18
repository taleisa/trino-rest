package io.trino.plugin.rest;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.trino.plugin.rest.openapi.ColumnDefinition;
import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.plugin.rest.openapi.OpenApiSchemaParser;
import io.trino.plugin.rest.openapi.PostFilterDefinition;
import io.trino.spi.StandardErrorCode;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorResolvedIndex;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTableVersion;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.expression.Variable;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.BooleanType;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;

public class RestMetadata implements ConnectorMetadata {
    private static final Map<String, Type> TYPE_NAME_TO_TRINO_TYPE = Map.of(
            "VARCHAR", VarcharType.VARCHAR,
            "BIGINT", BigintType.BIGINT,
            "DOUBLE", DoubleType.DOUBLE,
            "BOOLEAN", BooleanType.BOOLEAN,
            "JSON", VarcharType.VARCHAR // JSON stored as VARCHAR for now
    );
    private final Map<String, EndpointDefinition> tableNameToEndPointDefinition;
    private static final Logger log = Logger.get(RestMetadata.class);

    public RestMetadata(RestConfig config) {
        this.tableNameToEndPointDefinition = new HashMap<>();
        try {
            List<EndpointDefinition> endpoints = OpenApiSchemaParser.parse(config);
            for (EndpointDefinition endpoint : endpoints) {
                tableNameToEndPointDefinition.put(endpoint.tableName(), endpoint);
            }
        } catch (Exception e) {
            log.warn(e, "Failed to parse OpenAPI spec");
        }
    }

    public Map<String, EndpointDefinition> getTableNameToEndPointDefinition() {
        return tableNameToEndPointDefinition;
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session) {
        return List.of("default");
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle table) {
        RestTableHandle handle = (RestTableHandle) table;
        EndpointDefinition definition = tableNameToEndPointDefinition.get(handle.schemaTableName().getTableName());
        Map<String, ColumnHandle> columnNameToHandle = new HashMap<>();
        for (ColumnDefinition col : definition.columns()) {
            columnNameToHandle.put(col.name(),
                    new RestColumnHandle(col.name(), TYPE_NAME_TO_TRINO_TYPE.get(col.trinoType())));
        }
        // 'Virtual' columns used to indicate that these are columns that will be
        // handled solely by the api not trino
        if (definition.isPostQuery()) {
            for (PostFilterDefinition filter : definition.postBody().filters()) {
                if (filter.responseColumn() != null) {
                    continue;
                }
                columnNameToHandle.put(filter.columnName(),
                        new RestColumnHandle(filter.columnName(), TYPE_NAME_TO_TRINO_TYPE.get(filter.trinoType())));
            }
        }
        return columnNameToHandle;
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle,
            ColumnHandle columnHandle) {
        return ((RestColumnHandle) columnHandle).getColumnMetadata();
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName) {
        List<SchemaTableName> tableNames = new ArrayList<>();
        for (String tableName : tableNameToEndPointDefinition.keySet()) {
            tableNames.add(new SchemaTableName("default", tableName));
        }
        return tableNames;
    }

    @Override
    public ConnectorTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName,
            Optional<ConnectorTableVersion> startVersion, Optional<ConnectorTableVersion> endVersion) {
        if (tableNameToEndPointDefinition.containsKey(tableName.getTableName())) {
            return new RestTableHandle(tableName);
        }
        return null;
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table) {
        RestTableHandle handle = (RestTableHandle) table;
        String tableName = handle.schemaTableName().getTableName();
        EndpointDefinition definition = tableNameToEndPointDefinition.getOrDefault(tableName, null);
        if (definition != null) {
            boolean isJoinOnly = definition.isPostQuery() && definition.postBody().isRootArray();
            // A filter with a responseColumn is exposed only once, below, under that column's own
            // name - map it here so that column can carry the "this is also a required/optional
            // JOIN key" comment a plain response column otherwise wouldn't have any reason to show.
            Map<ColumnDefinition, PostFilterDefinition> responseColumnToFilter = definition.isPostQuery()
                    ? definition.postBody().filters().stream()
                            .filter(filter -> filter.responseColumn() != null)
                            .collect(Collectors.toMap(PostFilterDefinition::responseColumn, filter -> filter))
                    : Map.of();

            List<ColumnMetadata> columnMetadata = new ArrayList<>();
            for (ColumnDefinition col : definition.columns()) {
                ColumnMetadata.Builder columnBuilder = ColumnMetadata.builder()
                        .setName(col.name())
                        .setType(TYPE_NAME_TO_TRINO_TYPE.get(col.trinoType()));
                PostFilterDefinition matchedFilter = responseColumnToFilter.get(col);
                if (matchedFilter != null) {
                    columnBuilder.setComment(Optional.of(String.format(
                            "%s JOIN key (%s) - usable only via JOIN, not WHERE",
                            matchedFilter.required() ? "required" : "optional",
                            matchedFilter.isArray() ? "accepts multiple values via IN" : "single value only, no IN")));
                }
                columnMetadata.add(columnBuilder.build());
            }
            // 'Virtual' columns used to indicate that these are columns that will be
            // handled solely by the api not trino
            if (definition.isPostQuery()) {
                for (PostFilterDefinition filter : definition.postBody().filters()) {
                    if (filter.responseColumn() != null) {
                        continue;
                    }
                    String comment = String.format("%s POST filter (%s)%s",
                            filter.required() ? "required" : "optional",
                            filter.isArray() ? "accepts multiple values via IN" : "single value only, no IN",
                            isJoinOnly ? " - usable only via JOIN, not WHERE" : "");
                    columnMetadata.add(ColumnMetadata.builder()
                            .setName(filter.columnName())
                            .setType(TYPE_NAME_TO_TRINO_TYPE.get(filter.trinoType()))
                            .setComment(Optional.of(comment))
                            .build());
                }
            }
            Optional<String> tableComment = isJoinOnly
                    ? Optional.of("Bulk-lookup endpoint - queryable only via JOIN, using its required key "
                            + "column(s) as the join condition. No plain SELECT/WHERE query path exists.")
                    : Optional.empty();
            return new ConnectorTableMetadata(handle.schemaTableName(), columnMetadata, Map.of(), tableComment);
        } else {
            throw new NoSuchElementException(String.format("No table with name %s exists within endpoint", tableName));
        }
    }

    /*
     * Apply filter is canbe called multiple times by trino at differnet
     * stages/optimization stages, this was overriden to extract any filters on
     * columns that will be sent by the post request.
     */
    @Override
    public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(ConnectorSession session,
            ConnectorTableHandle table, Constraint constraint) {
        RestTableHandle handle = (RestTableHandle) table;
        EndpointDefinition definition = tableNameToEndPointDefinition.get(handle.schemaTableName().getTableName());
        if (definition == null || !definition.isPostQuery()) {
            return Optional.empty();
        }

        Map<String, PostFilterDefinition> columnNameToPostFilterDefinition = new HashMap<>();
        for (PostFilterDefinition filter : definition.postBody().filters()) {
            columnNameToPostFilterDefinition.put(filter.columnName(), filter);
        }

        // Since request columns will be sent in the request, they cannot be present
        // within complex conditions. Only a single value or range is permitted for
        // these columns because that can be represented in a JSON request. Trino places
        // these complex conditions in `constraint.getExpression()` and straighforward
        // ones which can be expressed in a JSON request in `constraint.getSummary()`
        boolean isAnyRequestColumnInExpression = refrencesFilterColumn(constraint.getExpression(),
                constraint.getAssignments(), columnNameToPostFilterDefinition.keySet());
        if (isAnyRequestColumnInExpression) {

            throw new TrinoException(StandardErrorCode.GENERIC_USER_ERROR,
                    "A request_filter_* column is used in a position that can't be pushed into the POST "
                            + "request (e.g. combined with OR against a different column, or wrapped in a "
                            + "function). Each filter column must appear only in a top-level equality or "
                            + "IN predicate, combined with AND.");
        }

        Map<ColumnHandle, Domain> domains = constraint.getSummary().getDomains().orElse(Map.of());

        // Column name from the WHERE clause to its values, regardless of which
        // operation (=, IN) produced them. Keyed by PostFilterDefinition.name(),
        // accumulated across rounds.
        Map<String, List<String>> columnNameToFilterValues = new HashMap<>(handle.resolvedFilterValues());
        // Has this call of applyFilter surface any new Filters for RestConnector to
        // handle.
        boolean madeProgress = false;

        for (Map.Entry<ColumnHandle, Domain> entry : domains.entrySet()) {
            RestColumnHandle columnHandle = (RestColumnHandle) entry.getKey();
            PostFilterDefinition filter = columnNameToPostFilterDefinition.get(columnHandle.columnName());
            if (filter == null) {
                // A column that trino will handle filtering on itself; left for remainingFilter
                // below.
                continue;
            }
            List<String> filterValues = resolveDiscreteValues(columnHandle, entry.getValue());
            List<String> previouslyResolvedValues = columnNameToFilterValues.get(filter.name());
            // This round of applyFilter has surfaced new values that were not added to the
            // RestColumnHandle
            if (!filterValues.equals(previouslyResolvedValues)) {
                columnNameToFilterValues.put(filter.name(), filterValues);
                madeProgress = true;
            }
        }

        if (!madeProgress) {
            // Must return empty once a round makes zero new progress, or Trino's optimizer
            // won't converge.
            return Optional.empty();
        }

        handle = new RestTableHandle(handle.schemaTableName(), columnNameToFilterValues);
        TupleDomain<ColumnHandle> remainingFilter = constraint.getSummary()
                .filter((columnHandle, domain) -> !columnNameToPostFilterDefinition
                        .containsKey(((RestColumnHandle) columnHandle).columnName()));
        return Optional
                .of(new ConstraintApplicationResult<>(handle, remainingFilter, constraint.getExpression(), false));
    }

    // A range or IS NULL on one of our filter columns is invalid, because these
    // columns fill in
    // a POST request field and can only ever hold one substituted value per
    // request.
    private static List<String> resolveDiscreteValues(RestColumnHandle columnHandle, Domain domain) {
        if (domain.isNullAllowed() || !domain.isNullableDiscreteSet()) {
            throw new TrinoException(StandardErrorCode.GENERIC_USER_ERROR,
                    String.format(
                            "Column %s only supports equality or IN predicates (it fills in a POST request field); %s is not supported",
                            columnHandle.columnName(), domain));
        }
        return domain.getNullableDiscreteSet().getNonNullValues().stream()
                .map(RestMetadata::stringifyDomainValue)
                .collect(Collectors.toList());
    }

    private static String stringifyDomainValue(Object value) {
        if (value instanceof Slice slice) {
            return slice.toStringUtf8();
        }
        return String.valueOf(value);
    }

    private static boolean refrencesFilterColumn(ConnectorExpression expresion, Map<String, ColumnHandle> assignments,
            Set<String> columnNames) {
        Deque<ConnectorExpression> pending = new ArrayDeque<>();
        pending.push(expresion);
        while (!pending.isEmpty()) {
            ConnectorExpression current = pending.pop();
            if (current instanceof Variable variable) {
                ColumnHandle handle = assignments.get(variable.getName());
                if (handle instanceof RestColumnHandle restColumnHandle
                        && columnNames.contains(restColumnHandle.columnName())) {
                    return true;
                }
            }
            pending.addAll(current.getChildren());
        }
        return false;
    }

    // When a join is done that includes one of our connector tables, trino asks if
    // our connector can handle this join. If we can handle it we return
    // ConnectorResolvedIndex. If not, Optional.empty()
    @Override
    public Optional<ConnectorResolvedIndex> resolveIndex(ConnectorSession session, ConnectorTableHandle tableHandle,
            Set<ColumnHandle> indexableColumns, Set<ColumnHandle> outputColumns,
            TupleDomain<ColumnHandle> tupleDomain) {
        RestTableHandle handle = (RestTableHandle) tableHandle;
        EndpointDefinition endpointDefinition = tableNameToEndPointDefinition
                .get(handle.schemaTableName().getTableName());

        if (endpointDefinition == null || !endpointDefinition.isPostQuery()
                || !endpointDefinition.postBody().isRootArray()) {
            return Optional.empty();
        }

        Set<String> indexableColumnNames = indexableColumns.stream()
                .map(columnHandle -> ((RestColumnHandle) columnHandle).columnName())
                .collect(Collectors.toSet());

        Set<String> knownKeyColumnNames = endpointDefinition.postBody().filters().stream()
                .map(PostFilterDefinition::columnName)
                .collect(Collectors.toSet());
        // Make sure all indexable columns (in join statement) are columns for this
        // endpoint.
        boolean allIndexableAreKnownKeys = knownKeyColumnNames.containsAll(indexableColumnNames);
        boolean allRequiredKeysCovered = endpointDefinition.postBody().filters().stream()
                .filter(PostFilterDefinition::required)
                .allMatch(postFilterDefinition -> indexableColumnNames.contains(postFilterDefinition.columnName()));

        if (!allIndexableAreKnownKeys || !allRequiredKeysCovered) {
            // This table has no valid non-index query path (RestSplitManager.getSplits()
            // can only resolve required filters from literal WHERE predicates, never from
            // join correlations, so a mismatched join here can never succeed some other
            // way - fail now with a clear reason instead of letting it fall through to a
            // confusing "missing resolvable predicate" error later.
            throw new TrinoException(StandardErrorCode.GENERIC_USER_ERROR,
                    String.format(
                            "Table %s can only be joined using exactly its required key column(s) %s as the join "
                                    + "condition; got join column(s) %s.",
                            handle.schemaTableName().getTableName(), knownKeyColumnNames, indexableColumnNames));
        }

        return Optional.of(new ConnectorResolvedIndex(new RestIndexHandle(handle.schemaTableName()), tupleDomain));
    }

}
