package io.trino.plugin.rest.openapi;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

// Nested inside EndpointDefinition, which Trino ships from coordinator to worker as JSON (via
// RestSplit); must be JSON-serializable.
public record ColumnDefinition(
        @JsonProperty("name") String name,
        @JsonProperty("trinoType") String trinoType) {
    @JsonCreator
    public ColumnDefinition {}
}