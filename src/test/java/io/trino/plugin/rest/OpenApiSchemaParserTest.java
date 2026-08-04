package io.trino.plugin.rest;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import io.trino.plugin.rest.openapi.ColumnDefinition;
import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.plugin.rest.openapi.OpenApiSchemaParser;

public class OpenApiSchemaParserTest {

  @RegisterExtension
  static WireMockExtension wm = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort())
      .build();

  private static final String BASE_SPEC = """
      {
        "openapi": "3.1.0",
        "info": { "title": "Test", "version": "1.0" },
        "paths": {
          "/items": {
            "get": {
              "responses": {
                "200": {
                  "description": "OK",
                  "content": {
                    "application/json": {
                      "schema": %s
                    }
                  }
                }
              }
            }
          }
        }
      }
      """;

  private List<EndpointDefinition> parse(String schema) throws Exception {
    wm.stubFor(get("/spec").willReturn(okJson(BASE_SPEC.formatted(schema))));
    RestConfig config = new RestConfig(Map.of(
        "rest.token", "token",
        "rest.specUrl", wm.baseUrl() + "/spec",
        "rest.baseUrl", ""));
    return OpenApiSchemaParser.parse(config);
  }

  private Map<String, String> columnsOf(List<EndpointDefinition> endpoints) {
    return endpoints.get(0).columns().stream()
        .collect(Collectors.toMap(ColumnDefinition::name, ColumnDefinition::trinoType));
  }

  private Schema<?> schemaOf(String json) throws Exception {
    return new ObjectMapper().readValue(json, Schema.class);
  }

  @SuppressWarnings("unchecked")
  private List<ColumnDefinition> extractColumns(Schema<?> schema) throws Exception {
    Method method = OpenApiSchemaParser.class.getDeclaredMethod(
        "extractColumns", Schema.class, String.class, List.class, String.class);
    method.setAccessible(true);
    return (List<ColumnDefinition>) method.invoke(null, schema, "", new ArrayList<ColumnDefinition>(), "/test");
  }

  @Test
  void flatObjectSchema() throws Exception {
    List<EndpointDefinition> endpoints = parse("""
        {
          "type": "object",
          "properties": {
            "id": { "type": "integer" },
            "name": { "type": "string" },
            "active": { "type": "boolean" },
            "score": { "type": "number" }
          }
        }
        """);

    assertEquals(1, endpoints.size());
    Map<String, String> columns = columnsOf(endpoints);
    assertEquals("BIGINT", columns.get("id"));
    assertEquals("VARCHAR", columns.get("name"));
    assertEquals("BOOLEAN", columns.get("active"));
    assertEquals("DOUBLE", columns.get("score"));
  }

  @Test
  void rootArraySchema() throws Exception {
    List<EndpointDefinition> endpoints = parse("""
        {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "id": { "type": "integer" },
              "name": { "type": "string" }
            }
          }
        }
        """);

    assertEquals(1, endpoints.size());
    assertTrue(endpoints.get(0).isRootArray());
    Map<String, String> columns = columnsOf(endpoints);
    assertEquals("BIGINT", columns.get("id"));
    assertEquals("VARCHAR", columns.get("name"));
  }

  @Test
  void nestedObjectExpandsWithUnderscore() throws Exception {
    List<EndpointDefinition> endpoints = parse("""
        {
          "type": "object",
          "properties": {
            "id": { "type": "integer" },
            "address": {
              "type": "object",
              "properties": {
                "city": { "type": "string" },
                "zip": { "type": "string" }
              }
            }
          }
        }
        """);

    Map<String, String> columns = columnsOf(endpoints);
    assertEquals("BIGINT", columns.get("id"));
    assertEquals("VARCHAR", columns.get("address_city"));
    assertEquals("VARCHAR", columns.get("address_zip"));
  }

  @Test
  void nestedArrayBecomesJson() throws Exception {
    List<EndpointDefinition> endpoints = parse("""
        {
          "type": "object",
          "properties": {
            "id": { "type": "integer" },
            "tags": { "type": "array" }
          }
        }
        """);

    Map<String, String> columns = columnsOf(endpoints);
    assertEquals("BIGINT", columns.get("id"));
    assertEquals("JSON", columns.get("tags"));
  }

  @Test
  void topLevelOneOfSchemaHasNoColumns() throws Exception {
    // A polymorphic response described with oneOf (common for endpoints that can return
    // different shapes, e.g. a success object or an error object) has no top-level "type",
    // so there are no properties to turn into columns.
    List<ColumnDefinition> columns = extractColumns(schemaOf("""
        {
          "oneOf": [
            { "type": "object", "properties": { "id": { "type": "integer" } } },
            { "type": "object", "properties": { "error": { "type": "string" } } }
          ]
        }
        """));
    assertTrue(columns.isEmpty());
  }

}
