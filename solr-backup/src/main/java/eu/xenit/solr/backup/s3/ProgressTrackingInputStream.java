package eu.xenit.solr.backup.s3;

import lombok.NonNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * Custom InputStream wrapper that reports the number of bytes read to a listener.
 */
public class ProgressTrackingInputStream extends FilterInputStream {

    private final @NonNull Consumer<Long> listener;
    private long bytesRead = 0;

    /**
     * Creates a {@code FilterInputStream}
     * by assigning the  argument {@code in}
     * to the field {@code this.in} so as
     * to remember it for later use.
     *
     * @param in the underlying input stream, or {@code null} if
     *           this instance is to be created without an underlying stream.
     * @param listener the listener to report the number of bytes read to
     */
    protected ProgressTrackingInputStream(InputStream in, @NonNull Consumer<Long> listener) {
        super(in);
        this.listener = listener;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            bytesRead += 1;
            listener.accept(bytesRead);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int bytes = super.read(b, off, len);
        if (bytes > 0) {
            bytesRead += bytes;
            listener.accept(bytesRead);
        }
        return bytes;
    }
}
