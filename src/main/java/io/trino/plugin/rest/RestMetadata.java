package io.trino.plugin.rest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import io.airlift.log.Logger;
import io.trino.plugin.rest.openapi.ColumnDefinition;
import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.plugin.rest.openapi.OpenApiSchemaParser;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTableVersion;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.BooleanType;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarcharType;

public class RestMetadata implements ConnectorMetadata {
    private static final Map<String, Type> TRINO_TYPES = Map.of(
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
        Map<String, ColumnHandle> handles = new HashMap<>();
        for (ColumnDefinition col : definition.columns()) {
            ColumnMetadata metadata = new ColumnMetadata(col.name(), TRINO_TYPES.get(col.trinoType()));
            handles.put(col.name(), new RestColumnHandle(metadata));
        }
        return handles;
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle,
            ColumnHandle columnHandle) {
        return ((RestColumnHandle) columnHandle).columnMetadata();
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
                columnMetadata.add(new ColumnMetadata(col.name(), TRINO_TYPES.get(col.trinoType())));
            }
            return new ConnectorTableMetadata(handle.schemaTableName(), columnMetadata);
        } else {
            throw new NoSuchElementException(String.format("No table with name %s exists within endpoint", tableName));
        }
    }
}
