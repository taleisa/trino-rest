package io.trino.plugin.rest;

import io.trino.spi.connector.ConnectorTransactionHandle;

// A named singleton so Jackson can serialize/deserialize it when Trino ships table handles
// from coordinator to worker; an anonymous class has no type info Jackson can record.
public enum RestTransactionHandle implements ConnectorTransactionHandle {
    INSTANCE
}
