package io.trino.plugin.rest.openapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import io.trino.plugin.rest.RestConfig;

public class OpenApiSchemaParser {
    private static final Map<String, String> TYPE_TO_TRINO_TYPE = Map.of(
            "string", "VARCHAR",
            "integer", "BIGINT",
            "number", "DOUBLE",
            "boolean", "BOOLEAN",
            "array", "JSON",
            "null", "VARCHAR");

    public static List<EndpointDefinition> parse(RestConfig config) throws Exception {

        OpenAPI openAPI = getOpenAPI(config);

        if (openAPI == null) {
            throw new Exception("Unable to parse openAPI");
        }
        List<EndpointDefinition> endPointDefinitions = new ArrayList<>();
        for (Map.Entry<String, PathItem> pathItem : openAPI.getPaths().entrySet()) {
            String path = pathItem.getKey();
            String[] pathSeperatedBySlash = path.split("/");
            String tableName = pathSeperatedBySlash[pathSeperatedBySlash.length - 1];
            Operation get = pathItem.getValue().getGet();
            // For now path parameters are not supported
            if (path.contains("{"))
                continue;
            if (get == null || get.getResponses() == null || get.getResponses().get("200") == null
                    || get.getResponses().get("200").getContent() == null
                    || get.getResponses().get("200").getContent().get("application/json") == null) {
                continue;
            }
            Schema responseSchema = get.getResponses().get("200").getContent().get("application/json").getSchema();
            boolean isRootArray = "array".equals(responseSchema.getType());
            List<ColumnDefinition> columns = new ArrayList<>();
            endPointDefinitions
                    .add(new EndpointDefinition(path, tableName, extractColumns(responseSchema, "", columns),
                            isRootArray));

        }
        return endPointDefinitions;
    }

    /**
     * Extract columns from OPENAPI schema, any non top level array is treated as a
     * json object.
     */
    private static List<ColumnDefinition> extractColumns(Schema schema, String prefix, List<ColumnDefinition> columns) {
        Boolean isTopLevel = prefix.isEmpty();
        if ("object".equals(schema.getType()) || "array".equals(schema.getType()) && isTopLevel) {
            Schema target = "array".equals(schema.getType()) ? schema.getItems() : schema;
            @SuppressWarnings({ "rawtypes", "unchecked" })
            Map<String, Schema> map = (Map<String, Schema>) target.getProperties();
            // Arrays not on top level is mapped to JSON object
            for (Map.Entry<String, Schema> entry : map.entrySet()) {
                Schema innerSchema = entry.getValue();
                String name = prefix + entry.getKey();
                columns = extractColumns(innerSchema, name + "_", columns);

            }
        } else {
            // `prefix` is passed with `_` remove it as this will be a column name.
            columns.add(new ColumnDefinition(prefix.substring(0, prefix.length() - 1),
                    TYPE_TO_TRINO_TYPE.get(schema.getType())));
        }
        return columns;
    }

    private static OpenAPI getOpenAPI(RestConfig config) {
        SwaggerParseResult result;
        if (config.getSpecUrl() != null) {
            result = new OpenAPIParser().readLocation(config.getSpecUrl(), null, null);
        } else {
            result = new OpenAPIParser().readLocation(config.getSpecPath(), null, null);
        }
        if (result.getMessages() != null)
            result.getMessages().forEach(System.err::println); // validation errors and warnings
        return result.getOpenAPI();

    }
}
