package io.trino.plugin.rest.openapi;

import java.util.List;

public record EndpointDefinition(String path, String tableName, List<ColumnDefinition> columns, boolean isRootArray) {
}
