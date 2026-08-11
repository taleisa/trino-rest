package io.trino.plugin.rest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.spi.connector.ConnectorSplit;

// Mirrors io.trino.plugin.example.ExampleSplit: must be JSON-serializable since Trino ships
// splits from coordinator to worker.
public record RestSplit(
        @JsonProperty("uri") String uri,
        @JsonProperty("endpointDefinition") EndpointDefinition endpointDefinition,
        // Pre-built (placeholders already substituted) JSON POST body. Null for GET endpoints -
        // endpointDefinition.isPostQuery() is the source of truth for which one a split needs.
        @JsonProperty("requestBody") String requestBody) implements ConnectorSplit {
    @JsonCreator
    public RestSplit {}

    // Convenience constructor for GET endpoints, so existing call sites don't need to pass a
    // null requestBody explicitly.
    public RestSplit(String uri, EndpointDefinition endpointDefinition) {
        this(uri, endpointDefinition, null);
    }
}
