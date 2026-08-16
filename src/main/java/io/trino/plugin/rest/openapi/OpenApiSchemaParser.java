package io.trino.plugin.rest.openapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.airlift.log.Logger;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
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
            // Lowercase: Trino canonicalizes unquoted identifiers to lowercase on every
            // lookup (both what SHOW TABLES displays and what getTableHandle() receives),
            // so a mixed-case table name derived as-is from the URL path would show up in
            // SHOW TABLES but never actually be reachable by any query.
            String tableName = pathSeperatedBySlash[pathSeperatedBySlash.length - 1].toLowerCase(Locale.ROOT);
            // For now path parameters are not supported
            if (path.contains("{"))
                continue;

            // GET always takes priority when both exist (a shared path is the REST
            // convention for GET=list/read, POST=create, not a filter variant). Only fall
            // back to POST if it looks like a query-style endpoint: a JSON response to
            // read, and a JSON request body schema to derive filters from.
            Operation get = pathItem.getValue().getGet();
            Operation post = pathItem.getValue().getPost();
            EndpointDefinition endpoint = hasJsonResponse(get)
                    ? buildGetEndpoint(path, tableName, get)
                    : hasJsonResponse(post) ? buildPostQueryEndpoint(path, tableName, post) : null;

            if (endpoint != null) {
                endPointDefinitions.add(endpoint);
            }
        }
        return endPointDefinitions;
    }

    private static EndpointDefinition buildGetEndpoint(String path, String tableName, Operation get) {
        try {
            Schema responseSchema = getJsonResponseSchema(get);
            List<ColumnDefinition> columns = new ArrayList<>();
            extractColumns(responseSchema, "", columns, path);
            if (columns.isEmpty()) {
                log.warn("Skipping %s: no columns could be extracted from schema", path);
                return null;
            }
            boolean isResponseRootArray = "array".equals(getType(responseSchema));
            return new EndpointDefinition(path, tableName, columns, isResponseRootArray);
        } catch (Exception e) {
            log.warn(e, "Skipping %s: %s", path, e.getMessage());
            return null;
        }
    }

    private static EndpointDefinition buildPostQueryEndpoint(String path, String tableName, Operation post) {
        try {
            Schema responseSchema = getJsonResponseSchema(post);
            List<ColumnDefinition> columns = new ArrayList<>();
            extractColumns(responseSchema, "", columns, path);
            if (columns.isEmpty()) {
                log.warn("Skipping %s: no columns could be extracted from schema", path);
                return null;
            }

            Schema requestSchema = getJsonRequestBodySchema(post);
            if (requestSchema == null) {
                log.warn("Skipping %s: POST operation has no JSON request body schema", path);
                return null;
            }
            String requestRootType = getType(requestSchema);
            if ("object".equals(requestRootType) || "array".equals(requestRootType)) {

                List<PostFilterDefinition> filters = new ArrayList<>();
                Map<String, Object> requestBodyTemplate = buildRequestTemplate(
                        "array".equals(requestRootType) ? requestSchema.getItems() : requestSchema, "", filters, path,
                        List.of());
                if (requestBodyTemplate == null) {
                    // buildRequestTemplate already logged the specific reason.
                    return null;
                }

                boolean isResponseRootArray = "array".equals(getType(responseSchema));
                PostBodyDefinition postBody = "object".equals(requestRootType)
                        ? new PostBodyDefinition(requestBodyTemplate, filters)
                        : new PostBodyDefinition(requestBodyTemplate, filters, true);
                return new EndpointDefinition(path, tableName, columns, isResponseRootArray, postBody);
            } else {
                log.warn("Skipping %s: request body must be a JSON object or array", path);
                return null;
            }
        } catch (Exception e) {
            log.warn(e, "Skipping %s: %s", path, e.getMessage());
            return null;
        }
    }

    private static boolean hasJsonResponse(Operation operation) {
        return operation != null && operation.getResponses() != null
                && operation.getResponses().get("200") != null
                && operation.getResponses().get("200").getContent() != null
                && operation.getResponses().get("200").getContent().get("application/json") != null;
    }

    private static Schema getJsonResponseSchema(Operation operation) {
        return operation.getResponses().get("200").getContent().get("application/json").getSchema();
    }

    private static Schema getJsonRequestBodySchema(Operation operation) {
        if (operation.getRequestBody() == null || operation.getRequestBody().getContent() == null) {
            return null;
        }
        MediaType mediaType = operation.getRequestBody().getContent().get("application/json");
        return mediaType != null ? mediaType.getSchema() : null;
    }

    /**
     * Recursively walks an object schema's properties, building the matching
     * request body template fragment and collecting a PostFilterDefinition for
     * every walkable leaf. Names are prefix-chained the same way extractColumns()
     * names response columns, so filters at different nesting depths never collide.
     * Returns null if a *required* property can't be represented (object -> list ->
     * object, or an unresolvable type) - that failure propagates up so the whole
     * endpoint gets skipped, since we'd otherwise be building a request guaranteed
     * to fail the target API's own validation.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Map<String, Object> buildRequestTemplate(Schema objectSchema, String prefix,
            List<PostFilterDefinition> filters, String path, List<String> pathSegments) {
        Map<String, Schema> properties = (Map<String, Schema>) objectSchema.getProperties();
        if (properties == null) {
            return Map.of();
        }
        List<String> required = objectSchema.getRequired() != null ? objectSchema.getRequired() : List.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> entry : properties.entrySet()) {
            String propertyKey = entry.getKey();
            Schema propertySchema = entry.getValue();
            String name = prefix + propertyKey;
            List<String> fieldPath = new ArrayList<>(pathSegments);
            fieldPath.add(propertyKey);
            boolean isRequired = required.contains(propertyKey);
            String type = getType(propertySchema);

            if ("object".equals(type)) {
                Map<String, Object> nested = buildRequestTemplate(propertySchema, name + "_", filters, path,
                        fieldPath);
                // null means this nested object has a required property somewhere inside it
                // that can't be represented (array-of-objects, unresolvable type, etc.).
                if (nested == null) {
                    if (isRequired) {
                        return null;
                    }
                    // Skip field entirely as its not required but also cannot be represented.
                    continue;
                }
                result.put(propertyKey, nested);
            } else if ("array".equals(type)) {
                String itemsType = getType(propertySchema.getItems());
                if (isScalarType(itemsType)) {
                    filters.add(new PostFilterDefinition(name, TYPE_TO_TRINO_TYPE.get(itemsType), isRequired, true,
                            fieldPath));
                    result.put(propertyKey, null);
                } else if (isRequired) {
                    log.warn(
                            "Skipping %s: required property %s is an array of objects, which can't be templated",
                            path, name);
                    return null;
                }
                // optional array-of-objects/arrays: not walkable, omit the key entirely.
            } else if (isScalarType(type)) {
                filters.add(new PostFilterDefinition(name, TYPE_TO_TRINO_TYPE.get(type), isRequired, false,
                        fieldPath));
                result.put(propertyKey, null);
            } else if (isRequired) {
                log.warn("Skipping %s: required property %s has no resolvable type", path, name);
                return null;
            }
            // optional, unresolvable type: omit the key entirely.
        }
        return result;
    }

    private static boolean isScalarType(String type) {
        return "string".equals(type) || "integer".equals(type) || "number".equals(type) || "boolean".equals(type);
    }

    /**
     * Extract columns from OPENAPI schema, any non top level array is treated as a
     * json object.
     */
    private static List<ColumnDefinition> extractColumns(Schema schema, String prefix, List<ColumnDefinition> columns,
            String path) {
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
                columns = extractColumns(innerSchema, name + "_", columns, path);

            }
        } else if (isTopLevel) {
            // A top-level schema with no resolvable type (e.g. a oneOf/anyOf polymorphic
            // response) has no properties to turn into columns.
            log.warn("Skipping %s: top-level schema has no resolvable type (e.g. oneOf/anyOf)", path);
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
