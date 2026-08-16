package io.trino.plugin.rest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.ConnectorIndexHandle;
import io.trino.spi.connector.SchemaTableName;

public record RestIndexHandle(@JsonProperty("schemaTableName") SchemaTableName schemaTableName)
        implements ConnectorIndexHandle {
    @JsonCreator
    public RestIndexHandle {}
}
