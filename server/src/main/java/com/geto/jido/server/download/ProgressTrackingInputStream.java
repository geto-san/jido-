package com.geto.jido.server.download;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Wraps a media stream to count bytes, report progress, and abort on cancel.
 *
 * <p>Exists so cancellation and progress happen *as the bytes flow* rather than
 * after the fact. Without it, a cancel would only be noticed once a multi-
 * gigabyte file had already been written to disk.
 */
class ProgressTrackingInputStream extends FilterInputStream {

    private static final int READ_BUFFER = 1 << 16;

    private final long declaredSize;
    private final BooleanSupplier cancelRequested;
    private final LongConsumer onProgress;
    private long transferred;

    ProgressTrackingInputStream(
            InputStream delegate,
            long declaredSize,
            BooleanSupplier cancelRequested,
            LongConsumer onProgress) {
        super(delegate);
        this.declaredSize = declaredSize;
        this.cancelRequested = cancelRequested;
        this.onProgress = onProgress;
    }

    @Override
    public int read() throws IOException {
        checkCancelled();
        int value = super.read();
        if (value != -1) {
            record(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        checkCancelled();
        int count = super.read(buffer, offset, length);
        if (count > 0) {
            record(count);
        }
        return count;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        record(skipped);
        return skipped;
    }

    private void record(long count) {
        transferred += count;
        onProgress.accept(transferred);
    }

    private void checkCancelled() {
        if (cancelRequested.getAsBoolean()) {
            throw new DownloadCancelledException();
        }
    }

    long transferred() {
        return transferred;
    }

    long declaredSize() {
        return declaredSize;
    }

    static class DownloadCancelledException extends RuntimeException {
        DownloadCancelledException() {
            super("Download was cancelled");
        }
    }
}