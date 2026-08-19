package io.trino.plugin.rest.openapi;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

// Nested inside PostBodyDefinition, itself nested inside EndpointDefinition, which Trino ships
// from coordinator to worker as JSON (via RestSplit); must be JSON-serializable.
public record PostFilterDefinition(
        @JsonProperty("trinoType") String trinoType,
        @JsonProperty("required") boolean required,
        // Whether the source schema declared this as an array-of-scalar property (true)
        // or a bare scalar property (false). Decides how PostBodyDefinition may
        // substitute more than one resolved value: always fine for an array filter,
        // unrepresentable for a scalar one.
        @JsonProperty("isArray") boolean isArray,
        // Real nested property keys from the request body root down to this filter,
        // e.g. ["address", "city"]. Use this to walk requestBody, not name().split("_"),
        // which is ambiguous whenever a property's own key contains an underscore.
        @JsonProperty("path") List<String> path,
        // What this postfilter corresponds to as a response column ie. column in the
        // table. For columns that are part of a post request with a list objects the
        // work is devided between trino and this connector. This connector retrieves
        // the data from the request and trino does the join. For this to happen we must
        // be able to establish a relationship between the request column and the
        // response column.
        @JsonProperty("responseColumn") ColumnDefinition responseColumn) {
    @JsonCreator
    public PostFilterDefinition {
    }

    // Derived, not stored - always exactly path joined with "_", so there's no second
    // representation of the same data to keep in sync. Used as the internal correlation
    // key threaded through RestMetadata/RestSplitManager/RestConnectorIndex/
    // PostBodyDefinition to connect a resolved value back to this filter.
    public String name() {
        return String.join("_", path);
    }

    // The Trino-visible column name for this filter: the response column's own name
    // when this filter is echoed back and readable (see responseColumn above), so a
    // JOIN reads naturally as e.g. "ON postgres.ip = rest.ip" rather than a
    // synthetic write-only-looking name. Falls back to the request_filter_*
    // convention otherwise, signaling "not independently readable."
    public String columnName() {
        return responseColumn != null ? responseColumn.name() : "request_filter_" + name();
    }
}
