package io.trino.plugin.rest;

import java.util.List;

import io.trino.spi.Plugin;
import io.trino.spi.connector.ConnectorFactory;

public class RestPlugin implements Plugin {
    @Override
    public Iterable<ConnectorFactory> getConnectorFactories() {
        return List.of(new RestConnectorFactory());
    }

}
