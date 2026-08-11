package io.trino.plugin.rest.openapi;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public record PostBodyDefinition(
        @JsonProperty("requestBody") Map<String, Object> requestBody,
        @JsonProperty("filters") List<PostFilterDefinition> filters) {
    @JsonCreator
    public PostBodyDefinition {
    }

    // NON_NULL: a template leaf left as null (OpenApiSchemaParser's placeholder for
    // "not filled in") is a filter that never got a WHERE predicate - drop it from
    // the serialized body instead of sending a literal `null`.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @SuppressWarnings("unchecked")
    public String buildPostPayload(Map<String, List<String>> resolvedValues) {
        // Make a copy of the request payload template to substitute values in place.
        Map<String, Object> copiedRequestBody = MAPPER.convertValue(requestBody,
                new TypeReference<Map<String, Object>>() {
                });
        // Loop through reolvedValues ie. filters.
        for (Map.Entry<String, List<String>> entry : resolvedValues.entrySet()) {
            PostFilterDefinition postFilterDefinition = getPostFilterDefinition(entry.getKey());
            List<String> filterPath = postFilterDefinition.path();
            String leafFieldKey = filterPath.get(filterPath.size() - 1);
            Object current = copiedRequestBody;
            // Find the object that holds the object we need to replace in
            // copiedRequestBody.
            for (int i = 0; i < filterPath.size() - 1; i++) {
                current = ((Map<?, ?>) current).get(filterPath.get(i));

            }
            String trinoType = postFilterDefinition.trinoType();
            if (postFilterDefinition.isArray()) {
                List<Object> typedValues = entry.getValue().stream()
                        .map(value -> castToExpectedType(trinoType, value))
                        .collect(Collectors.toList());
                ((Map<String, Object>) current).put(leafFieldKey, typedValues);
            } else if (entry.getValue().size() > 1) {
                throw new IllegalArgumentException(String.format(
                        "Filter %s resolved to %d values but its request body field only accepts a single "
                                + "value; IN/OR predicates producing more than one value are not supported for this filter",
                        entry.getKey(), entry.getValue().size()));
            } else {
                ((Map<String, Object>) current).put(leafFieldKey,
                        castToExpectedType(trinoType, entry.getValue().get(0)));
            }
        }
        return serialize(copiedRequestBody);
    }

    private static Object castToExpectedType(String trinoType, String value) {
        return switch (trinoType) {
            case "BIGINT" -> Long.parseLong(value);
            case "DOUBLE" -> Double.parseDouble(value);
            case "BOOLEAN" -> Boolean.parseBoolean(value);
            default -> value; // VARCHAR
        };
    }

    private PostFilterDefinition getPostFilterDefinition(String name) {
        return filters().stream()
                .filter(filter -> filter.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Template placeholder {{" + name + "}} has no matching filter definition"));
    }

    private static String serialize(Object filled) {
        try {
            return MAPPER.writeValueAsString(filled);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize POST request body", e);
        }
    }
}
