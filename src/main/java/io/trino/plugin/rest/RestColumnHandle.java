package io.trino.plugin.rest;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.type.Type;

// Carries only columnName + columnType (both serializable) rather than a full ColumnMetadata,
// which Trino ships from coordinator to worker as JSON but has no registered serializer of its
// own. Mirrors io.trino.plugin.example.ExampleColumnHandle.
public record RestColumnHandle(
        @JsonProperty("columnName") String columnName,
        @JsonProperty("columnType") Type columnType) implements ColumnHandle {
    @JsonCreator
    public RestColumnHandle {}

    public ColumnMetadata getColumnMetadata() {
        return new ColumnMetadata(columnName, columnType);
    }
}
