package com.geto.jido.server.resolve;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs yt-dlp as a subprocess and parses its JSON output.
 *
 * <p>Why a subprocess rather than a Java port: yt-dlp's extractors track
 * YouTube's cipher, n-sig, and PO-token changes continuously. Porting them to
 * Java would mean owning that breakage on a schedule dictated by upstream.
 * The container ships the binary; this class owns the orchestration.
 *
 * <p>Every invocation is bounded by {@link ResolveProperties#timeout()} and
 * drained fully, because a subprocess whose pipe is never read deadlocks as
 * soon as it fills the OS pipe buffer.
 */
@Component
public class YtDlpRunner {

    private static final Logger log = LoggerFactory.getLogger(YtDlpRunner.class);
    private static final int MAX_STDERR_CHARS = 2000;

    private final ResolveProperties properties;

    public YtDlpRunner(ResolveProperties properties) {
        this.properties = properties;
    }

    /**
     * @return the parsed JSON object yt-dlp printed, or empty when the URL is
     *     unsupported, private, geo-blocked, or the binary is missing.
     * @throws ResolveTimeoutException when the process outlives the timeout
     */
    public Result run(String url) {
        List<String> command = new ArrayList<>(List.of(
                properties.binary(),
                "--dump-single-json",
                // Age-gate and consent walls are the most common cause of a
                // confident-but-wrong "unsupported" from yt-dlp on the platforms
                // the app actually cares about.
                "--extractor-args", "youtube:player_client=android,web",
                "--no-warnings",
                // Suppress progress noise so stdout stays parseable.
                "--no-progress",
                "--socket-timeout", String.valueOf(properties.connectTimeout().toSeconds()),
                url));
        if (!"default".equals(properties.cookieFile())) {
            command.add("--cookies");
            command.add(properties.cookieFile());
        }

        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException e) {
            log.error("Could not start '{}'. Is yt-dlp installed and on PATH?", properties.binary(), e);
            return Result.failed("yt-dlp is not available on this server");
        }

        var stdout = new AtomicReference<String>();
        var stderr = new AtomicReference<String>();
        try {
            // Both pipes must be drained *while* the process runs. A YouTube
            // --dump-single-json response is ~500KB, far past the ~64KB pipe
            // buffer, so waiting for exit first would leave yt-dlp blocked on
            // write and the wait would always time out.
            Thread outReader = reader("yt-dlp-stdout",
                    () -> stdout.set(readAllQuietly(process.getInputStream())));
            Thread errReader = reader("yt-dlp-stderr",
                    () -> stderr.set(readAllQuietly(process.getErrorStream())));

            boolean finished = process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                // destroyForcibly, then still drain: an orphaned yt-dlp keeps
                // running and holds the process table.
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new ResolveTimeoutException(properties.timeout());
            }

            // The readers finish once the pipes close, which happens on exit.
            outReader.join(5000);
            errReader.join(5000);

            String out = stdout.get() == null ? "" : stdout.get();
            String err = truncate(stderr.get() == null ? "" : stderr.get());

            if (process.exitValue() != 0) {
                log.warn("yt-dlp exited {} for a {} URL: {}",
                        process.exitValue(), Platform.from(url), err);
                return Result.failed(summarise(err));
            }
            return Result.succeeded(out.strip(), Platform.from(url));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return Result.failed("Resolution was interrupted");
        }
    }

    /**
     * Daemon so a reader can never keep the JVM alive if the process is
     * killed mid-resolution.
     */
    private static Thread reader(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Read errors are not fatal: a partial diagnostic is still more useful
     * than an exception, and stdout is the part we actually parse.
     */
    private static String readAllQuietly(InputStream stream) {
        try {
            return readAll(stream);
        } catch (IOException e) {
            return "";
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** yt-dlp can emit a page of traceback; the API only needs the first lines. */
    private static String truncate(String value) {
        return value.length() <= MAX_STDERR_CHARS ? value : value.substring(0, MAX_STDERR_CHARS) + "…";
    }

    /**
     * yt-dlp's useful error is the last "ERROR: ..." line, not the first, which
     * is usually a progress or cookie warning.
     */
    private static String summarise(String stderr) {
        return stderr.lines()
                .filter(line -> line.startsWith("ERROR:"))
                .findFirst()
                .map(line -> line.substring("ERROR:".length()).strip())
                .orElseGet(() -> stderr.isBlank() ? "yt-dlp failed" : stderr.lines().findFirst().orElse("yt-dlp failed"));
    }

    public record Result(boolean ok, String json, Platform platform, String error) {

        public static Result succeeded(String json, Platform platform) {
            return new Result(true, json, platform, null);
        }

        public static Result failed(String error) {
            return new Result(false, null, Platform.GENERIC, error);
        }
    }

    public static class ResolveTimeoutException extends RuntimeException {
        public ResolveTimeoutException(Duration timeout) {
            super("Resolution timed out after " + timeout.toSeconds() + "s");
        }
    }
}
