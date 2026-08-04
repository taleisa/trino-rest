package io.trino.plugin.rest;

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.connector.RecordSet;
import io.trino.spi.type.Type;

public class RestRecordSet implements RecordSet {
    private final RestSplit split;
    private final RestConfig config;
    private final List<ColumnMetadata> columns;

    public RestRecordSet(RestSplit split, RestConfig config, List<ColumnMetadata> columns) {
        this.split = split;
        this.config = config;
        this.columns = columns;
    }

    @Override
    public List<Type> getColumnTypes() {
        return columns.stream().map(ColumnMetadata::getType).collect(Collectors.toList());
    }

    @Override
    public RecordCursor cursor() {
        try {
            return new RestRecordCursor(split, config, columns);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to create cursor for " + split.uri(), e);
        }
    }

}
