package com.geto.jido.server.resolve;

import java.util.Locale;
import java.util.Optional;

/**
 * Recognises the source platform from the URL host.
 *
 * <p>Used to pick yt-dlp arguments (and later to route to a platform-specific
 * API), so a URL the app already knows how to handle directly never pays for
 * a subprocess.
 */
public enum Platform {
    YOUTUBE,
    TIKTOK,
    INSTAGRAM,
    PINTEREST,
    SPOTIFY,
    TWITTER,
    REDDIT,
    SOUNDCLOUD,
    FACEBOOK,
    GENERIC;

    private static final String[] TIKTOK_HOSTS = {"tiktok.com", "vm.tiktok.com", "vt.tiktok.com"};
    private static final String[] INSTAGRAM_HOSTS = {"instagram.com", "instagr.am", "ddinstagram.com"};
    private static final String[] PINTEREST_HOSTS = {"pinterest.com", "pin.it", "pinterest.ca", "pinterest.co.uk"};
    private static final String[] SPOTIFY_HOSTS = {"spotify.com", "spoti.fi", "open.spotify.com"};
    private static final String[] TWITTER_HOSTS = {"twitter.com", "x.com", "t.co", "fxtwitter.com", "vxtwitter.com"};
    private static final String[] REDDIT_HOSTS = {"reddit.com", "redd.it", "v.redd.it", "i.redd.it"};
    private static final String[] SOUNDCLOUD_HOSTS = {"soundcloud.com", "snd.sc"};
    private static final String[] FACEBOOK_HOSTS = {"facebook.com", "fb.watch", "fb.com"};
    private static final String[] YOUTUBE_HOSTS = {
        "youtube.com", "youtu.be", "m.youtube.com", "music.youtube.com", "youtube-nocookie.com"
    };

    /**
     * @param url a full share URL; unparseable input yields {@link #GENERIC}
     *     rather than an error, since yt-dlp may still handle it.
     */
    public static Platform from(String url) {
        if (url == null || url.isBlank()) {
            return GENERIC;
        }
        Optional<String> host;
        try {
            host = Optional.of(java.net.URI.create(url.strip()).getHost());
        } catch (IllegalArgumentException e) {
            return GENERIC;
        }
        if (host.isEmpty()) {
            return GENERIC;
        }
        // Strip a leading "www." once so host lists don't need the prefix.
        String h = host.get().toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) {
            h = h.substring(4);
        }
        if (matches(h, YOUTUBE_HOSTS)) return YOUTUBE;
        if (matches(h, TIKTOK_HOSTS)) return TIKTOK;
        if (matches(h, INSTAGRAM_HOSTS)) return INSTAGRAM;
        if (matches(h, PINTEREST_HOSTS)) return PINTEREST;
        if (matches(h, SPOTIFY_HOSTS)) return SPOTIFY;
        if (matches(h, TWITTER_HOSTS)) return TWITTER;
        if (matches(h, REDDIT_HOSTS)) return REDDIT;
        if (matches(h, SOUNDCLOUD_HOSTS)) return SOUNDCLOUD;
        if (matches(h, FACEBOOK_HOSTS)) return FACEBOOK;
        return GENERIC;
    }

    /** Suffix match so "m.youtube.com" and "www.youtube.com" both hit. */
    private static boolean matches(String host, String[] candidates) {
        for (String candidate : candidates) {
            if (host.equals(candidate) || host.endsWith("." + candidate)) {
                return true;
            }
        }
        return false;
    }

    public boolean isKnown() {
        return this != GENERIC;
    }
}
