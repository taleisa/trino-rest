package io.trino.plugin.rest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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

        List<Map<String, Object>> responseRows;
        try {
            responseRows = MAPPER.readValue(response, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (IOException e) {
            throw new RuntimeException(String.format("Failed to parse index lookup response from %s", uri), e);
        }

        List<Type> outputTypes = outputSchema.stream()
                .map(columnHandle -> ((RestColumnHandle) columnHandle).columnType())
                .collect(Collectors.toList());

        List<List<Object>> records = new ArrayList<>();
        for (Map<String, Object> responseRow : responseRows) {
            List<Object> record = new ArrayList<>();
            for (ColumnHandle columnHandle : outputSchema) {
                RestColumnHandle restColumnHandle = (RestColumnHandle) columnHandle;
                // Key columns are looked up under their raw filter name - the target API only
                // ever echoes back "product_name", never our Trino-side "request_filter_product_name".
                PostFilterDefinition filter = columnNameToFilter.get(restColumnHandle.columnName());
                String responseFieldName = filter != null ? filter.name() : restColumnHandle.columnName();
                Object rawValue = responseRow.get(responseFieldName);
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

    private static Object normalizeForType(Type type, Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (type instanceof BigintType) {
            return ((Number) rawValue).longValue();
        }
        if (type instanceof DoubleType) {
            return ((Number) rawValue).doubleValue();
        }
        if (type instanceof BooleanType) {
            return rawValue;
        }
        return rawValue.toString(); // VARCHAR
    }
}
