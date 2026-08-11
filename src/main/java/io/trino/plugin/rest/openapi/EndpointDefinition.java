package io.trino.plugin.rest.openapi;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

// Carried inside RestSplit, which Trino ships from coordinator to worker as JSON; must be
// JSON-serializable.
public record EndpointDefinition(
        @JsonProperty("path") String path,
        @JsonProperty("tableName") String tableName,
        @JsonProperty("columns") List<ColumnDefinition> columns,
        @JsonProperty("isRootArray") boolean isRootArray,
        @JsonProperty("postBody") PostBodyDefinition postBody) {
    @JsonCreator
    public EndpointDefinition {
    }

    // Convenience constructor for GET endpoints, so existing call sites don't need to pass a
    // null postBody explicitly.
    public EndpointDefinition(String path, String tableName, List<ColumnDefinition> columns, boolean isRootArray) {
        this(path, tableName, columns, isRootArray, null);
    }

    public boolean isPostQuery() {
        return postBody != null;
    }
}
