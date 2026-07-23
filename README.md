# Jido

Android app that watches the clipboard for Pinterest / Instagram / TikTok
links, automatically downloads the media via a RapidAPI downloader
endpoint, and shows the download list with live progress.

## Setup

1. Copy `local.properties.example` to `local.properties` (git-ignored) and
   set your real RapidAPI key:
   ```
   RAPIDAPI_KEY=your_real_key_here
   ```
   The key is injected at build time via `BuildConfig.RAPIDAPI_KEY` — it is
   never hardcoded in source, so it won't end up committed to git.

2. Open `app/src/main/java/com/geto/jido/ClipboardService.kt` and check the
   `apiConfigs` map. It currently has an `Instagram` entry wired to
   `instagram120.p.rapidapi.com`'s `/api/instagram/get?url=` GET endpoint.
   Add entries for `Pinterest` / `TikTok` once you've picked APIs for them.

3. `parseDirectUrl()` tries several common RapidAPI response field names
   (`url`, `download_url`, `link`, `medias[0].url`, `items[0].url`,
   `result.url`). If your API's response doesn't match any of those, check
   Logcat (filter: `ClipboardService`) for the raw JSON body on your first
   real request and add the right field name.

## ⚠️ Security note

An earlier version of this repo had a real RapidAPI key hardcoded directly
in `ClipboardService.kt` and committed to git history. Even after removing
it from the current file, **it still exists in past commits** on GitHub.
If you haven't already:
- Regenerate/rotate that RapidAPI key from your RapidAPI dashboard.
- If this repo is public, consider it burned — either rewrite git history
  (e.g. with `git filter-repo` or BFG Repo-Cleaner) or make a fresh repo.

## Notes on platform terms of service

Pinterest, Instagram, and TikTok's terms of service generally restrict
automated scraping/downloading of content through unofficial channels. This
project is for personal/private use with content you already have the
right to view and save; it isn't a redistribution tool. Consider this
before publishing the app publicly or to app stores — Play Store policy in
particular restricts apps whose main purpose is downloading media from
platforms that prohibit it in their own ToS.

## Architecture

- `ClipboardService` — foreground service; listens for clipboard changes,
  detects supported links, calls the downloader API via OkHttp, enqueues
  the file with `DownloadManager`, and polls for progress.
- `DownloadRepository` — in-memory, process-wide `StateFlow<List<DownloadItem>>`
  shared between the service and the UI. Resets if the app process is
  killed; swap for a Room database if you need history to survive that.
- `DownloadsAdapter` / `activity_main.xml` / `item_download.xml` — the
  downloads list UI with per-item status and progress bar.
- `DownloadCompleteReceiver` — catches `ACTION_DOWNLOAD_COMPLETE`, re-scans
  the file into the media gallery, shows a completion Toast, and syncs the
  final status into `DownloadRepository` as a backstop.
- `MainActivity` — requests `POST_NOTIFICATIONS` on Android 13+, starts the
  foreground service, and renders the downloads list. Also has a manual
  "paste a link" row (EditText + paste button + Download button) at the top,
  which sends the link straight to `ClipboardService` via an Intent extra
  (`ClipboardService.EXTRA_MANUAL_LINK`) instead of relying on the clipboard
  listener — handy for testing a link immediately, or as a fallback on
  devices where `OnPrimaryClipChangedListener` doesn't fire reliably. It runs
  through the exact same fetch/download pipeline as an auto-detected clipboard
  link.

## Minimum requirements

- Android Studio Koala+ (AGP 8.5+)
- Kotlin 1.9.24
- minSdk 26, targetSdk 34
