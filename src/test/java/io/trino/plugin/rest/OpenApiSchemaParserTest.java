package io.trino.plugin.rest;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
    assertTrue(endpoints.get(0).isResponseRootArray());
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
    // A polymorphic response described with oneOf (common for endpoints that can
    // return
    // different shapes, e.g. a success object or an error object) has no top-level
    // "type",
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

  @Test
  void rootPathUsesSpecTitleAsTableName() throws Exception {
    // "/".split("/") is a zero-length array in Java (no non-empty segments to keep), so naively
    // taking the last segment as the table name throws ArrayIndexOutOfBoundsException. A root
    // path is a legitimate endpoint though - it should be accepted, not skipped or crashed on -
    // and since it has no path segment to derive a name from, it falls back to the spec's own
    // info.title, lowercased with whitespace turned into underscores (matching the underscore
    // convention already used for nested column names elsewhere in this parser).
    String spec = """
        {
          "openapi": "3.1.0",
          "info": { "title": "My Test API", "version": "1.0" },
          "paths": {
            "/": {
              "get": {
                "responses": {
                  "200": {
                    "description": "OK",
                    "content": {
                      "application/json": {
                        "schema": { "type": "object", "properties": { "status": { "type": "string" } } }
                      }
                    }
                  }
                }
              }
            },
            "/items": {
              "get": {
                "responses": {
                  "200": {
                    "description": "OK",
                    "content": {
                      "application/json": {
                        "schema": { "type": "object", "properties": { "id": { "type": "integer" } } }
                      }
                    }
                  }
                }
              }
            }
          }
        }
        """;
    wm.stubFor(get("/spec").willReturn(okJson(spec)));
    RestConfig config = new RestConfig(Map.of(
        "rest.token", "token",
        "rest.specUrl", wm.baseUrl() + "/spec",
        "rest.baseUrl", ""));

    List<EndpointDefinition> endpoints = OpenApiSchemaParser.parse(config);

    assertTrue(endpoints.stream().anyMatch(e -> "my_test_api".equals(e.tableName())),
        "the / endpoint should be discovered as a table named after the spec's info.title");
    assertTrue(endpoints.stream().anyMatch(e -> "items".equals(e.tableName())),
        "the well-formed /items endpoint should still be discovered alongside it");
  }

  @Test
  void refIsResolvedIntoRealColumns() throws Exception {
    // getOpenAPI() sets ParseOptions.setResolveFully(true), so a $ref should already be
    // dereferenced into the real schema by the time our own parsing code sees it.
    String spec = """
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
                        "schema": { "$ref": "#/components/schemas/Item" }
                      }
                    }
                  }
                }
              }
            }
          },
          "components": {
            "schemas": {
              "Item": {
                "type": "object",
                "properties": {
                  "id": { "type": "integer" },
                  "name": { "type": "string" }
                }
              }
            }
          }
        }
        """;
    wm.stubFor(get("/spec").willReturn(okJson(spec)));
    RestConfig config = new RestConfig(Map.of(
        "rest.token", "token",
        "rest.specUrl", wm.baseUrl() + "/spec",
        "rest.baseUrl", ""));

    List<EndpointDefinition> endpoints = OpenApiSchemaParser.parse(config);

    assertEquals(1, endpoints.size());
    Map<String, String> columns = columnsOf(endpoints);
    assertEquals("BIGINT", columns.get("id"));
    assertEquals("VARCHAR", columns.get("name"));
  }

  @Test
  @Timeout(10)
  void circularRefDoesNotHangOrCrash() throws Exception {
    // Node references itself (id + a "parent" field that's the same Node schema again).
    // Behavior isn't verified yet - this test exists to observe and pin down what
    // setResolveFully(true) actually does with a cycle, bounded by a timeout so a bad outcome
    // (infinite resolution) fails the test instead of hanging the whole suite.
    String spec = """
        {
          "openapi": "3.1.0",
          "info": { "title": "Test", "version": "1.0" },
          "paths": {
            "/nodes": {
              "get": {
                "responses": {
                  "200": {
                    "description": "OK",
                    "content": {
                      "application/json": {
                        "schema": { "$ref": "#/components/schemas/Node" }
                      }
                    }
                  }
                }
              }
            }
          },
          "components": {
            "schemas": {
              "Node": {
                "type": "object",
                "properties": {
                  "id": { "type": "integer" },
                  "parent": { "$ref": "#/components/schemas/Node" }
                }
              }
            }
          }
        }
        """;
    wm.stubFor(get("/spec").willReturn(okJson(spec)));
    RestConfig config = new RestConfig(Map.of(
        "rest.token", "token",
        "rest.specUrl", wm.baseUrl() + "/spec",
        "rest.baseUrl", ""));

    // Observed (not guessed): swagger-parser's resolveFully breaks the cycle after two levels
    // rather than resolving forever, but the schema it leaves at that point is type=object with
    // getProperties()==null - which extractColumns doesn't currently guard against, so this
    // throws a NullPointerException internally. That's caught by buildGetEndpoint's existing
    // try/catch (same as any other malformed schema) and logged, so /nodes is skipped but
    // parse() itself still completes normally - it must not hang, and no exception should
    // escape to the caller.
    List<EndpointDefinition> endpoints = OpenApiSchemaParser.parse(config);

    assertTrue(endpoints.stream().noneMatch(e -> "nodes".equals(e.tableName())),
        "a circularly-referenced schema currently can't be templated into columns, so /nodes "
            + "should be skipped rather than partially/incorrectly represented");
  }

}
