package com.geto.jido.server.download;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Queue and worker tuning.
 *
 * <p>{@code concurrency} defaults to 1 on purpose. The Android client downloads
 * on-device and this backend is an orchestrator; a handful of concurrent
 * yt-dlp subprocesses plus outbound transfers is already the dominant load, and
 * raising it before measuring just converts a slow queue into an out-of-memory
 * kill.
 */
@ConfigurationProperties(prefix = "jido.downloads")
public record DownloadProperties(
        @DefaultValue("1") int concurrency,
        @DefaultValue("PT1S") Duration pollInterval,
        @DefaultValue("PT2H") Duration jobTimeout,
        @DefaultValue("PT10S") Duration connectTimeout,
        @DefaultValue("PT30M") Duration requestTimeout,
        @DefaultValue("20") int claimBatchSize,
        @DefaultValue("PT2H") Duration staleAfter) {}