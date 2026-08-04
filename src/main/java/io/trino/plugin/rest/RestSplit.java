package io.trino.plugin.rest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.spi.connector.ConnectorSplit;

// Mirrors io.trino.plugin.example.ExampleSplit: must be JSON-serializable since Trino ships
// splits from coordinator to worker.
public record RestSplit(
        @JsonProperty("uri") String uri,
        @JsonProperty("endpointDefinition") EndpointDefinition endpointDefinition) implements ConnectorSplit {
    @JsonCreator
    public RestSplit {}
}
