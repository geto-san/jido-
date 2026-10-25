package com.geto.jido.server.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResolveServiceTest {

    private YtDlpRunner runner;
    private ResolveService service;

    @BeforeEach
    void setUp() {
        runner = mock(YtDlpRunner.class);
        ResolveProperties properties = new ResolveProperties(
                "yt-dlp", Duration.ofSeconds(30), Duration.ofSeconds(10), "default", false, Duration.ofHours(6));
        service = new ResolveService(runner, properties);
    }

    @Test
    void prefersMergedHighResolutionFormatOverVideoOnlyStream() {
        // A video-only 1080p stream must lose to a merged 720p one: the client
        // has no muxing step, so a video-only pick would download silently.
        String json = """
                {"title":"clip","formats":[
                  {"format_id":"137","url":"https://cdn/v1080.mp4","height":1080,"acodec":"none","vcodec":"avc1","tbr":4000},
                  {"format_id":"22","url":"https://cdn/v720.mp4","height":720,"acodec":"aac","vcodec":"avc1","tbr":2000}
                ]}""";
        when(runner.run(anyString())).thenReturn(YtDlpRunner.Result.succeeded(json, Platform.YOUTUBE));

        var media = service.resolve("https://youtu.be/abc");

        assertThat(media.directUrl()).isEqualTo("https://cdn/v720.mp4");
        assertThat(media.platform()).isEqualTo(Platform.YOUTUBE);
        assertThat(media.title()).isEqualTo("clip");
        assertThat(media.formatId()).isEqualTo("22");
        assertThat(media.cachedHit()).isFalse();
    }

    @Test
    void fallsBackToTopLevelUrlForSingleFormatSites() {
        String json = """
                {"title":"tiktok clip","url":"https://cdn/tt.mp4","duration":12.5,"format_id":"download"}""";
        when(runner.run(anyString())).thenReturn(YtDlpRunner.Result.succeeded(json, Platform.TIKTOK));

        var media = service.resolve("https://www.tiktok.com/@x/video/1");

        assertThat(media.directUrl()).isEqualTo("https://cdn/tt.mp4");
        assertThat(media.durationSeconds()).isEqualTo(12.5);
        assertThat(media.formatId()).isEqualTo("download");
    }

    @Test
    void cachesSuccessfulResolutionsAndServesSecondCallFromCache() {
        when(runner.run(anyString())).thenReturn(YtDlpRunner.Result.succeeded(
                "{\"url\":\"https://cdn/a.mp4\"}", Platform.GENERIC));

        service.resolve("https://example.com/a");
        var second = service.resolve("https://example.com/a");

        verify(runner, times(1)).run(anyString());
        assertThat(second.cachedHit()).isTrue();
    }

    @Test
    void doesNotCacheFailures() {
        when(runner.run(anyString())).thenReturn(YtDlpRunner.Result.failed("Video unavailable"));

        assertThatThrownBy(() -> service.resolve("https://example.com/dead"))
                .isInstanceOf(ResolveService.ResolutionFailed.class);
        assertThatThrownBy(() -> service.resolve("https://example.com/dead"))
                .isInstanceOf(ResolveService.ResolutionFailed.class);

        // Both attempts hit yt-dlp: a cached failure would outlive the fix.
        verify(runner, times(2)).run(anyString());
    }

    @Test
    void reportsFailureWhenValidJsonHasNoMedia() {
        when(runner.run(anyString())).thenReturn(YtDlpRunner.Result.succeeded(
                "{\"title\":\"playlist\",\"formats\":[]}", Platform.YOUTUBE));

        assertThatThrownBy(() -> service.resolve("https://youtu.be/abc"))
                .isInstanceOf(ResolveService.ResolutionFailed.class);
    }

    @Test
    void reportsFailureOnMalformedJson() {
        when(runner.run(anyString())).thenReturn(
                YtDlpRunner.Result.succeeded("this is not json", Platform.GENERIC));

        assertThatThrownBy(() -> service.resolve("https://example.com/x"))
                .isInstanceOf(ResolveService.ResolutionFailed.class);
    }

    @Test
    void evictsExpiredCacheEntries() {
        ResolveProperties ttlZero = new ResolveProperties(
                "yt-dlp", Duration.ofSeconds(30), Duration.ofSeconds(10), "default", false, Duration.ZERO);
        var shortLived = new ResolveService(runner, ttlZero);
        when(runner.run(anyString())).thenReturn(YtDlpRunner.Result.succeeded(
                "{\"url\":\"https://cdn/a.mp4\"}", Platform.GENERIC));

        shortLived.resolve("https://example.com/a");
        assertThat(shortLived.cacheSize()).isZero();

        // A second call must not have been served from cache.
        shortLived.resolve("https://example.com/a");
        verify(runner, times(2)).run(anyString());
    }

    @Test
    void rejectsBlankUrlWithoutCallingRunner() {
        assertThatThrownBy(() -> service.resolve(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.resolve("  "))
                .isInstanceOf(IllegalArgumentException.class);
        verify(runner, never()).run(anyString());
    }
}
