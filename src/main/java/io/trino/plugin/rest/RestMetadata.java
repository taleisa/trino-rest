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
        if (definition.isPostQuery()) {
            for (PostFilterDefinition filter : definition.postBody().filters()) {
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
            List<ColumnMetadata> columnMetadata = new ArrayList<>();
            for (ColumnDefinition col : definition.columns()) {
                columnMetadata.add(ColumnMetadata.builder()
                        .setName(col.name())
                        .setType(TYPE_NAME_TO_TRINO_TYPE.get(col.trinoType()))
                        .build());
            }
            if (definition.isPostQuery()) {
                for (PostFilterDefinition filter : definition.postBody().filters()) {
                    String comment = String.format("%s POST filter (%s)",
                            filter.required() ? "required" : "optional",
                            filter.isArray() ? "accepts multiple values via IN" : "single value only, no IN");
                    columnMetadata.add(ColumnMetadata.builder()
                            .setName(filter.columnName())
                            .setType(TYPE_NAME_TO_TRINO_TYPE.get(filter.trinoType()))
                            .setComment(Optional.of(comment))
                            .build());
                }
            }
            return new ConnectorTableMetadata(handle.schemaTableName(), columnMetadata);
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

}

// select * from rest.default.product_search where request_filter_category =
// 'furniture' and request_filter_ids in (3,4,8) and (name = 'Pro Widget' or
// name = 'Compact Gizmo' or request_filter_ids = 1) limit 5;
