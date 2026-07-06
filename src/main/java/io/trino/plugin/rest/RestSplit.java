package io.trino.plugin.rest;

import io.trino.plugin.rest.openapi.EndpointDefinition;
import io.trino.spi.connector.ConnectorSplit;

public record RestSplit(String url, EndpointDefinition endpointDefinition) implements ConnectorSplit {
}
