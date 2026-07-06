package io.trino.plugin.rest;
import java.util.Map;

import io.trino.spi.connector.Connector;
import io.trino.spi.connector.ConnectorContext;
import io.trino.spi.connector.ConnectorFactory;



public class RestConnectorFactory implements ConnectorFactory{

    @Override
    public String getName() {
        return "rest";
    }

    @Override
    public Connector create(String catalogName, Map<String, String> config, ConnectorContext context) {
        RestConfig restConfig = new RestConfig(config);
        return new RestConnector(restConfig);
    }
    
}
