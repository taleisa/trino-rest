package io.trino.plugin.rest;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.trino.plugin.rest.openapi.ColumnDefinition;
import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.plugin.rest.openapi.OpenApiSchemaParser;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
  void emptySchemaIsSkipped() throws Exception {
    List<EndpointDefinition> endpoints = parse("{}");
    assertTrue(endpoints.isEmpty());
  }

}
