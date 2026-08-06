package io.trino.plugin.rest;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

// Minimal byte-counting wrapper, used so RestRecordCursor#getCompletedBytes() can report
// progress without buffering the response. Not using Guava's CountingInputStream: Guava is only
// a transitive, test-scope dependency here (pulled in by wiremock), not available to main
// sources.
class CountingInputStream extends FilterInputStream {
    private long count = 0;

    CountingInputStream(InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            count++;
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n != -1) {
            count += n;
        }
        return n;
    }

    long getCount() {
        return count;
    }
}
