package io.trino.plugin.rest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.trino.plugin.rest.openapi.ColumnDefinition;
import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.plugin.rest.openapi.PostBodyDefinition;
import io.trino.plugin.rest.openapi.PostFilterDefinition;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorIndex;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.InMemoryRecordSet;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.connector.RecordPageSource;
import io.trino.spi.connector.RecordSet;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.BooleanType;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.Type;

public class RestConnectorIndex implements ConnectorIndex {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestConfig config;
    private final EndpointDefinition definition;
    private final List<ColumnHandle> lookupSchema;
    private final List<ColumnHandle> outputSchema;

    public RestConnectorIndex(RestConfig config, EndpointDefinition definition, List<ColumnHandle> lookupSchema,
            List<ColumnHandle> outputSchema) {
        this.config = config;
        this.definition = definition;
        this.lookupSchema = lookupSchema;
        this.outputSchema = outputSchema;
    }

    @Override
    public ConnectorPageSource lookup(RecordSet recordSet) {
        Map<String, PostFilterDefinition> columnNameToFilter = definition.postBody().filters().stream()
                .collect(Collectors.toMap(PostFilterDefinition::columnName, filter -> filter));
        Map<String, ColumnDefinition> columnNameToDefinition = definition.columns().stream()
                .collect(Collectors.toMap(ColumnDefinition::name, column -> column));

        List<Map<String, Object>> payloads = new ArrayList<>();
        RecordCursor cursor = recordSet.cursor();
        while (cursor.advanceNextPosition()) {
            Map<String, List<String>> row = new HashMap<>();
            for (int i = 0; i < lookupSchema.size(); i++) {
                RestColumnHandle columnHandle = (RestColumnHandle) lookupSchema.get(i);
                PostFilterDefinition filter = columnNameToFilter.get(columnHandle.columnName());
                row.put(filter.name(), List.of(stringifyCursorValue(cursor, i, filter.trinoType())));
            }
            payloads.add(definition.postBody().buildPostPayload(row));
        }

        String uri = config.getBaseUrl() + definition.path();
        InputStream response = new RestHttpClient(config).post(uri, PostBodyDefinition.serialize(payloads));

        JsonNode responseRows;
        try {
            responseRows = MAPPER.readTree(response);
        } catch (IOException e) {
            throw new RuntimeException(String.format("Failed to parse index lookup response from %s", uri), e);
        }

        List<Type> outputTypes = outputSchema.stream()
                .map(columnHandle -> ((RestColumnHandle) columnHandle).columnType())
                .collect(Collectors.toList());

        List<List<Object>> records = new ArrayList<>();
        for (JsonNode responseRow : responseRows) {
            List<Object> record = new ArrayList<>();
            for (ColumnHandle columnHandle : outputSchema) {
                RestColumnHandle restColumnHandle = (RestColumnHandle) columnHandle;
                // Every readable column - whether a plain response field or a filter that's also
                // echoed back (its Trino column name is then the response column's own name, see
                // PostFilterDefinition.responseColumn/columnName) - is looked up the same way: walk
                // its real nested path in the response. A filter with no matching response column
                // (columnName() still request_filter_*) has nothing here to find and stays null.
                ColumnDefinition column = columnNameToDefinition.get(restColumnHandle.columnName());
                JsonNode rawValue = column != null
                        ? JsonUtil.walk(responseRow, restColumnHandle.columnName(), column.path(), uri)
                        : null;
                record.add(normalizeForType(restColumnHandle.columnType(), rawValue));
            }
            records.add(record);
        }

        return new RecordPageSource(new InMemoryRecordSet(outputTypes, records));
    }

    private static String stringifyCursorValue(RecordCursor cursor, int field, String trinoType) {
        return switch (trinoType) {
            case "BIGINT" -> String.valueOf(cursor.getLong(field));
            case "DOUBLE" -> String.valueOf(cursor.getDouble(field));
            case "BOOLEAN" -> String.valueOf(cursor.getBoolean(field));
            default -> cursor.getSlice(field).toStringUtf8(); // VARCHAR
        };
    }

    private static Object normalizeForType(Type type, JsonNode rawValue) {
        if (rawValue == null || rawValue.isNull()) {
            return null;
        }
        if (type instanceof BigintType) {
            return rawValue.asLong();
        }
        if (type instanceof DoubleType) {
            return rawValue.asDouble();
        }
        if (type instanceof BooleanType) {
            return rawValue.asBoolean();
        }
        return rawValue.asText(); // VARCHAR
    }
}
