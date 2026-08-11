package io.trino.plugin.rest;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.SchemaTableName;

// Mirrors io.trino.plugin.example.ExampleTableHandle: must be JSON-serializable since Trino
// ships table handles from coordinator to worker.
public record RestTableHandle(
        @JsonProperty("schemaTableName") SchemaTableName schemaTableName,
        // Keyed by PostFilterDefinition.name(), accumulated across possibly-multiple
        // applyFilter() rounds. Empty/unused for GET-only tables.
        @JsonProperty("resolvedFilterValues") Map<String, List<String>> resolvedFilterValues)
        implements ConnectorTableHandle {
    @JsonCreator
    public RestTableHandle {}

    // Convenience constructor for GET-only tables, so the existing call site doesn't need to
    // pass an empty map explicitly.
    public RestTableHandle(SchemaTableName schemaTableName) {
        this(schemaTableName, Map.of());
    }
}
