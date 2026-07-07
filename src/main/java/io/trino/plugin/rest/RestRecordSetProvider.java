package io.trino.plugin.rest;

import java.util.List;

import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorRecordSetProvider;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorSplit;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTransactionHandle;
import io.trino.spi.connector.RecordSet;

public class RestRecordSetProvider implements ConnectorRecordSetProvider {
    private final RestConfig config;

    public RestRecordSetProvider(RestConfig config) {
        this.config = config;
    }

    @Override
    public RecordSet getRecordSet(ConnectorTransactionHandle transaction, ConnectorSession session,
            ConnectorSplit split, ConnectorTableHandle table, List<? extends ColumnHandle> columns) {
        RestSplit restSplit = (RestSplit) split;
        List<io.trino.spi.connector.ColumnMetadata> columnMetadata = columns.stream()
                .map(col -> ((RestColumnHandle) col).columnMetadata())
                .toList();
        return new RestRecordSet(restSplit, config, columnMetadata);
    }
}
