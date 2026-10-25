package com.geto.jido.server.download;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ProgressTrackingInputStreamTest {

    @Test
    void countsBytesAcrossReads() throws IOException {
        byte[] payload = new byte[10_000];
        var progress = new AtomicLong();

        try (var stream = new ProgressTrackingInputStream(
                new ByteArrayInputStream(payload), payload.length, () -> false, progress::set)) {
            byte[] buffer = new byte[1024];
            while (stream.read(buffer) != -1) {
                // drain
            }
            assertThat(progress.get()).isEqualTo(10_000);
            assertThat(stream.transferred()).isEqualTo(10_000);
        }
    }

    @Test
    void passesContentThroughUnchanged() throws IOException {
        byte[] payload = "the actual bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try (var stream = new ProgressTrackingInputStream(
                new ByteArrayInputStream(payload), payload.length, () -> false, ignored -> {})) {
            assertThat(stream.readAllBytes()).isEqualTo(payload);
        }
    }

    @Test
    void abortsOnceCancelledSoALargeTransferStopsEarly() {
        byte[] payload = new byte[5_000_000];
        var cancelled = new AtomicBoolean(false);
        var progress = new AtomicLong();

        try (var stream = new ProgressTrackingInputStream(
                new ByteArrayInputStream(payload), payload.length, cancelled::get, progress::set)) {

            byte[] buffer = new byte[64 * 1024];
            // Cancel once a little has moved, then keep reading: the next read
            // must fail rather than draining 5MB to completion.
            stream.read(buffer);
            cancelled.set(true);

            assertThatThrownBy(() -> {
                while (stream.read(buffer) != -1) {
                    // keep reading until it refuses
                }
            }).isInstanceOf(ProgressTrackingInputStream.DownloadCancelledException.class);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void reportsDeclaredSize() throws IOException {
        try (var stream = new ProgressTrackingInputStream(
                new ByteArrayInputStream(new byte[4]), 4096, () -> false, ignored -> {})) {
            assertThat(stream.declaredSize()).isEqualTo(4096);
        }
    }
}