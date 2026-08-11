package io.trino.plugin.rest.openapi;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

// Nested inside PostBodyDefinition, itself nested inside EndpointDefinition, which Trino ships
// from coordinator to worker as JSON (via RestSplit); must be JSON-serializable.
public record PostFilterDefinition(
        @JsonProperty("name") String name,
        @JsonProperty("trinoType") String trinoType,
        @JsonProperty("required") boolean required,
        // Whether the source schema declared this as an array-of-scalar property (true) or a
        // bare scalar property (false). Decides how PostBodyDefinition may substitute more than
        // one resolved value: always fine for an array filter, unrepresentable for a scalar one.
        @JsonProperty("isArray") boolean isArray,
        // Real nested property keys from the request body root down to this filter, e.g.
        // ["address", "city"]. Unlike `name` (those same segments joined with "_"), this is never
        // ambiguous with a property that legitimately has an underscore in its own key - use this
        // to walk requestBody, not name.split("_").
        @JsonProperty("path") List<String> path) {
    @JsonCreator
    public PostFilterDefinition {
    }

    public String columnName() {
        return "request_filter_" + name;
    }
}
