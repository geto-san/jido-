package com.geto.jido.server.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlatformTest {

    @ParameterizedTest
    @CsvSource({
        "https://www.youtube.com/watch?v=abc, YOUTUBE",
        "https://youtu.be/abc, YOUTUBE",
        "https://m.youtube.com/watch?v=abc, YOUTUBE",
        "https://music.youtube.com/watch?v=abc, YOUTUBE",
        "https://www.tiktok.com/@x/video/123, TIKTOK",
        "https://vm.tiktok.com/abc, TIKTOK",
        "https://www.instagram.com/reel/abc, INSTAGRAM",
        "https://www.pinterest.com/pin/123, PINTEREST",
        "https://pin.it/abc, PINTEREST",
        "https://open.spotify.com/track/abc, SPOTIFY",
        "https://x.com/u/status/1, TWITTER",
        "https://twitter.com/u/status/1, TWITTER",
        "https://www.reddit.com/r/x/comments/1, REDDIT",
        "https://v.redd.it/abc, REDDIT",
        "https://soundcloud.com/a/b, SOUNDCLOUD",
        "https://www.facebook.com/watch?v=1, FACEBOOK",
        "https://example.com/video.mp4, GENERIC"
    })
    void recognisesPlatformFromHost(String url, Platform expected) {
        assertThat(Platform.from(url)).isEqualTo(expected);
    }

    @Test
    void doesNotMatchHostThatMerelyEndsWithName() {
        // "notyoutube.com" must not be treated as YouTube; suffix matching has
        // to be on a dot boundary or a lookalike domain slips through.
        assertThat(Platform.from("https://notyoutube.com/watch?v=abc")).isEqualTo(Platform.GENERIC);
    }

    @Test
    void blankOrUnparseableInputIsGenericRatherThanAnError() {
        assertThat(Platform.from(null)).isEqualTo(Platform.GENERIC);
        assertThat(Platform.from("")).isEqualTo(Platform.GENERIC);
        assertThat(Platform.from("not a url")).isEqualTo(Platform.GENERIC);
    }
}
