package io.trino.plugin.rest;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeId;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.TypeOperators;
import io.trino.spi.type.TypeSignature;
import io.trino.spi.type.VarcharType;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RestMetadataTest {

  @RegisterExtension
  static WireMockExtension wm = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort())
      .build();

  // A minimal, real TypeManager isn't available without pulling in trino-testing - these tests
  // don't exercise JSON-type resolution at all, so a stub that only needs to satisfy the
  // RestMetadata constructor is enough.
  private static final TypeManager FAKE_TYPE_MANAGER = new TypeManager() {
    @Override
    public Type getType(TypeSignature signature) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Type fromSqlType(String type) {
      return VarcharType.VARCHAR;
    }

    @Override
    public Type getType(TypeId id) {
      throw new UnsupportedOperationException();
    }

    @Override
    public TypeOperators getTypeOperators() {
      throw new UnsupportedOperationException();
    }
  };

  // A root-array (bulk-lookup) endpoint whose request field "ip" matches a response field of the
  // same name, case-insensitively - so it gets a responseColumn and is exposed/read as "ip", not
  // "request_filter_ip".
  private static final String ROOT_ARRAY_SPEC = """
      {
        "openapi": "3.1.0",
        "info": { "title": "Test", "version": "1.0" },
        "paths": {
          "/lookup": {
            "post": {
              "requestBody": {
                "content": {
                  "application/json": {
                    "schema": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "required": ["ip"],
                        "properties": { "ip": { "type": "string" } }
                      }
                    }
                  }
                }
              },
              "responses": {
                "200": {
                  "description": "OK",
                  "content": {
                    "application/json": {
                      "schema": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "properties": {
                            "ip": { "type": "string" },
                            "city": { "type": "string" }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
      """;

  private RestMetadata metadataFor(String spec) {
    wm.stubFor(get("/spec").willReturn(okJson(spec)));
    RestConfig config = new RestConfig(Map.of(
        "rest.token", "token",
        "rest.specUrl", wm.baseUrl() + "/spec",
        "rest.baseUrl", wm.baseUrl()));
    return new RestMetadata(config, FAKE_TYPE_MANAGER);
  }

  @Test
  void nonDiscreteWhereOnMatchedJoinKeyIsDeclinedNotThrown() throws Exception {
    // "ip" has a responseColumn (see ROOT_ARRAY_SPEC) - its value comes from the JOIN
    // correlation itself, independent of whether applyFilter can push this predicate. A
    // non-discrete domain here (e.g. from "WHERE ip IS NOT NULL") must not fail the query.
    RestMetadata metadata = metadataFor(ROOT_ARRAY_SPEC);
    ConnectorTableHandle tableHandle = metadata.getTableHandle(null,
        new SchemaTableName("default", "lookup"), Optional.empty(), Optional.empty());
    Map<String, ColumnHandle> columns = metadata.getColumnHandles(null, tableHandle);
    RestColumnHandle ipColumn = (RestColumnHandle) columns.get("ip");

    Domain notNullDomain = Domain.notNull(VarcharType.VARCHAR);
    Constraint constraint = new Constraint(TupleDomain.withColumnDomains(Map.of(ipColumn, notNullDomain)));

    Optional<ConstraintApplicationResult<ConnectorTableHandle>> result = assertDoesNotThrow(
        () -> metadata.applyFilter(null, tableHandle, constraint),
        "a non-discrete WHERE on a matched (readable) join-key column should not throw");

    // Declined, not silently dropped: it must stay somewhere Trino will still check it - either
    // applyFilter made no pushdown at all (Optional.empty(), Trino enforces the predicate
    // itself), or it's present in remainingFilter.
    boolean stillEnforced = result.isEmpty()
        || result.get().getRemainingFilter().getDomains()
            .map(domains -> domains.containsKey(ipColumn))
            .orElse(false);
    assertTrue(stillEnforced, "the declined predicate must still be enforced by Trino, not silently dropped");
  }

  @Test
  void nonDiscreteWhereOnUnmatchedFilterStillThrows() throws Exception {
    // A filter with no responseColumn is never populated in the output (tracked separately as
    // "Echo resolved filter values instead of NULL" in TODOS.md) - silently declining a
    // non-discrete predicate on it would let Trino filter against an always-NULL column,
    // silently returning zero rows instead of a clear error. Must keep throwing.
    String spec = """
        {
          "openapi": "3.1.0",
          "info": { "title": "Test", "version": "1.0" },
          "paths": {
            "/lookup": {
              "post": {
                "requestBody": {
                  "content": {
                    "application/json": {
                      "schema": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "required": ["ip"],
                          "properties": {
                            "ip": { "type": "string" },
                            "unmatchedfield": { "type": "string" }
                          }
                        }
                      }
                    }
                  }
                },
                "responses": {
                  "200": {
                    "description": "OK",
                    "content": {
                      "application/json": {
                        "schema": {
                          "type": "array",
                          "items": {
                            "type": "object",
                            "properties": { "ip": { "type": "string" } }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
        """;
    RestMetadata metadata = metadataFor(spec);
    ConnectorTableHandle tableHandle = metadata.getTableHandle(null,
        new SchemaTableName("default", "lookup"), Optional.empty(), Optional.empty());
    Map<String, ColumnHandle> columns = metadata.getColumnHandles(null, tableHandle);
    RestColumnHandle unmatchedColumn = (RestColumnHandle) columns.get("request_filter_unmatchedfield");

    Domain notNullDomain = Domain.notNull(VarcharType.VARCHAR);
    Constraint constraint = new Constraint(
        TupleDomain.withColumnDomains(Map.of(unmatchedColumn, notNullDomain)));

    assertThrows(TrinoException.class, () -> metadata.applyFilter(null, tableHandle, constraint));
  }
}
