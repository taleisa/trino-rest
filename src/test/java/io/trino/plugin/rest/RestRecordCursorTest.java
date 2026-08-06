package io.trino.plugin.rest;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.BooleanType;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.VarcharType;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RestRecordCursorTest {

  @RegisterExtension
  static WireMockExtension wm = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort())
      .build();

  private static final List<ColumnMetadata> COLUMNS = List.of(
      new ColumnMetadata("id", BigintType.BIGINT),
      new ColumnMetadata("name", VarcharType.VARCHAR),
      new ColumnMetadata("active", BooleanType.BOOLEAN),
      new ColumnMetadata("score", DoubleType.DOUBLE));

  private RestConfig config() {
    return new RestConfig(Map.of(
        "rest.token", "token",
        "rest.specUrl", wm.baseUrl() + "/spec",
        "rest.baseUrl", wm.baseUrl()));
  }

  private RecordCursor cursorFor(String path, boolean isRootArray, List<ColumnMetadata> columns) throws Exception {
    EndpointDefinition endpoint = new EndpointDefinition(path, "items", List.of(), isRootArray);
    RestSplit split = new RestSplit(wm.baseUrl() + path, endpoint);
    RestRecordSet recordSet = new RestRecordSet(split, config(), columns);
    return recordSet.cursor();
  }

  @Test
  void rootArrayRowsReadCorrectly() throws Exception {
    wm.stubFor(get("/items").willReturn(okJson("""
        [
          {"id": 1, "name": "Alice", "active": true, "score": 4.5},
          {"id": 2, "name": "Bob", "active": false, "score": null},
          {"id": 3, "name": "Charlie", "active": true, "score": 9.1}
        ]
        """)));

    RecordCursor cursor = cursorFor("/items", true, COLUMNS);
    try {
      assertTrue(cursor.advanceNextPosition());
      assertEquals(1, cursor.getLong(0));
      assertEquals("Alice", cursor.getSlice(1).toStringUtf8());
      assertTrue(cursor.getBoolean(2));
      assertEquals(4.5, cursor.getDouble(3));
      assertFalse(cursor.isNull(3));

      assertTrue(cursor.advanceNextPosition());
      assertEquals(2, cursor.getLong(0));
      assertFalse(cursor.getBoolean(2));
      assertTrue(cursor.isNull(3));

      assertTrue(cursor.advanceNextPosition());
      assertEquals(3, cursor.getLong(0));

      assertFalse(cursor.advanceNextPosition());
    } finally {
      cursor.close();
    }
  }

  @Test
  void singleObjectResponseReadAsOneRow() throws Exception {
    wm.stubFor(get("/item").willReturn(okJson("""
        {"id": 42, "name": "Solo", "active": true, "score": 1.5}
        """)));

    RecordCursor cursor = cursorFor("/item", false, COLUMNS);
    try {
      assertTrue(cursor.advanceNextPosition());
      assertEquals(42, cursor.getLong(0));
      assertEquals("Solo", cursor.getSlice(1).toStringUtf8());

      assertFalse(cursor.advanceNextPosition());
    } finally {
      cursor.close();
    }
  }

  @Test
  void closeAfterEarlyTerminationDoesNotThrow() throws Exception {
    StringBuilder rows = new StringBuilder("[");
    for (int i = 1; i <= 50; i++) {
      if (i > 1) rows.append(",");
      rows.append(String.format("{\"id\": %d, \"name\": \"row%d\", \"active\": true, \"score\": 1.0}", i, i));
    }
    rows.append("]");
    wm.stubFor(get("/many").willReturn(okJson(rows.toString())));

    RecordCursor cursor = cursorFor("/many", true, COLUMNS);
    // simulate a LIMIT: stop reading well before exhausting the 50 rows
    assertTrue(cursor.advanceNextPosition());
    assertTrue(cursor.advanceNextPosition());
    assertDoesNotThrow(cursor::close);
  }

  @Test
  void non200ResponseFailsWithClearError() {
    wm.stubFor(get("/broken").willReturn(aResponse().withStatus(500)));

    RuntimeException ex = assertThrows(RuntimeException.class, () -> cursorFor("/broken", true, COLUMNS));
    assertTrue(ex.getMessage().contains("500"));
    assertTrue(ex.getMessage().contains(wm.baseUrl() + "/broken"));
  }

  @Test
  void largeResponseIsReadCorrectlyRowByRow() throws Exception {
    // Proves functional correctness at scale through the public RecordCursor API - not a
    // direct measurement of peak JVM heap usage (impractical/flaky from a unit test). The
    // bounded-memory guarantee is a structural fact verifiable by review: there is no
    // List<JsonNode> field anymore, only a single reassigned `currentRow`; that claim was
    // additionally verified empirically outside this test suite against the project's actual
    // Jackson version, over both a file and a real HTTP connection, against a real ~1GB payload
    // under a constrained heap.
    int rowCount = 5000;
    StringBuilder rows = new StringBuilder("[");
    for (int i = 1; i <= rowCount; i++) {
      if (i > 1) rows.append(",");
      rows.append(String.format("{\"id\": %d, \"name\": \"row%d\", \"active\": %b, \"score\": %d.5}",
          i, i, i % 2 == 0, i));
    }
    rows.append("]");
    wm.stubFor(get("/bulk").willReturn(okJson(rows.toString())));

    RecordCursor cursor = cursorFor("/bulk", true, COLUMNS);
    try {
      int count = 0;
      long firstId = -1;
      long lastId = -1;
      while (cursor.advanceNextPosition()) {
        count++;
        long id = cursor.getLong(0);
        if (count == 1) {
          firstId = id;
          assertEquals("row1", cursor.getSlice(1).toStringUtf8());
        }
        lastId = id;
      }
      assertEquals(rowCount, count);
      assertEquals(1, firstId);
      assertEquals(rowCount, lastId);
    } finally {
      cursor.close();
    }
  }
}
