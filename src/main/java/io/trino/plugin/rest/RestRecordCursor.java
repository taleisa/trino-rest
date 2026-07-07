package io.trino.plugin.rest;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.type.Type;

public class RestRecordCursor implements RecordCursor {
    private final List<ColumnMetadata> columns;
    private final List<JsonNode> rows = new ArrayList<>();
    private int currentRowIndex = -1;
    private final long completedBytes;
    private final long readTimeNanos;

    public RestRecordCursor(RestSplit split, RestConfig config, List<ColumnMetadata> columns)
            throws JsonProcessingException {
        this.columns = columns;
        RestHttpClient client = new RestHttpClient(config);
        long startTime = System.nanoTime();
        String responseBody = client.fetch(split.url());
        long endTime = System.nanoTime();
        readTimeNanos = endTime - startTime;
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(responseBody);
        completedBytes = responseBody.getBytes().length;
        if (split.endpointDefinition().isRootArray()) {
            for (JsonNode node : root) {
                rows.add(node);
            }
        } else {
            rows.add(root);
        }

    }

    @Override
    public long getCompletedBytes() {
        return completedBytes;
    }

    @Override
    public long getReadTimeNanos() {
        return readTimeNanos;
    }

    @Override
    public Type getType(int field) {
        return columns.get(field).getType();
    }

    @Override
    public boolean advanceNextPosition() {
        currentRowIndex++;
        return currentRowIndex < rows.size();
    }

    @Override
    public boolean getBoolean(int field) {
        String columnName = columns.get(field).getName();
        return rows.get(currentRowIndex).get(columnName).asBoolean();
    }

    @Override
    public long getLong(int field) {
        String columnName = columns.get(field).getName();
        return rows.get(currentRowIndex).get(columnName).asLong();
    }

    @Override
    public double getDouble(int field) {
        String columnName = columns.get(field).getName();
        return rows.get(currentRowIndex).get(columnName).asDouble();
    }

    @Override
    public Slice getSlice(int field) {
        String columnName = columns.get(field).getName();
        return Slices.utf8Slice(rows.get(currentRowIndex).get(columnName).asText());
    }

    @Override
    public Object getObject(int field) {
        // No complex types, leave with exception for now
        throw new UnsupportedOperationException("Unimplemented method 'getObject'");
    }

    @Override
    public boolean isNull(int field) {
        String columnName = columns.get(field).getName();
        JsonNode value = rows.get(currentRowIndex).get(columnName);
        // Check for missing key or value is json null
        return value == null || value.isNull();
    }

    @Override
    public void close() {
        // No resources to release since this is an HTTP request
    }

}
