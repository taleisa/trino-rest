package io.trino.plugin.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.plugin.rest.openapi.PostBodyDefinition;
import io.trino.plugin.rest.openapi.PostFilterDefinition;
import io.trino.spi.StandardErrorCode;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplitManager;
import io.trino.spi.connector.ConnectorSplitSource;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.DynamicFilter;
import io.trino.spi.connector.FixedSplitSource;

public class RestSplitManager implements ConnectorSplitManager {
    private final RestConfig config;
    private final Map<String, EndpointDefinition> endpointsByTable;

    public RestSplitManager(RestConfig config, Map<String, EndpointDefinition> endpointsByTable) {
        this.config = config;
        this.endpointsByTable = endpointsByTable;
    }

    @Override
    public ConnectorSplitSource getSplits(
            ConnectorTransactionHandle transaction,
            ConnectorSession session,
            ConnectorTableHandle table,
            DynamicFilter dynamicFilter,
            Constraint constraint) {
        RestTableHandle handle = (RestTableHandle) table;
        EndpointDefinition endpoint = endpointsByTable.get(handle.schemaTableName().getTableName());
        String uri = config.getBaseUrl() + endpoint.path();

        if (!endpoint.isPostQuery()) {
            return new FixedSplitSource(new RestSplit(uri, endpoint));
        }

        PostBodyDefinition postBody = endpoint.postBody();
        Map<String, List<String>> resolvedFilterValues = handle.resolvedFilterValues();
        List<String> missingRequiredFilters = new ArrayList<>();
        for (PostFilterDefinition filter : postBody.filters()) {
            List<String> values = resolvedFilterValues.get(filter.name());
            if (filter.required() && (values == null || values.isEmpty())) {
                missingRequiredFilters.add(filter.columnName());
            }
        }
        if (!missingRequiredFilters.isEmpty()) {
            // No WHERE predicate resolved a value for a required filter, so there's no way
            // to build a valid POST body - fail rather than send a request the target API
            // would reject anyway. This can happen even when the column IS mentioned in the
            // WHERE clause: TupleDomain (what applyFilter works with) can only express a
            // conjunction of per-column domains, so a predicate on this column combined
            // with
            // OR against a *different* column is unpushable in its entirety, not just
            // partially.
            throw new TrinoException(StandardErrorCode.GENERIC_USER_ERROR,
                    String.format(
                            "Query on %s is missing a resolvable predicate for required filter column(s): %s. "
                                    + "Each required filter needs its own top-level equality or IN predicate, "
                                    + "combined with AND - a predicate combined with OR across different columns "
                                    + "cannot be pushed into a single POST request.",
                            handle.schemaTableName().getTableName(), String.join(", ", missingRequiredFilters)));
        }

        Map<String, Object> requestBody = postBody.buildPostPayload(resolvedFilterValues);

        return new FixedSplitSource(new RestSplit(uri, endpoint, PostBodyDefinition.serialize(requestBody)));
    }
}
