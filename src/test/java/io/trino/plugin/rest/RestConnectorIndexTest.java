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
    // A real parsed endpoint cross-references filters against the response's own columns() - both
    // "product_name" and "date" are echoed back under the same name here, so each filter carries
    // that column as its responseColumn, and its Trino-visible name becomes that column's own
    // name ("product_name"/"date") rather than "request_filter_product_name"/"request_filter_date".
    ColumnDefinition productNameColumn = new ColumnDefinition("VARCHAR", List.of("product_name"));
    ColumnDefinition dateColumn = new ColumnDefinition("VARCHAR", List.of("date"));
    PostFilterDefinition productName = new PostFilterDefinition("VARCHAR", true, false,
        List.of("product_name"), productNameColumn);
    PostFilterDefinition date = new PostFilterDefinition("VARCHAR", true, false, List.of("date"), dateColumn);
    Map<String, Object> template = new HashMap<>();
    template.put("product_name", null);
    template.put("date", null);
    PostBodyDefinition postBody = new PostBodyDefinition(template, List.of(productName, date), true);
    List<ColumnDefinition> columns = List.of(
        productNameColumn, dateColumn,
        new ColumnDefinition("DOUBLE", List.of("discount_pct")),
        new ColumnDefinition("BOOLEAN", List.of("in_stock")));
    return new EndpointDefinition("/enrich", "enrich", columns, true, postBody);
  }

  @Test
  void lookupReturnsKeyColumnValuesNotNull() throws Exception {
    // The target API only ever echoes back the bare field names (product_name/date) - which is
    // exactly what these columns are now named, since they have a matching responseColumn.
    wm.stubFor(post("/enrich").willReturn(okJson("""
        [
          {"product_name": "Widget", "date": "2024-01-01", "discount_pct": 12.5, "in_stock": true}
        ]
        """)));

    List<ColumnHandle> lookupSchema = List.of(
        new RestColumnHandle("product_name", VarcharType.VARCHAR),
        new RestColumnHandle("date", VarcharType.VARCHAR));
    // outputSchema always includes the key columns alongside the requested value columns - this
    // is what Trino's IndexSnapshotBuilder hashes on to correlate results back to probe rows.
    List<ColumnHandle> outputSchema = List.of(
        new RestColumnHandle("product_name", VarcharType.VARCHAR),
        new RestColumnHandle("date", VarcharType.VARCHAR),
        new RestColumnHandle("discount_pct", DoubleType.DOUBLE),
        new RestColumnHandle("in_stock", BooleanType.BOOLEAN));

    RecordSet inputRecordSet = new InMemoryRecordSet(
        List.of(VarcharType.VARCHAR, VarcharType.VARCHAR),
        List.of(List.of("Widget", "2024-01-01")));

    RestConnectorIndex index = new RestConnectorIndex(config(), enrichEndpoint(), lookupSchema, outputSchema);
    ConnectorPageSource pageSource = index.lookup(inputRecordSet);
    RecordCursor cursor = ((RecordPageSource) pageSource).getCursor();

    assertTrue(cursor.advanceNextPosition());
    assertFalse(cursor.isNull(0), "product_name should be populated from the response");
    assertEquals("Widget", cursor.getSlice(0).toStringUtf8());
    assertFalse(cursor.isNull(1), "date should be populated from the response");
    assertEquals("2024-01-01", cursor.getSlice(1).toStringUtf8());
    assertEquals(12.5, cursor.getDouble(2));
    assertTrue(cursor.getBoolean(3));
  }

  @Test
  void nestedOutputColumnValueIsReadCorrectly() throws Exception {
    // The column name "location_city" reflects a nested field ({"location": {"city": ...}}), so
    // its real path (as a real parsed endpoint's columns() would carry it) is ["location",
    // "city"], not the flat name itself.
    PostFilterDefinition idFilter = new PostFilterDefinition("VARCHAR", true, false, List.of("id"), null);
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
  void jsonObjectColumnValueIsReadAsRealJsonNotEmptyString() throws Exception {
    // Same underlying bug as RestRecordCursor: a free-form/dictionary field maps to an opaque
    // JSON (VARCHAR-typed) column, but normalizeForType() called JsonNode.asText() unconditionally
    // - meaningful only for scalar nodes, silently "" for a container (object/array) node.
    PostFilterDefinition idFilter = new PostFilterDefinition("VARCHAR", true, false, List.of("id"), null);
    Map<String, Object> template = new HashMap<>();
    template.put("id", null);
    PostBodyDefinition postBody = new PostBodyDefinition(template, List.of(idFilter), true);
    List<ColumnDefinition> columns = List.of(new ColumnDefinition("JSON", List.of("names")));
    EndpointDefinition endpoint = new EndpointDefinition("/dictlookup", "dictlookup", columns, true, postBody);

    wm.stubFor(post("/dictlookup").willReturn(okJson("""
        [
          {"id": "1", "names": {"en": "New York", "de": "New York"}}
        ]
        """)));

    List<ColumnHandle> lookupSchema = List.of(new RestColumnHandle("request_filter_id", VarcharType.VARCHAR));
    List<ColumnHandle> outputSchema = List.of(
        new RestColumnHandle("request_filter_id", VarcharType.VARCHAR),
        new RestColumnHandle("names", VarcharType.VARCHAR));

    RecordSet inputRecordSet = new InMemoryRecordSet(List.of(VarcharType.VARCHAR), List.of(List.of("1")));

    RestConnectorIndex index = new RestConnectorIndex(config(), endpoint, lookupSchema, outputSchema);
    ConnectorPageSource pageSource = index.lookup(inputRecordSet);
    RecordCursor cursor = ((RecordPageSource) pageSource).getCursor();

    assertTrue(cursor.advanceNextPosition());
    assertFalse(cursor.isNull(1), "names should be populated from the response");
    assertEquals("{\"en\":\"New York\",\"de\":\"New York\"}", cursor.getSlice(1).toStringUtf8());
  }

  @Test
  void keyColumnMatchesResponseFieldCaseInsensitively() throws Exception {
    // Some APIs (e.g. MaxMind-style GeoIP APIs) echo the lookup key back under a differently
    // -cased field name than the request used (request field: "ip", response field: "IP").
    // OpenApiSchemaParser resolves this once, at parse time, by matching the filter's name
    // against the response's own columns() case-insensitively - so the filter's Trino column name
    // becomes the response's own name ("IP"), and gets read via the normal, exact-match
    // nested-path lookup rather than a flat lookup under the request-side name.
    ColumnDefinition ipColumn = new ColumnDefinition("VARCHAR", List.of("IP"));
    ColumnDefinition cityColumn = new ColumnDefinition("VARCHAR", List.of("City"));
    PostFilterDefinition ipFilter = new PostFilterDefinition("VARCHAR", true, false, List.of("ip"), ipColumn);
    Map<String, Object> template = new HashMap<>();
    template.put("ip", null);
    PostBodyDefinition postBody = new PostBodyDefinition(template, List.of(ipFilter), true);
    EndpointDefinition endpoint = new EndpointDefinition("/lookup", "lookup", List.of(ipColumn, cityColumn), true,
        postBody);

    wm.stubFor(post("/lookup").willReturn(okJson("""
        [
          {"IP": "1.2.3.4", "City": "Springfield"}
        ]
        """)));

    List<ColumnHandle> lookupSchema = List.of(new RestColumnHandle("IP", VarcharType.VARCHAR));
    List<ColumnHandle> outputSchema = List.of(
        new RestColumnHandle("IP", VarcharType.VARCHAR),
        new RestColumnHandle("City", VarcharType.VARCHAR));

    RecordSet inputRecordSet = new InMemoryRecordSet(List.of(VarcharType.VARCHAR), List.of(List.of("1.2.3.4")));

    RestConnectorIndex index = new RestConnectorIndex(config(), endpoint, lookupSchema, outputSchema);
    ConnectorPageSource pageSource = index.lookup(inputRecordSet);
    RecordCursor cursor = ((RecordPageSource) pageSource).getCursor();

    assertTrue(cursor.advanceNextPosition());
    assertFalse(cursor.isNull(0), "IP should be populated even though the request used a different casing (\"ip\")");
    assertEquals("1.2.3.4", cursor.getSlice(0).toStringUtf8());
    assertEquals("Springfield", cursor.getSlice(1).toStringUtf8());
  }
}
