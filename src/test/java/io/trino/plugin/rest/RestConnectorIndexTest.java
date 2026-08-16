package io.trino.plugin.rest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.plugin.rest.openapi.PostBodyDefinition;
import io.trino.plugin.rest.openapi.PostFilterDefinition;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.InMemoryRecordSet;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.connector.RecordPageSource;
import io.trino.spi.connector.RecordSet;
import io.trino.spi.type.BooleanType;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.VarcharType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RestConnectorIndexTest {

  @RegisterExtension
  static WireMockExtension wm = WireMockExtension.newInstance()
      .options(wireMockConfig().dynamicPort())
      .build();

  private RestConfig config() {
    return new RestConfig(Map.of(
        "rest.token", "token",
        "rest.specUrl", wm.baseUrl() + "/spec",
        "rest.baseUrl", wm.baseUrl()));
  }

  private EndpointDefinition enrichEndpoint() {
    PostFilterDefinition productName = new PostFilterDefinition("product_name", "VARCHAR", true, false,
        List.of("product_name"));
    PostFilterDefinition date = new PostFilterDefinition("date", "VARCHAR", true, false, List.of("date"));
    Map<String, Object> template = new HashMap<>();
    template.put("product_name", null);
    template.put("date", null);
    PostBodyDefinition postBody = new PostBodyDefinition(template, List.of(productName, date), true);
    return new EndpointDefinition("/enrich", "enrich", List.of(), true, postBody);
  }

  @Test
  void lookupReturnsKeyColumnValuesNotNull() throws Exception {
    // The target API only ever echoes back the bare field names (product_name/date), never our
    // Trino-side request_filter_* column names.
    wm.stubFor(post("/enrich").willReturn(okJson("""
        [
          {"product_name": "Widget", "date": "2024-01-01", "discount_pct": 12.5, "in_stock": true}
        ]
        """)));

    List<ColumnHandle> lookupSchema = List.of(
        new RestColumnHandle("request_filter_product_name", VarcharType.VARCHAR),
        new RestColumnHandle("request_filter_date", VarcharType.VARCHAR));
    // outputSchema always includes the key columns alongside the requested value columns - this
    // is what Trino's IndexSnapshotBuilder hashes on to correlate results back to probe rows.
    List<ColumnHandle> outputSchema = List.of(
        new RestColumnHandle("request_filter_product_name", VarcharType.VARCHAR),
        new RestColumnHandle("request_filter_date", VarcharType.VARCHAR),
        new RestColumnHandle("discount_pct", DoubleType.DOUBLE),
        new RestColumnHandle("in_stock", BooleanType.BOOLEAN));

    RecordSet inputRecordSet = new InMemoryRecordSet(
        List.of(VarcharType.VARCHAR, VarcharType.VARCHAR),
        List.of(List.of("Widget", "2024-01-01")));

    RestConnectorIndex index = new RestConnectorIndex(config(), enrichEndpoint(), lookupSchema, outputSchema);
    ConnectorPageSource pageSource = index.lookup(inputRecordSet);
    RecordCursor cursor = ((RecordPageSource) pageSource).getCursor();

    assertTrue(cursor.advanceNextPosition());
    // This is the bug: the key columns are looked up in the response using the Trino column name
    // ("request_filter_product_name") instead of the filter's raw name ("product_name"), so they
    // come back null even though the API response clearly has a value for them.
    assertFalse(cursor.isNull(0), "request_filter_product_name should be populated from the response");
    assertEquals("Widget", cursor.getSlice(0).toStringUtf8());
    assertFalse(cursor.isNull(1), "request_filter_date should be populated from the response");
    assertEquals("2024-01-01", cursor.getSlice(1).toStringUtf8());
    assertEquals(12.5, cursor.getDouble(2));
    assertTrue(cursor.getBoolean(3));
  }
}
