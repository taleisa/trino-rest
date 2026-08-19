package io.trino.plugin.rest.openapi;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

// Nested inside EndpointDefinition, which Trino ships from coordinator to worker as JSON (via
// RestSplit); must be JSON-serializable.
public record ColumnDefinition(
        @JsonProperty("trinoType") String trinoType,
        // Real nested property keys from the response root down to this column, e.g.
        // ["address", "city"]. Use this to walk a parsed response - never name().split("_"),
        // which is ambiguous whenever a property's own key contains an underscore.
        @JsonProperty("path") List<String> path) {
    @JsonCreator
    public ColumnDefinition {}

    // Derived, not stored - always exactly path joined with "_", so there's no second
    // representation of the same data to keep in sync.
    public String name() {
        return String.join("_", path);
    }
}