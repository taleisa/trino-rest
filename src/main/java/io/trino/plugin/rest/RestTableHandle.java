package io.trino.plugin.rest;

import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.SchemaTableName;

public record RestTableHandle(SchemaTableName schemaTableName) implements ConnectorTableHandle {
}
