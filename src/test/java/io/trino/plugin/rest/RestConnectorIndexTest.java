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

import io.trino.plugin.rest.openapi.ColumnDefinition;
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
    // A real parsed endpoint's columns() covers every response field, not just the filters -
    // match that here so lookups for non-filter output columns have somewhere to find their path.
    List<ColumnDefinition> columns = List.of(
        new ColumnDefinition("DOUBLE", List.of("discount_pct")),
        new ColumnDefinition("BOOLEAN", List.of("in_stock")));
    return new EndpointDefinition("/enrich", "enrich", columns, true, postBody);
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

  @Test
  void nestedOutputColumnValueIsReadCorrectly() throws Exception {
    // The column name "location_city" reflects a nested field ({"location": {"city": ...}}), so
    // its real path (as a real parsed endpoint's columns() would carry it) is ["location",
    // "city"], not the flat name itself.
    PostFilterDefinition idFilter = new PostFilterDefinition("id", "VARCHAR", true, false, List.of("id"));
    Map<String, Object> template = new HashMap<>();
    template.put("id", null);
    PostBodyDefinition postBody = new PostBodyDefinition(template, List.of(idFilter), true);
    List<ColumnDefinition> columns = List.of(new ColumnDefinition("VARCHAR", List.of("location", "city")));
    EndpointDefinition endpoint = new EndpointDefinition("/geo", "geo", columns, true, postBody);

    wm.stubFor(post("/geo").willReturn(okJson("""
        [
          {"id": "1", "location": {"city": "NYC"}}
        ]
        """)));

    List<ColumnHandle> lookupSchema = List.of(new RestColumnHandle("request_filter_id", VarcharType.VARCHAR));
    List<ColumnHandle> outputSchema = List.of(
        new RestColumnHandle("request_filter_id", VarcharType.VARCHAR),
        new RestColumnHandle("location_city", VarcharType.VARCHAR));

    RecordSet inputRecordSet = new InMemoryRecordSet(List.of(VarcharType.VARCHAR), List.of(List.of("1")));

    RestConnectorIndex index = new RestConnectorIndex(config(), endpoint, lookupSchema, outputSchema);
    ConnectorPageSource pageSource = index.lookup(inputRecordSet);
    RecordCursor cursor = ((RecordPageSource) pageSource).getCursor();

    assertTrue(cursor.advanceNextPosition());
    assertFalse(cursor.isNull(1), "location_city should be populated from the nested response field");
    assertEquals("NYC", cursor.getSlice(1).toStringUtf8());
  }

  @Test
  void keyColumnMatchesResponseFieldCaseInsensitively() throws Exception {
    // Some APIs (e.g. MaxMind-style GeoIP APIs) echo the lookup key back under a differently
    // -cased field name than the request used (request field: "ip", response field: "IP").
    // Assumed target behavior (not yet confirmed): key matching should be case-insensitive, so
    // this key column still gets populated instead of coming back null purely because of a
    // capitalization difference between request and response field names.
    PostFilterDefinition ipFilter = new PostFilterDefinition("ip", "VARCHAR", true, false, List.of("ip"));
    Map<String, Object> template = new HashMap<>();
    template.put("ip", null);
    PostBodyDefinition postBody = new PostBodyDefinition(template, List.of(ipFilter), true);
    EndpointDefinition endpoint = new EndpointDefinition("/lookup", "lookup", List.of(), true, postBody);

    wm.stubFor(post("/lookup").willReturn(okJson("""
        [
          {"IP": "1.2.3.4", "City": "Springfield"}
        ]
        """)));

    List<ColumnHandle> lookupSchema = List.of(new RestColumnHandle("request_filter_ip", VarcharType.VARCHAR));
    List<ColumnHandle> outputSchema = List.of(
        new RestColumnHandle("request_filter_ip", VarcharType.VARCHAR),
        new RestColumnHandle("City", VarcharType.VARCHAR));

    RecordSet inputRecordSet = new InMemoryRecordSet(List.of(VarcharType.VARCHAR), List.of(List.of("1.2.3.4")));

    RestConnectorIndex index = new RestConnectorIndex(config(), endpoint, lookupSchema, outputSchema);
    ConnectorPageSource pageSource = index.lookup(inputRecordSet);
    RecordCursor cursor = ((RecordPageSource) pageSource).getCursor();

    assertTrue(cursor.advanceNextPosition());
    assertFalse(cursor.isNull(0), "request_filter_ip should match the response's \"IP\" field case-insensitively");
    assertEquals("1.2.3.4", cursor.getSlice(0).toStringUtf8());
  }
}
