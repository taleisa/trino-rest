package io.trino.plugin.rest.openapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.airlift.log.Logger;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import io.trino.plugin.rest.RestConfig;

public class OpenApiSchemaParser {
    private static final Logger log = Logger.get(OpenApiSchemaParser.class);
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
            try {
                Schema responseSchema = get.getResponses().get("200").getContent().get("application/json").getSchema();
                boolean isRootArray = "array".equals(getType(responseSchema));
                List<ColumnDefinition> columns = new ArrayList<>();
                extractColumns(responseSchema, "", columns);
                if (columns.isEmpty()) {
                    log.warn("Skipping %s: no columns could be extracted from schema", path);
                    continue;
                }
                endPointDefinitions.add(new EndpointDefinition(path, tableName, columns, isRootArray));
            } catch (Exception e) {
                log.warn(e, "Skipping %s: %s", path, e.getMessage());
            }

        }
        return endPointDefinitions;
    }

    /**
     * Extract columns from OPENAPI schema, any non top level array is treated as a
     * json object.
     */
    private static List<ColumnDefinition> extractColumns(Schema schema, String prefix, List<ColumnDefinition> columns) {
        Boolean isTopLevel = prefix.isEmpty();
        String schemaType = getType(schema);
        // If type is object or non top level array
        if ("object".equals(schemaType) || ("array".equals(schemaType) && isTopLevel)) {
            Schema target = "array".equals(schemaType) ? schema.getItems() : schema;
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
                    TYPE_TO_TRINO_TYPE.get(schemaType)));
        }
        return columns;
    }

    private static OpenAPI getOpenAPI(RestConfig config) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        options.setExplicitObjectSchema(true);
        SwaggerParseResult result;
        if (config.getSpecUrl() != null) {
            result = new OpenAPIParser().readLocation(config.getSpecUrl(), null, options);
        } else {
            result = new OpenAPIParser().readLocation(config.getSpecPath(), null, options);
        }
        if (result.getMessages() != null)
            result.getMessages().forEach(msg -> log.warn(msg));
        return result.getOpenAPI();

    }

    /**
     * 
     * Get the schema type for OpenAPI >= 3.1 get the first none value schema type.
     * For OpenAPI < 3.1 there is only 1 type.
     * 
     */
    private static String getType(Schema schema) {
        if (schema.getType() != null) {
            return schema.getType();
        } else if (schema.getTypes() != null && !schema.getTypes().isEmpty()) {
            for (Object schemaType : schema.getTypes()) {
                if (!"null".equals(schemaType)) {
                    return (String) schemaType;
                }
            }
        }
        return null;
    }
}
