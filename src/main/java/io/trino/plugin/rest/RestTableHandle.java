package io.trino.plugin.rest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.SchemaTableName;

// Mirrors io.trino.plugin.example.ExampleTableHandle: must be JSON-serializable since Trino
// ships table handles from coordinator to worker.
public record RestTableHandle(@JsonProperty("schemaTableName") SchemaTableName schemaTableName) implements ConnectorTableHandle {
    @JsonCreator
    public RestTableHandle {}
}
