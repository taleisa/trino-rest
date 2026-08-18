package io.trino.plugin.rest;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import io.airlift.log.Logger;

// Small helper for working with parsed JSON responses (JsonNode trees) at query-execution time -
// as opposed to io.trino.plugin.rest.openapi, which only ever works with OpenAPI Schema objects
// at catalog-discovery time.
final class JsonUtil {
    private static final Logger log = Logger.get(JsonUtil.class);

    private JsonUtil() {}

    // Walks a ColumnDefinition/PostFilterDefinition's real nested path into a parsed response
    // row, rather than treating the flat, underscore-joined column name as a literal top-level
    // key.
    static JsonNode walk(JsonNode row, String columnName, List<String> path, String uri) {
        JsonNode current = row;
        for (String segment : path) {
            JsonNode next = current.get(segment);
            if (next == null) {
                log.warn("Column %s: path %s does not match response from %s (no field \"%s\")",
                        columnName, path, uri, segment);
                return null;
            }
            current = next;
        }
        return current;
    }
}
