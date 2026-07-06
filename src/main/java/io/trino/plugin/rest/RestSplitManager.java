package io.trino.plugin.rest;

import java.util.Map;

import io.trino.plugin.rest.openapi.EndpointDefinition;
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
        return new FixedSplitSource(new RestSplit(config.getBaseUrl() + endpoint.path(), endpoint));
    }
}
