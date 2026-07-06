package io.trino.plugin.rest;

import io.airlift.slice.Slice;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.RecordCursor;
import io.trino.spi.type.Type;

public class RestRecordCursor implements RecordCursor {

    public RestRecordCursor(RestSplit split, RestConfig config, List<ColumnMetadata> columns) {
    }

    @Override
    public long getCompletedBytes() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getCompletedBytes'");
    }

    @Override
    public long getReadTimeNanos() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getReadTimeNanos'");
    }

    @Override
    public Type getType(int field) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getType'");
    }

    @Override
    public boolean advanceNextPosition() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'advanceNextPosition'");
    }

    @Override
    public boolean getBoolean(int field) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getBoolean'");
    }

    @Override
    public long getLong(int field) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getLong'");
    }

    @Override
    public double getDouble(int field) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getDouble'");
    }

    @Override
    public Slice getSlice(int field) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getSlice'");
    }

    @Override
    public Object getObject(int field) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getObject'");
    }

    @Override
    public boolean isNull(int field) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'isNull'");
    }

    @Override
    public void close() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'close'");
    }

}
