package com.geto.jido.server.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link YtDlpRunner} against a real subprocess.
 *
 * <p>The stdout volume is the point: a real YouTube
 * {@code --dump-single-json} response runs to hundreds of KB, well past the
 * pipe buffer. An implementation that waits for the process to exit before
 * reading stdout deadlocks on any real URL and only ever looks like "slow".
 *
 * <p>Fake yt-dlp is a shell script because {@link ProcessBuilder} does not go
 * through a shell, so a pipeline cannot be passed as the binary directly.
 */
class YtDlpRunnerTest {

    @TempDir
    Path tempDir;

    private YtDlpRunner runnerWith(Duration timeout, String scriptName, String scriptBody) {
        Path script = writeScript(scriptName, scriptBody);
        return new YtDlpRunner(new ResolveProperties(
                script.toString(), timeout, Duration.ofSeconds(5), "default", false, Duration.ofHours(1)));
    }

    private Path writeScript(String name, String body) {
        try {
            Path script = tempDir.resolve(name);
            Files.writeString(script, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
            if (!script.toFile().setExecutable(true)) {
                throw new IllegalStateException("could not make " + script + " executable");
            }
            return script;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void drainsStdoutLargerThanThePipeBuffer() {
        // 5MB of stdout cannot fit in a pipe buffer, so this only completes if
        // the reader drains while the writer is blocked.
        YtDlpRunner runner = runnerWith(Duration.ofSeconds(30), "big.sh",
                "head -c 5000000 /dev/zero | tr '\\0' 'a'\n");

        YtDlpRunner.Result result = runner.run("https://example.com/big");

        assertThat(result.ok()).isTrue();
        assertThat(result.json().length()).isGreaterThan(4_000_000);
    }

    @Test
    void surfacesNonZeroExitAsFailure() {
        YtDlpRunner runner = runnerWith(Duration.ofSeconds(10), "fail.sh",
                "echo 'some noise' >&2\n"
                        + "echo 'ERROR: Video unavailable' >&2\n"
                        + "exit 1\n");

        YtDlpRunner.Result result = runner.run("https://example.com/gone");

        assertThat(result.ok()).isFalse();
        // The useful line is the ERROR: one, not the leading noise.
        assertThat(result.error()).isEqualTo("Video unavailable");
    }

    @Test
    void reportsMissingBinaryInsteadOfThrowing() {
        YtDlpRunner runner = new YtDlpRunner(new ResolveProperties(
                tempDir.resolve("does-not-exist").toString(),
                Duration.ofSeconds(10), Duration.ofSeconds(5), "default", false, Duration.ofHours(1)));

        YtDlpRunner.Result result = runner.run("https://example.com/x");

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not available");
    }

    @Test
    void timesOutHungProcess() {
        YtDlpRunner runner = runnerWith(Duration.ofSeconds(1), "hang.sh", "sleep 60\n");

        assertThatThrownBy(() -> runner.run("https://example.com/slow"))
                .isInstanceOf(YtDlpRunner.ResolveTimeoutException.class);
    }

    @Test
    void passesTheUrlThroughAsAnArgument() {
        YtDlpRunner runner = runnerWith(Duration.ofSeconds(10), "echo.sh", "echo \"$@\"\n");

        YtDlpRunner.Result result = runner.run("https://example.com/track?a=1");

        assertThat(result.ok()).isTrue();
        assertThat(result.json()).contains("https://example.com/track?a=1");
        // A URL split across arguments would mean an injection or a lost query string.
        assertThat(result.json()).doesNotContain("echo");
    }
}
