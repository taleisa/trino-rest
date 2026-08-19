package io.trino.plugin.rest;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.plugin.rest.openapi.ColumnDefinition;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.type.Type;

public class RestRecordCursor implements RecordCursor {
    private static final Logger log = Logger.get(RestRecordCursor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<ColumnMetadata> columns;
    private final Map<String, ColumnDefinition> columnNameToDefinition;
    private final String uri;
    private final boolean isResponseRootArray;
    private final JsonParser parser;
    private final CountingInputStream countingStream;

    private JsonNode currentRow;
    private boolean singleObjectReturned = false;
    private long readTimeNanos;

    public RestRecordCursor(RestSplit split, RestConfig config, List<ColumnMetadata> columns) throws IOException {
        this.columns = columns;
        this.columnNameToDefinition = split.endpointDefinition().columns().stream()
                .collect(Collectors.toMap(ColumnDefinition::name, column -> column));
        this.uri = split.uri();
        this.isResponseRootArray = split.endpointDefinition().isResponseRootArray();

        RestHttpClient client = new RestHttpClient(config);
        long start = System.nanoTime();
        InputStream rawStream = split.endpointDefinition().isPostQuery()
                ? client.post(uri, split.requestBody())
                : client.fetch(uri);
        CountingInputStream counting = new CountingInputStream(rawStream);
        JsonParser p = null;
        try {
            p = MAPPER.getFactory().createParser(counting);
            if (isResponseRootArray) {
                JsonToken token = p.nextToken();
                if (token != JsonToken.START_ARRAY) {
                    throw new RuntimeException(
                            String.format("Expected root JSON array in response from %s but got %s", uri, token));
                }
            }
        } catch (IOException | RuntimeException e) {
            try {
                if (p != null) {
                    p.close();
                } else {
                    counting.close();
                }
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        this.parser = p;
        this.countingStream = counting;
        this.readTimeNanos = System.nanoTime() - start;
    }

    @Override
    public long getCompletedBytes() {
        return countingStream.getCount();
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
        long start = System.nanoTime();
        try {
            if (isResponseRootArray) {
                JsonToken token = parser.nextToken();
                if (token == null || token == JsonToken.END_ARRAY) {
                    currentRow = null;
                    return false;
                }
                currentRow = MAPPER.readTree(parser);
            } else {
                if (singleObjectReturned) {
                    return false;
                }
                currentRow = MAPPER.readTree(parser);
                singleObjectReturned = true;
            }
            return true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read next row from " + uri, e);
        } finally {
            readTimeNanos += System.nanoTime() - start;
        }
    }

    @Override
    public boolean getBoolean(int field) {
        return getFieldNode(field).asBoolean();
    }

    @Override
    public long getLong(int field) {
        return getFieldNode(field).asLong();
    }

    @Override
    public double getDouble(int field) {
        return getFieldNode(field).asDouble();
    }

    @Override
    public Slice getSlice(int field) {
        JsonNode value = getFieldNode(field);
        // asText() is only meaningful for a scalar value node - for a container (object/array)
        // node, e.g. an opaque JSON column, it silently returns "" instead of the JSON content.
        return Slices.utf8Slice(value.isContainerNode() ? value.toString() : value.asText());
    }

    @Override
    public Object getObject(int field) {
        // No complex types, leave with exception for now
        throw new UnsupportedOperationException("Unimplemented method 'getObject'");
    }

    @Override
    public boolean isNull(int field) {
        JsonNode value = getFieldNode(field);
        // Check for missing key or value is json null
        return value == null || value.isNull();
    }

    private JsonNode getFieldNode(int field) {
        String columnName = columns.get(field).getName();
        ColumnDefinition definition = columnNameToDefinition.get(columnName);
        if (definition == null) {
            return null;
        }
        return JsonUtil.walk(currentRow, columnName, definition.path(), uri);
    }

    @Override
    public void close() {
        try {
            // cascades to closing countingStream -> the underlying HTTP InputStream, per
            // Jackson's default Feature.AUTO_CLOSE_SOURCE
            parser.close();
        } catch (IOException e) {
            // A cleanup failure after rows may have already been successfully delivered (e.g.
            // Trino stopped early due to a LIMIT) shouldn't fail an otherwise-successful query.
            log.warn(e, "Failed to close REST response stream for %s", uri);
        }
    }

}
