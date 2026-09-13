package io.trino.plugin.rest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.spi.connector.ConnectorIndexHandle;
import io.trino.spi.connector.SchemaTableName;

// Mirrors RestSplit: Trino ships this from coordinator to worker as JSON. The worker's
// getIndex() must use endpointDefinition from the handle, not re-look up a local parse.
public record RestIndexHandle(
        @JsonProperty("schemaTableName") SchemaTableName schemaTableName,
        @JsonProperty("endpointDefinition") EndpointDefinition endpointDefinition)
        implements ConnectorIndexHandle {
    @JsonCreator
    public RestIndexHandle {}
}
