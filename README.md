# Jido

Android app that watches the clipboard for Pinterest / Instagram / TikTok
links and automatically downloads the media via a RapidAPI downloader
endpoint.

## Before you build

1. Open `app/src/main/java/com/geto/jido/ClipboardService.kt`.
2. In `fetchDirectMediaUrl()`, fill in:
   - `RAPIDAPI_KEY` — your RapidAPI key
   - `RAPIDAPI_HOST` — the host of the specific downloader API you subscribe to
   - `API_ENDPOINT` — the full POST URL for that API
3. Check `parseDirectUrl()` matches the JSON shape your chosen API actually
   returns (field names vary between listings) and adjust the key name if
   needed.

**Don't commit real API keys.** For anything beyond local testing, move
these into `local.properties` / `BuildConfig` fields (git-ignored) instead
of hardcoding them in source.

## Notes on platform terms of service

Pinterest, Instagram, and TikTok's terms of service generally restrict
automated scraping/downloading of content through unofficial channels. This
project is for personal/private use with content you already have the
right to view and save; it isn't a redistribution tool. Consider this
before publishing the app publicly or to app stores — Play Store policy in
particular restricts apps whose main purpose is downloading media from
platforms that prohibit it in their own ToS.

## Architecture

- `ClipboardService` — foreground service, listens for clipboard changes,
  detects supported links, calls the downloader API, and enqueues the file
  with `DownloadManager`.
- `DownloadCompleteReceiver` — catches `ACTION_DOWNLOAD_COMPLETE`, re-scans
  the file into the media gallery, and shows a completion Toast.
- `MainActivity` — requests `POST_NOTIFICATIONS` on Android 13+, then starts
  the foreground service.

## Minimum requirements

- Android Studio Koala+ (AGP 8.5+)
- Kotlin 1.9.24
- minSdk 26, targetSdk 34
