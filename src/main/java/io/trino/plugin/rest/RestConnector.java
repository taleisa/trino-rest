package io.trino.plugin.rest;

import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorIndexProvider;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorRecordSetProvider;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplitManager;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.transaction.IsolationLevel;

public class RestConnector implements Connector {
    private final RestMetadata metadata;
    private final RestSplitManager splitManager;
    private final RestRecordSetProvider recordSetProvider;
    private final RestConfig config;

    public RestConnector(RestConfig config) throws Exception {
        this.config = config;
        this.metadata = new RestMetadata(config);
        this.splitManager = new RestSplitManager(config, metadata.getTableNameToEndPointDefinition());
        this.recordSetProvider = new RestRecordSetProvider(config);
    }

    @Override
    public ConnectorTransactionHandle beginTransaction(IsolationLevel isolationLevel, boolean readOnly,
            boolean autoCommit) {
        return RestTransactionHandle.INSTANCE;
    }

    @Override
    public ConnectorMetadata getMetadata(ConnectorSession session, ConnectorTransactionHandle transactionHandle) {
        return metadata;
    }

    @Override
    public ConnectorSplitManager getSplitManager() {
        return splitManager;
    }

    @Override
    public ConnectorRecordSetProvider getRecordSetProvider() {
        return recordSetProvider;
    }

    @Override
    public void shutdown() {
    }

    @Override
    public ConnectorIndexProvider getIndexProvider() {

        return (transactionHandle, session, indexHandle, lookupSchema, outputSchema) -> {
            RestIndexHandle handle = (RestIndexHandle) indexHandle;
            EndpointDefinition definition = metadata.getTableNameToEndPointDefinition()
                    .get(handle.schemaTableName().getTableName());
            return new RestConnectorIndex(config, definition, lookupSchema, outputSchema);
        };
    }
}
