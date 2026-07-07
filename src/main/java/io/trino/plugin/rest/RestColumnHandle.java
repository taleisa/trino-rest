package io.trino.plugin.rest;

import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;

public record RestColumnHandle(ColumnMetadata columnMetadata) implements ColumnHandle {}
